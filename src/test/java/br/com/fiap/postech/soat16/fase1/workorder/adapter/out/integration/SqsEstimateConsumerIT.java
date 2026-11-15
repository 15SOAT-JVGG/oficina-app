package br.com.fiap.postech.soat16.fase1.workorder.adapter.out.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import br.com.fiap.postech.soat16.fase1.shared.test.infrastructure.JwtKeyPairTestResource;
import br.com.fiap.postech.soat16.fase1.shared.test.infrastructure.PostgresTestResource;
import br.com.fiap.postech.soat16.fase1.workorder.application.command.OpenWorkOrderCommand;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.WorkOrderPriority;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@QuarkusTestResource(JwtKeyPairTestResource.class)
class SqsEstimateConsumerIT {
    @Inject
    EstimateResultDecoder decoder;
    @Inject
    EstimateResultJournal results;
    @Inject
    WorkOrderEventJournal openings;
    @Inject
    ObjectMapper mapper;

    @Test
    void commitsResultBeforeDeletingAndIgnoresRedelivery() throws Exception {
        try (var stack = new GenericContainer<>(DockerImageName.parse("localstack/localstack:3.8"))
                .withEnv("SERVICES", "sqs").withEnv("SQS_ENDPOINT_STRATEGY", "path")
                .withExposedPorts(4566).waitingFor(Wait.forLogMessage(".*Ready\\..*\\n", 1))) {
            stack.start();
            URI endpoint = URI.create("http://" + stack.getHost() + ":" + stack.getMappedPort(4566));
            var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
            try (var sqs = SqsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                    .credentialsProvider(credentials).build()) {
                String queue = sqs.createQueue(builder -> builder.queueName("os.fifo")
                        .attributes(java.util.Map.of(QueueAttributeName.FIFO_QUEUE, "true"))).queueUrl();
                UUID order = UUID.randomUUID();
                var request = new OpenWorkOrderCommand(UUID.randomUUID(), UUID.randomUUID(), "Teste", WorkOrderPriority.LOW,
                        null, List.of(new OpenWorkOrderCommand.RequestedService(UUID.randomUUID())), List.of());
                await(() -> Panache.withTransaction(() -> openings.recordOpening(order, request)));
                Object[] ids = await(() -> Panache.withSession(() -> Panache.getSession().flatMap(session -> session
                        .createNativeQuery("""
                                SELECT opening_event_id, saga_id, correlation_id
                                FROM oficina_mecanica.work_order_estimate_projection WHERE work_order_id = :id
                                """, Object[].class).setParameter("id", order).getSingleResult())));
                ObjectNode event = (ObjectNode) mapper.readTree(Path.of("docs/events/v1/estimate-created.example.json").toFile());
                event.put("eventId", UUID.randomUUID().toString());
                event.put("workOrderId", order.toString());
                event.put("causationId", ids[0].toString());
                event.put("sagaId", ids[1].toString());
                event.put("correlationId", ids[2].toString());
                var consumer = new SqsEstimateConsumer(decoder, results);
                consumer.queueUrl = Optional.of(queue);
                var realClient = SqsAsyncClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                        .credentialsProvider(credentials).build();
                var failingClient = mock(SqsAsyncClient.class);
                var receipt = new AtomicReference<String>();
                var deleteAttempts = new AtomicInteger();
                when(failingClient.receiveMessage(any(ReceiveMessageRequest.class))).thenAnswer(invocation ->
                        realClient.receiveMessage((ReceiveMessageRequest) invocation.getArgument(0)).thenApply(response -> {
                            if (!response.messages().isEmpty()) {
                                receipt.set(response.messages().getFirst().receiptHandle());
                            }
                            return response;
                        }));
                when(failingClient.deleteMessage(any(DeleteMessageRequest.class))).thenAnswer(invocation -> {
                    if (deleteAttempts.getAndIncrement() == 0) {
                        return CompletableFuture.failedFuture(new IllegalStateException("simulated delete failure"));
                    }
                    return realClient.deleteMessage((DeleteMessageRequest) invocation.getArgument(0));
                });
                consumer.client = failingClient;
                try {
                    String body = mapper.writeValueAsString(event);
                    sqs.sendMessage(builder -> builder.queueUrl(queue).messageBody(body)
                            .messageGroupId(order.toString()).messageDeduplicationId(UUID.randomUUID().toString()));
                    assertThrows(IllegalStateException.class, () -> await(consumer::receive));
                    Object[] committed = await(() -> Panache.withSession(() -> Panache.getSession().flatMap(session -> session
                            .createNativeQuery("""
                                    SELECT status, (SELECT count(*) FROM oficina_mecanica.work_order_inbox
                                        WHERE event_id = :eventId)
                                    FROM oficina_mecanica.work_order_estimate_projection WHERE work_order_id = :orderId
                                    """, Object[].class).setParameter("eventId", UUID.fromString(event.get("eventId").asText()))
                                    .setParameter("orderId", order).getSingleResult())));
                    assertEquals("CRIADO", committed[0]);
                    assertEquals(1L, ((Number) committed[1]).longValue());
                    sqs.changeMessageVisibility(builder -> builder.queueUrl(queue).receiptHandle(receipt.get()).visibilityTimeout(0));
                    await(consumer::receive);
                    sqs.sendMessage(builder -> builder.queueUrl(queue).messageBody(body)
                            .messageGroupId(order.toString()).messageDeduplicationId(UUID.randomUUID().toString()));
                    await(consumer::receive);
                    Object[] state = await(() -> Panache.withSession(() -> Panache.getSession().flatMap(session -> session
                            .createNativeQuery("""
                                    SELECT p.status, (SELECT count(*) FROM oficina_mecanica.work_order_inbox i
                                        WHERE i.event_id = :eventId)
                                    FROM oficina_mecanica.work_order_estimate_projection p WHERE p.work_order_id = :orderId
                                    """, Object[].class).setParameter("eventId", UUID.fromString(event.get("eventId").asText()))
                                    .setParameter("orderId", order).getSingleResult())));
                    assertEquals("CRIADO", state[0]);
                    assertEquals(1L, ((Number) state[1]).longValue());
                    assertTrue(sqs.receiveMessage(builder -> builder.queueUrl(queue).waitTimeSeconds(1)).messages().isEmpty());
                } finally {
                    consumer.close();
                    realClient.close();
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
