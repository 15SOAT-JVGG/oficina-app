package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.integration;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import br.com.fiap.postech.soat16.fase1.customer.adapter.out.persistence.CustomerRepository;
import br.com.fiap.postech.soat16.fase1.customer.domain.model.Customer;
import br.com.fiap.postech.soat16.fase1.customer.domain.model.enums.DocumentType;
import br.com.fiap.postech.soat16.fase1.shared.test.infrastructure.JwtKeyPairTestResource;
import br.com.fiap.postech.soat16.fase1.shared.test.infrastructure.PostgresTestResource;
import br.com.fiap.postech.soat16.fase1.vehicle.adapter.out.persistence.VehicleRepository;
import br.com.fiap.postech.soat16.fase1.vehicle.domain.model.Vehicle;
import br.com.fiap.postech.soat16.fase1.vehicle.domain.model.enums.VehicleType;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.jwt.build.Jwt;
import io.smallrye.mutiny.Uni;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@QuarkusTestResource(JwtKeyPairTestResource.class)
class WorkOrderAsyncFlowIT {
    @Inject CustomerRepository customers;
    @Inject VehicleRepository vehicles;
    @Inject EstimateResultDecoder decoder;
    @Inject EstimateResultJournal journal;
    @Inject ObjectMapper mapper;

    @Test
    void opensOverHttpPublishesAndReflectsBillingFailure() throws Exception {
        try (var stack = new GenericContainer<>(DockerImageName.parse("localstack/localstack:3.8"))
                .withEnv("SERVICES", "sns,sqs").withEnv("SQS_ENDPOINT_STRATEGY", "path")
                .withCopyFileToContainer(MountableFile.forHostPath(Path.of("infra/localstack/setup-events.sh")),
                        "/etc/localstack/init/ready.d/setup-events.sh")
                .withExposedPorts(4566).waitingFor(Wait.forLogMessage(".*Ready\\..*\\n", 1))) {
            stack.start();
            await(() -> Panache.withTransaction(() -> Panache.getSession().flatMap(session -> session
                    .createNativeQuery("DELETE FROM oficina_mecanica.work_order_estimate_projection").executeUpdate()
                    .chain(() -> session.createNativeQuery("DELETE FROM oficina_mecanica.work_order_outbox").executeUpdate()))));
            var endpoint = URI.create("http://" + stack.getHost() + ":" + stack.getMappedPort(4566));
            var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
            try (var sqs = SqsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                    .credentialsProvider(credentials).build()) {
                var customer = new Customer();
                customer.setFirstName("Maria");
                customer.setLastName("Silva");
                customer.setEmail("test." + UUID.randomUUID() + "@example.com");
                customer.setPhoneNumber("+5511999999999");
                customer.setDocument("DOC-" + UUID.randomUUID());
                customer.setDocumentType(DocumentType.CPF);
                UUID customerId = await(() -> Panache.withTransaction(() -> customers.save(customer))).getId();
                var vehicle = new Vehicle();
                vehicle.setLicensePlate("TST" + String.format("%04d", java.util.concurrent.ThreadLocalRandom.current().nextInt(10_000)));
                vehicle.setManufacturer("Fiat");
                vehicle.setModel("Uno");
                vehicle.setColor("Branco");
                vehicle.setYear(2020);
                vehicle.setKmDriven(1000L);
                vehicle.setType(VehicleType.CAR);
                UUID vehicleId = await(() -> Panache.withTransaction(() -> vehicles.save(vehicle))).getId();
                UUID part = UUID.randomUUID();
                String token = Jwt.issuer("oficina-api").upn("test").groups(Set.of("ADMIN"))
                        .expiresIn(Duration.ofHours(1)).sign();
                String request = """
                        {"customerId":"%s","vehicleId":"%s","description":"Teste",
                         "parts":[{"partId":"%s","quantity":1}]}
                        """.formatted(customerId, vehicleId, part);
                String order = given().auth().oauth2(token).contentType("application/json").body(request)
                        .when().post("/v1/work-orders").then().statusCode(201)
                        .body("workOrder.estimateProgress", org.hamcrest.Matchers.equalTo("AGUARDANDO"))
                        .extract().path("workOrder.workOrderId");
                var transport = new SnsOutboxTransport();
                transport.topicArn = Optional.of("arn:aws:sns:us-east-1:000000000000:os-events.fifo");
                transport.client = SnsAsyncClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                        .credentialsProvider(credentials).build();
                var consumer = new SqsEstimateConsumer(decoder, journal);
                String billingQueue = sqs.getQueueUrl(builder -> builder.queueName("billing-events.fifo")).queueUrl();
                String osQueue = sqs.getQueueUrl(builder -> builder.queueName("os-events.fifo")).queueUrl();
                consumer.queueUrl = Optional.of(osQueue);
                consumer.client = SqsAsyncClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                        .credentialsProvider(credentials).build();
                try {
                    var unavailable = mock(OutboxTransport.class);
                    when(unavailable.publish(any())).thenReturn(Uni.createFrom().failure(new IllegalStateException("broker unavailable")));
                    var failedPublisher = new OutboxPublisher(unavailable);
                    assertThrows(IllegalStateException.class, () -> await(failedPublisher::publishNext));
                    var attempted = ArgumentCaptor.forClass(OutboxTransport.Message.class);
                    verify(unavailable).publish(attempted.capture());
                    Long pending = await(() -> Panache.withSession(() -> Panache.getSession().flatMap(session -> session
                            .createNativeQuery("""
                                    SELECT count(*) FROM oficina_mecanica.work_order_outbox
                                    WHERE work_order_id = :id AND published_at IS NULL
                                    """, Long.class).setParameter("id", UUID.fromString(order)).getSingleResult())));
                    assertEquals(1L, pending);
                    // Uma nova instância representa o worker após reinício, usando a outbox persistida.
                    var publisher = new OutboxPublisher(transport);
                    for (int attempt = 0; attempt < 200; attempt++) {
                        if (!await(publisher::publishNext)) {
                            break;
                        }
                    }
                    var sent = sqs.receiveMessage(builder -> builder.queueUrl(billingQueue).waitTimeSeconds(10)
                            .maxNumberOfMessages(10)).messages();
                    assertEquals(1, sent.size());
                    var opened = mapper.readTree(sent.getFirst().body());
                    assertEquals(order, opened.path("workOrderId").asText());
                    assertEquals(attempted.getValue().eventId().toString(), opened.path("eventId").asText());
                    var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(mapper.readTree(
                            Path.of("docs/events/v1/work-order-opened.schema.json").toFile()));
                    assertTrue(schema.validate(opened).isEmpty());
                    ObjectNode failure = (ObjectNode) mapper.readTree(
                            Path.of("docs/events/v1/estimate-creation-failed.example.json").toFile());
                    failure.put("eventId", UUID.randomUUID().toString());
                    failure.put("workOrderId", order);
                    failure.put("causationId", opened.path("eventId").asText());
                    failure.put("sagaId", opened.path("sagaId").asText());
                    failure.put("correlationId", opened.path("correlationId").asText());
                    sqs.sendMessage(builder -> builder.queueUrl(osQueue).messageBody(failure.toString())
                            .messageGroupId(order).messageDeduplicationId(UUID.randomUUID().toString()));
                    await(consumer::receive);
                    given().auth().oauth2(token).when().get("/v1/work-orders/" + order).then().statusCode(200)
                            .body("estimateProgress", org.hamcrest.Matchers.equalTo("FALHOU"))
                            .body("estimateFailureReason", org.hamcrest.Matchers.equalTo("Peça não encontrada"));
                } finally {
                    consumer.close();
                    transport.close();
                }
            }
        }
    }

    private <T> T await(Supplier<Uni<T>> action) {
        try {
            return VertxContextSupport.subscribeAndAwait(action);
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }
}
