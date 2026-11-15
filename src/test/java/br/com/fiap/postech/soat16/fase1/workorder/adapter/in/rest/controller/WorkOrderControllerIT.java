package br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.controller;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import br.com.fiap.postech.soat16.fase1.customer.adapter.out.persistence.CustomerRepository;
import br.com.fiap.postech.soat16.fase1.customer.domain.model.Customer;
import br.com.fiap.postech.soat16.fase1.customer.domain.model.enums.DocumentType;
import br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest.dto.ApiErrorResponseDto;
import br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest.pagination.PageableResponseDto;
import br.com.fiap.postech.soat16.fase1.shared.test.infrastructure.JwtKeyPairTestResource;
import br.com.fiap.postech.soat16.fase1.shared.test.infrastructure.PostgresTestResource;
import br.com.fiap.postech.soat16.fase1.vehicle.adapter.out.persistence.VehicleRepository;
import br.com.fiap.postech.soat16.fase1.vehicle.domain.model.Vehicle;
import br.com.fiap.postech.soat16.fase1.vehicle.domain.model.enums.VehicleType;
import br.com.fiap.postech.soat16.fase1.worker.adapter.out.persistence.WorkerRepository;
import br.com.fiap.postech.soat16.fase1.worker.domain.model.Worker;
import br.com.fiap.postech.soat16.fase1.worker.domain.model.enums.WorkerProfile;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.OpenedWorkOrderResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.WorkOrderMetricsResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.WorkOrderResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.WorkOrderTrackingResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.out.integration.EstimateResultJournal;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.out.persistence.WorkOrderRepository;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.out.security.JwtWorkOrderTrackingTokenAdapter;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.WorkOrderTrackingToken;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.WorkOrderStatus;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.integration.EstimateProjection;

import io.agroal.api.AgroalDataSource;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.restassured.RestAssured;
import io.restassured.common.mapper.TypeRef;
import io.restassured.response.Response;
import io.smallrye.jwt.build.Jwt;
import io.smallrye.mutiny.Uni;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@QuarkusTestResource(JwtKeyPairTestResource.class)
@DisplayName("WorkOrderController — Integration Tests (HTTP)")
class WorkOrderControllerIT {

    private static final String WORK_ORDERS_PATH = "/v1/work-orders";
    private static final String DECISION_LINK_MARKER = "/v1/public/work-orders/estimate-decisions/";
    private static final String TRACKING_LINK_MARKER = "/v1/public/work-orders/tracking/";
    private static final AtomicInteger PLATE_COUNTER = new AtomicInteger();

    @Inject
    MockMailbox mailbox;

    @Inject
    AgroalDataSource dataSource;

    @Inject
    CustomerRepository customerRepository;

    @Inject
    VehicleRepository vehicleRepository;

    @Inject
    WorkerRepository workerRepository;

    @Inject
    WorkOrderRepository workOrderRepository;

    @Inject
    EstimateResultJournal resultJournal;

    // Esperar trinta dias não é uma opção, então o link vencido é assinado pelo próprio adaptador.
    @Inject
    JwtWorkOrderTrackingTokenAdapter trackingTokenSignature;

    @BeforeAll
    static void authenticateAllRequests() {
        String token = Jwt.issuer("oficina-api")
                .upn("integration-test")
                .groups(Set.of("ADMIN", "MECHANIC"))
                .expiresIn(Duration.ofHours(1))
                .sign();
        RestAssured.filters((requestSpec, responseSpec, ctx) -> {
            requestSpec.header("Authorization", "Bearer " + token);
            return ctx.next(requestSpec, responseSpec);
        });
    }

    @AfterAll
    static void clearAuthentication() {
        RestAssured.replaceFiltersWith(List.of());
    }

    // Persiste fora do event loop, estabelecendo o contexto Vert.x exigido pelo Hibernate
    // Reactive, como no DataSeeder e no AuthControllerIT.
    private <T> T persistInTransaction(Supplier<Uni<T>> action) {
        try {
            return VertxContextSupport.subscribeAndAwait(() -> Panache.withTransaction(action));
        } catch (Throwable e) {
            throw new IllegalStateException("Falha ao preparar dados de teste", e);
        }
    }

    private static String uniqueLicensePlate() {
        int n = PLATE_COUNTER.incrementAndGet() % 10_000;
        return "ABC" + String.format("%04d", n);
    }

    private UUID seedCustomer() {
        return seedCustomerWithEmail().id();
    }

    private SeededCustomer seedCustomerWithEmail() {
        String email = "maria." + UUID.randomUUID() + "@example.com";
        Customer customer = new Customer();
        customer.setFirstName("Maria");
        customer.setLastName("Silva");
        customer.setEmail(email);
        customer.setPhoneNumber("+5511999999999");
        customer.setDocument("DOC-" + UUID.randomUUID());
        customer.setDocumentType(DocumentType.CPF);
        return new SeededCustomer(persistInTransaction(() -> customerRepository.save(customer)).getId(), email);
    }

    // O mailer roda em modo simulado nos testes, então a caixa de entrada é a única forma de
    // observar exatamente o que o cliente recebeu — inclusive os links que ele vai clicar.

    private List<String> trackingLinks(String customerEmail) {
        return emailedLinks(customerEmail, TRACKING_LINK_MARKER);
    }

    private List<String> emailedLinks(String customerEmail, String marker) {
        return mailbox.getMailMessagesSentTo(customerEmail).stream()
                .flatMap(mail -> mail.getText().lines())
                .map(String::trim)
                .filter(line -> line.contains(marker))
                .map(line -> line.substring(line.indexOf("http")))
                .toList();
    }

    private UUID seedVehicle() {
        Vehicle vehicle = new Vehicle();
        vehicle.setLicensePlate(uniqueLicensePlate());
        vehicle.setManufacturer("Fiat");
        vehicle.setModel("Uno");
        vehicle.setColor("Branco");
        vehicle.setYear(2020);
        vehicle.setKmDriven(50_000L);
        vehicle.setType(VehicleType.CAR);
        return persistInTransaction(() -> vehicleRepository.save(vehicle)).getId();
    }

    private UUID seedWorker() {
        Worker worker = Worker.create(WorkerProfile.MECHANIC, "João", "Mecânico",
                "joao." + UUID.randomUUID() + "@example.com", "+5511988888888", "hash");
        return persistInTransaction(() -> workerRepository.save(worker)).getId();
    }



    private OpenedWorkOrderResponseDto openWorkOrder(String body) {
        return given()
                .contentType("application/json")
                .body(body)
        .when()
                .post(WORK_ORDERS_PATH)
        .then()
                .statusCode(201)
                .extract().as(OpenedWorkOrderResponseDto.class);
    }

    private UUID createWorkOrder(UUID customerId, UUID vehicleId) {
        String body = """
                {"customerId":"%s","vehicleId":"%s","description":"Revisão geral"}
                """.formatted(customerId, vehicleId);

        return openWorkOrder(body).workOrder().workOrderId();
    }

    private PageableResponseDto<WorkOrderResponseDto> operationalQueuePage(int page, int size) {
        return given()
                .queryParam("page", page)
                .queryParam("size", size)
        .when()
                .get(WORK_ORDERS_PATH)
        .then()
                .statusCode(200)
                .extract().as(new TypeRef<>() {
                });
    }

    // A base é compartilhada por toda a classe, então a fila é percorrida por inteiro antes de
    // afirmar sobre a posição relativa das ordens criadas pelo teste.
    private List<WorkOrderResponseDto> operationalQueue() {
        List<WorkOrderResponseDto> queue = new ArrayList<>();
        PageableResponseDto<WorkOrderResponseDto> firstPage = operationalQueuePage(0, 100);
        for (int index = 0; index < firstPage.pagination().totalPages(); index++) {
            PageableResponseDto<WorkOrderResponseDto> current =
                    index == 0 ? firstPage : operationalQueuePage(index, 100);
            queue.addAll(current.content());
        }
        return queue;
    }

    private List<UUID> operationalQueueIds() {
        return operationalQueue().stream().map(WorkOrderResponseDto::workOrderId).toList();
    }

    private boolean workOrderExistsForVehicle(UUID vehicleId) {
        return operationalQueue().stream().anyMatch(workOrder -> vehicleId.equals(workOrder.vehicleId()));
    }

    private WorkOrderResponseDto getWorkOrder(UUID workOrderId) {
        return given()
        .when()
                .get(WORK_ORDERS_PATH + "/" + workOrderId)
        .then()
                .statusCode(200)
                .extract().as(WorkOrderResponseDto.class);
    }

    private void updateStatus(UUID workOrderId, WorkOrderStatus status) {
        given()
                .contentType("application/json")
                .body("{\"status\":\"" + status + "\"}")
        .when()
                .patch(WORK_ORDERS_PATH + "/" + workOrderId + "/status")
        .then()
                .statusCode(200);
    }





    // Lê o estoque atual da peça direto do repositório (estabelecendo o contexto Vert.x exigido
    // pelo Hibernate Reactive), já que não há endpoint de leitura de peça neste teste.


    // A API não permite escolher a data de abertura, então a fila só pode ser exercitada por data
    // retroagindo a OS direto na base.
    private void backdateOpening(UUID workOrderId, Duration age) {
        persistInTransaction(() -> workOrderRepository.find("id = ?1", workOrderId).firstResult()
                .invoke(entity -> entity.setOpenedAt(entity.getOpenedAt().minus(age))));
    }



    private ApiErrorResponseDto extractError(Response response) {
        return response.as(ApiErrorResponseDto.class);
    }

    @Nested
    @DisplayName("Fluxo completo da ordem de serviço")
    class FullLifecycle {



        @Test
        @DisplayName("deve rejeitar o status CANCELLED removido do contrato")
        void shouldRejectRemovedCancelledStatus() {
            UUID workOrderId = createWorkOrder(seedCustomer(), seedVehicle());

            given()
                    .contentType("application/json")
                    .body("{\"status\":\"CANCELLED\"}")
            .when()
                    .patch(WORK_ORDERS_PATH + "/" + workOrderId + "/status")
            .then()
                    .statusCode(400);
        }


    }

    @Nested
    @DisplayName("POST /v1/work-orders — create")
    class Create {

        @Test
        @DisplayName("deve criar a ordem com status RECEIVED")
        void shouldCreateWorkOrder() {
            UUID workOrderId = createWorkOrder(seedCustomer(), seedVehicle());
            assertEquals(WorkOrderStatus.RECEIVED, getWorkOrder(workOrderId).status());
        }

        @Test
        @DisplayName("deve devolver a identificação da ordem recém-aberta no corpo e no Location")
        void shouldReturnCreatedWorkOrderIdentification() {
            UUID vehicleId = seedVehicle();
            String body = """
                    {"customerId":"%s","vehicleId":"%s","description":"Revisão geral"}
                    """.formatted(seedCustomer(), vehicleId);

            Response response = given()
                    .contentType("application/json")
                    .body(body)
            .when()
                    .post(WORK_ORDERS_PATH)
            .then()
                    .statusCode(201)
                    .extract().response();

            OpenedWorkOrderResponseDto opened = response.as(OpenedWorkOrderResponseDto.class);
            UUID workOrderId = opened.workOrder().workOrderId();

            assertNotNull(workOrderId);
            assertNull(opened.estimate());
            assertEquals(vehicleId, opened.workOrder().vehicleId());
            assertTrue(response.header("Location").endsWith(WORK_ORDERS_PATH + "/" + workOrderId));
            assertEquals(workOrderId, getWorkOrder(workOrderId).workOrderId());
        }

        @Test
        @DisplayName("abre a OS com referências ainda não validadas e expõe o orçamento aguardando")
        void opensWithInitialRequestWithoutCatalogValidation() {
            UUID partId = UUID.randomUUID();
            UUID serviceId = UUID.randomUUID();
            String body = """
                    {"customerId":"%s","vehicleId":"%s","description":"Revisão",
                     "services":[{"serviceItemId":"%s"}],"parts":[{"partId":"%s","quantity":3}]}
                    """.formatted(seedCustomer(), seedVehicle(), serviceId, partId);
            OpenedWorkOrderResponseDto opened = openWorkOrder(body);
            assertNull(opened.estimate());
            assertEquals(EstimateProjection.Status.AGUARDANDO, opened.workOrder().estimateProgress());
            assertEquals(EstimateProjection.Status.AGUARDANDO,
                    getWorkOrder(opened.workOrder().workOrderId()).estimateProgress());
            Object[] journal = persistInTransaction(() -> Panache.getSession().flatMap(session -> session
                    .createNativeQuery("""
                            SELECT CAST(o.payload AS text), p.status FROM oficina_mecanica.work_order_outbox o
                            JOIN oficina_mecanica.work_order_estimate_projection p ON p.opening_event_id = o.event_id
                            WHERE o.work_order_id = :id
                            """, Object[].class).setParameter("id", opened.workOrder().workOrderId()).getSingleResult()));
            assertTrue(((String) journal[0]).contains(partId.toString()));
            assertTrue(((String) journal[0]).contains(serviceId.toString()));
            assertEquals("AGUARDANDO", journal[1]);
        }

        @Test
        @DisplayName("reverte a abertura quando o registro do evento falha")
        void rollsBackOpeningWhenJournalFails() throws Exception {
            UUID vehicleId = seedVehicle();
            String body = """
                    {"customerId":"%s","vehicleId":"%s","description":"Revisão"}
                    """.formatted(seedCustomer(), vehicleId);
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("""
                        CREATE FUNCTION oficina_mecanica.test_reject_outbox() RETURNS trigger
                        LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test outbox failure'; END $$
                        """);
                statement.execute("""
                        CREATE TRIGGER test_reject_outbox BEFORE INSERT ON oficina_mecanica.work_order_outbox
                        FOR EACH ROW EXECUTE FUNCTION oficina_mecanica.test_reject_outbox()
                        """);
                try {
                    given().contentType("application/json").body(body)
                            .when().post(WORK_ORDERS_PATH).then().statusCode(500);
                    assertFalse(workOrderExistsForVehicle(vehicleId));
                } finally {
                    statement.execute("DROP TRIGGER test_reject_outbox ON oficina_mecanica.work_order_outbox");
                    statement.execute("DROP FUNCTION oficina_mecanica.test_reject_outbox()");
                }
            }
        }

        @Test
        @DisplayName("expõe a falha recebida para uma solicitação inicial")
        void exposesEstimateFailure() throws Throwable {
            String body = """
                    {"customerId":"%s","vehicleId":"%s","description":"Revisão",
                     "parts":[{"partId":"%s","quantity":1}]}
                    """.formatted(seedCustomer(), seedVehicle(), UUID.randomUUID());
            UUID order = openWorkOrder(body).workOrder().workOrderId();
            Object[] ids = persistInTransaction(() -> Panache.getSession().flatMap(session -> session
                    .createNativeQuery("""
                            SELECT opening_event_id, saga_id, correlation_id
                            FROM oficina_mecanica.work_order_estimate_projection WHERE work_order_id = :id
                            """, Object[].class).setParameter("id", order).getSingleResult()));
            var event = new EstimateResultJournal.Event(UUID.randomUUID(), order, (UUID) ids[0],
                    (UUID) ids[1], (UUID) ids[2], null, null, "PART_NOT_FOUND", "Peça não encontrada");
            VertxContextSupport.subscribeAndAwait(() -> resultJournal.record(event));
            WorkOrderResponseDto result = getWorkOrder(order);
            assertEquals(EstimateProjection.Status.FALHOU, result.estimateProgress());
            assertEquals("Peça não encontrada", result.estimateFailureReason());
        }

        @Test
        @DisplayName("deve retornar 400 quando a quantidade de uma peça solicitada é menor que 1")
        void shouldReturn400WhenRequestedQuantityIsBelowOne() {
            UUID vehicleId = seedVehicle();
            String body = """
                    {"customerId":"%s","vehicleId":"%s","description":"Revisão",
                     "parts":[{"partId":"%s","quantity":0}]}
                    """.formatted(seedCustomer(), vehicleId, UUID.randomUUID());

            given()
                    .contentType("application/json")
                    .body(body)
            .when()
                    .post(WORK_ORDERS_PATH)
            .then()
                    .statusCode(400);

            assertFalse(workOrderExistsForVehicle(vehicleId));
        }

        @Test
        @DisplayName("deve retornar 404 quando o cliente não existe")
        void shouldReturn404WhenCustomerNotFound() {
            String body = """
                    {"customerId":"%s","vehicleId":"%s","description":"Revisão"}
                    """.formatted(UUID.randomUUID(), seedVehicle());

            ApiErrorResponseDto error = extractError(given()
                    .contentType("application/json")
                    .body(body)
            .when()
                    .post(WORK_ORDERS_PATH)
            .then()
                    .statusCode(404)
                    .extract().response());
            assertEquals("CUSTOMER_NOT_FOUND", error.code());
        }

        @Test
        @DisplayName("deve retornar 404 quando o veículo não existe")
        void shouldReturn404WhenVehicleNotFound() {
            String body = """
                    {"customerId":"%s","vehicleId":"%s","description":"Revisão"}
                    """.formatted(seedCustomer(), UUID.randomUUID());

            ApiErrorResponseDto error = extractError(given()
                    .contentType("application/json")
                    .body(body)
            .when()
                    .post(WORK_ORDERS_PATH)
            .then()
                    .statusCode(404)
                    .extract().response());
            assertEquals("VEHICLE_NOT_FOUND", error.code());
        }

        @Test
        @DisplayName("deve registrar o mecânico responsável informado na abertura")
        void shouldAssignTheRequestedWorker() {
            UUID workerId = seedWorker();
            String body = """
                    {"customerId":"%s","vehicleId":"%s","description":"Revisão",
                     "assignedWorkerId":"%s"}
                    """.formatted(seedCustomer(), seedVehicle(), workerId);

            OpenedWorkOrderResponseDto opened = openWorkOrder(body);

            assertEquals(workerId, opened.workOrder().assignedWorkerId());
            assertEquals(workerId, getWorkOrder(opened.workOrder().workOrderId()).assignedWorkerId());
        }

        @Test
        @DisplayName("deve retornar 404 e não abrir a ordem quando o mecânico responsável não existe")
        void shouldReturn404WhenAssignedWorkerNotFound() {
            UUID vehicleId = seedVehicle();
            String body = """
                    {"customerId":"%s","vehicleId":"%s","description":"Revisão",
                     "assignedWorkerId":"%s"}
                    """.formatted(seedCustomer(), vehicleId, UUID.randomUUID());

            ApiErrorResponseDto error = extractError(given()
                    .contentType("application/json")
                    .body(body)
            .when()
                    .post(WORK_ORDERS_PATH)
            .then()
                    .statusCode(404)
                    .extract().response());

            assertEquals("WORKER_NOT_FOUND", error.code());
            assertFalse(workOrderExistsForVehicle(vehicleId));
        }

        @Test
        @DisplayName("deve retornar 400 quando a descrição está em branco")
        void shouldReturn400WhenDescriptionBlank() {
            String body = """
                    {"customerId":"%s","vehicleId":"%s","description":""}
                    """.formatted(seedCustomer(), seedVehicle());

            given()
                    .contentType("application/json")
                    .body(body)
            .when()
                    .post(WORK_ORDERS_PATH)
            .then()
                    .statusCode(400);
        }
    }

    @Nested
    @DisplayName("PATCH /v1/work-orders/{id}/status — updateStatus")
    class UpdateStatus {

        @Test
        @DisplayName("deve retornar 404 quando a ordem não existe")
        void shouldReturn404WhenWorkOrderNotFound() {
            ApiErrorResponseDto error = extractError(given()
                    .contentType("application/json")
                    .body("{\"status\":\"DIAGNOSIS\"}")
            .when()
                    .patch(WORK_ORDERS_PATH + "/" + UUID.randomUUID() + "/status")
            .then()
                    .statusCode(404)
                    .extract().response());
            assertEquals("WORK_ORDER_NOT_FOUND", error.code());
        }

        @Test
        @DisplayName("deve rejeitar o status APPROVED removido do contrato")
        void shouldRejectRemovedApprovedStatus() {
            UUID workOrderId = createWorkOrder(seedCustomer(), seedVehicle());

            given()
                    .contentType("application/json")
                    .body("{\"status\":\"APPROVED\"}")
            .when()
                    .patch(WORK_ORDERS_PATH + "/" + workOrderId + "/status")
            .then()
                    .statusCode(400)
                    .extract().response();
        }






    }

    @Nested
    @DisplayName("POST /v1/work-orders/{id}/estimate — createEstimate")
    class CreateEstimate {





        @Test
        @DisplayName("deve retornar 400 quando a lista de itens está vazia")
        void shouldReturn400WhenItemsEmpty() {
            UUID workOrderId = createWorkOrder(seedCustomer(), seedVehicle());

            given()
                    .contentType("application/json")
                    .body("{\"items\":[]}")
            .when()
                    .post(WORK_ORDERS_PATH + "/" + workOrderId + "/estimate")
            .then()
                    .statusCode(400);
        }

    }

    @Nested
    @DisplayName("PATCH /v1/work-orders/{id}/estimate/{estimateId}/approve — approveEstimate")
    class ApproveEstimate {






    }

    @Nested
    @DisplayName("POST /v1/work-orders/{id}/services — addService")
    class AddService {



        @Test
        @DisplayName("deve retornar 400 quando o preço é zero")
        void shouldReturn400WhenPriceIsZero() {
            UUID workOrderId = createWorkOrder(seedCustomer(), seedVehicle());

            given()
                    .contentType("application/json")
                    .body("{\"description\":\"Alinhamento\",\"price\":0}")
            .when()
                    .post(WORK_ORDERS_PATH + "/" + workOrderId + "/services")
            .then()
                    .statusCode(400);
        }

    }

    @Nested
    @DisplayName("PATCH /v1/work-orders/{id}/close — close")
    class Close {







        @Test
        @DisplayName("deve retornar 400 quando finalValue é zero")
        void shouldReturn400WhenFinalValueIsZero() {
            UUID workOrderId = createWorkOrder(seedCustomer(), seedVehicle());

            given()
                    .contentType("application/json")
                    .body("{\"finalValue\":0}")
            .when()
                    .patch(WORK_ORDERS_PATH + "/" + workOrderId + "/close")
            .then()
                    .statusCode(400);
        }
    }

    @Nested
    @DisplayName("Orçamento (peças + serviços), rejeição e estoque")
    class EstimateDecisionAndStock {
















    }

    @Nested
    @DisplayName("Canal público do cliente — /v1/public/work-orders")
    class PublicChannel {

        private static final String PUBLIC_PATH = "/v1/public/work-orders";
        private static final String DECISION_PATH = PUBLIC_PATH + "/estimate-decisions/";
        private static final String TRACKING_PATH = PUBLIC_PATH + "/tracking/";

        @Test
        @DisplayName("cliente deve acompanhar a OS pelo link recebido, sem autenticação")
        void shouldTrackThroughEmailedLink() {
            SeededCustomer customer = seedCustomerWithEmail();
            UUID workOrderId = createWorkOrder(customer.id(), seedVehicle());
            updateStatus(workOrderId, WorkOrderStatus.DIAGNOSIS);

            WorkOrderTrackingResponseDto tracked = track(latestTrackingToken(customer.email()), 200)
                    .as(WorkOrderTrackingResponseDto.class);

            assertEquals(workOrderId, tracked.workOrderId());
            assertEquals(WorkOrderStatus.DIAGNOSIS, tracked.status());
            assertNotNull(tracked.openedAt());
        }

        @Test
        @DisplayName("o acompanhamento deve expor apenas o andamento do atendimento")
        void shouldExposeOnlyTheProgressFields() {
            SeededCustomer customer = seedCustomerWithEmail();
            createWorkOrder(customer.id(), seedVehicle());

            Map<String, Object> payload = track(latestTrackingToken(customer.email()), 200)
                    .as(new TypeRef<>() {
                    });

            assertEquals(Set.of("workOrderId", "status", "openedAt"), payload.keySet());
        }





        @Test
        @DisplayName("não deve existir acompanhamento por id, sem o link enviado ao cliente")
        void shouldNotRevealTheWorkOrderWithoutToken() {
            UUID workOrderId = createWorkOrder(seedCustomer(), seedVehicle());

            String body = given()
            .when()
                    .get(PUBLIC_PATH + "/" + workOrderId)
            .then()
                    .statusCode(404)
                    .extract().asString();

            assertFalse(body.contains(workOrderId.toString()));
        }

        @Test
        @DisplayName("deve retornar 400 para um link de acompanhamento que não foi emitido pela oficina")
        void shouldReturn400ForForgedTrackingLink() {
            ApiErrorResponseDto error = extractError(track("nao-e-um-link-valido", 400));

            assertEquals("TRACKING_TOKEN_INVALID", error.code());
        }

        @Test
        @DisplayName("deve retornar 410 para um link de acompanhamento apresentado depois dos trinta dias")
        void shouldReturn410WhenTrackingLinkHasExpired() {
            UUID workOrderId = createWorkOrder(seedCustomer(), seedVehicle());
            String expiredToken = trackingTokenSignature.sign(WorkOrderTrackingToken.issue(
                    workOrderId, LocalDateTime.now(ZoneId.systemDefault()).minusDays(31)));

            ApiErrorResponseDto error = extractError(track(expiredToken, 410));

            assertEquals("TRACKING_TOKEN_EXPIRED", error.code());
        }

















        private Response track(String token, int expectedStatus) {
            return given()
            .when()
                    .get(TRACKING_PATH + token)
            .then()
                    .statusCode(expectedStatus)
                    .extract().response();
        }

        // O cliente clica no link do e-mail mais recente, que é o que reflete o estágio atual.
        private String latestTrackingToken(String customerEmail) {
            List<String> links = trackingLinks(customerEmail);
            return tokenOf(links.get(links.size() - 1));
        }


        // Leva uma OS recém-aberta até WAITING_APPROVAL e recolhe do e-mail os dois links que a
        // oficina enviou ao cliente, que é como o cliente real chega ao endpoint de decisão.

        private String tokenOf(String link) {
            return link.substring(link.lastIndexOf('/') + 1);
        }
    }

    // Esperar sete dias não é uma opção, então o vencimento dos links é antecipado na base.

    private record SeededCustomer(UUID id, String email) {
    }

    @Nested
    @DisplayName("GET /v1/work-orders — fila operacional")
    class OperationalQueue {



        @Test
        @DisplayName("agrupa as ordens por estágio e exclui as concluídas")
        void groupsActiveStatusesAndExcludesCompleted() {
            UUID received = createWorkOrder(seedCustomer(), seedVehicle());
            UUID diagnosis = createWorkOrder(seedCustomer(), seedVehicle());
            updateStatus(diagnosis, WorkOrderStatus.DIAGNOSIS);
            UUID waiting = createWorkOrder(seedCustomer(), seedVehicle());
            UUID inProgress = createWorkOrder(seedCustomer(), seedVehicle());
            UUID completed = createWorkOrder(seedCustomer(), seedVehicle());
            seedPersistedStatus(waiting, WorkOrderStatus.WAITING_APPROVAL, false);
            seedPersistedStatus(inProgress, WorkOrderStatus.IN_PROGRESS, false);
            seedPersistedStatus(completed, WorkOrderStatus.COMPLETED, true);
            List<UUID> queue = operationalQueueIds();
            assertTrue(queue.indexOf(inProgress) < queue.indexOf(waiting));
            assertTrue(queue.indexOf(waiting) < queue.indexOf(diagnosis));
            assertTrue(queue.indexOf(diagnosis) < queue.indexOf(received));
            assertFalse(queue.contains(completed));
        }

        // A OS aberta por último é retroagida para que a ordem de criação e a ordem esperada fiquem
        // invertidas — sem isso o teste passaria mesmo se a fila devolvesse na ordem de inserção.
        @Test
        @DisplayName("deve colocar a OS mais antiga à frente dentro do mesmo status")
        void shouldPlaceTheOldestFirstWithinTheSameStatus() {
            UUID createdFirst = createWorkOrder(seedCustomer(), seedVehicle());
            UUID oldest = createWorkOrder(seedCustomer(), seedVehicle());
            backdateOpening(oldest, Duration.ofDays(1));

            List<UUID> queue = operationalQueueIds();

            assertTrue(queue.containsAll(List.of(createdFirst, oldest)));
            assertTrue(queue.indexOf(oldest) < queue.indexOf(createdFirst));
        }





        @Test
        @DisplayName("deve paginar exatamente o conjunto filtrado")
        void shouldPaginateTheFilteredSet() {
            createWorkOrder(seedCustomer(), seedVehicle());
            createWorkOrder(seedCustomer(), seedVehicle());

            PageableResponseDto<WorkOrderResponseDto> firstPage = operationalQueuePage(0, 1);
            PageableResponseDto<WorkOrderResponseDto> secondPage = operationalQueuePage(1, 1);

            assertEquals(1, firstPage.content().size());
            assertEquals(operationalQueueIds().size(), firstPage.pagination().totalElements());
            assertEquals(firstPage.pagination().totalElements(), secondPage.pagination().totalElements());
            assertTrue(firstPage.pagination().hasNext());
            assertTrue(secondPage.pagination().hasPrevious());
            assertNotEquals(firstPage.content().get(0).workOrderId(), secondPage.content().get(0).workOrderId());
        }

        @Test
        @DisplayName("deve devolver uma página vazia além da última")
        void shouldReturnAnEmptyPageBeyondTheLastOne() {
            createWorkOrder(seedCustomer(), seedVehicle());

            PageableResponseDto<WorkOrderResponseDto> page =
                    operationalQueuePage(operationalQueuePage(0, 100).pagination().totalPages(), 100);

            assertTrue(page.content().isEmpty());
            assertFalse(page.pagination().hasNext());
        }


    }

    @Nested
    @DisplayName("GET /v1/work-orders/metrics/average-execution-time — tempo médio de execução")
    class Metrics {
        @Test
        @DisplayName("calcula a métrica das ordens concluídas persistidas")
        void returnsAverageForPersistedCompletedOrders() {
            UUID order = createWorkOrder(seedCustomer(), seedVehicle());
            seedPersistedStatus(order, WorkOrderStatus.COMPLETED, true);
            WorkOrderMetricsResponseDto metrics = given().when()
                    .get(WORK_ORDERS_PATH + "/metrics/average-execution-time")
                    .then().statusCode(200).extract().as(WorkOrderMetricsResponseDto.class);
            assertTrue(metrics.completedWorkOrders() >= 1);
            assertNotNull(metrics.averageExecutionMinutes());
        }
    }

    private void seedPersistedStatus(UUID order, WorkOrderStatus status, boolean closed) {
        persistInTransaction(() -> Panache.getSession().flatMap(session -> session.createNativeQuery("""
                UPDATE oficina_mecanica.work_order SET status = :status,
                    closed_at = CASE WHEN :closed THEN opened_at + interval '1 hour' ELSE NULL END
                WHERE work_order_id = :id
                """).setParameter("status", status.name()).setParameter("closed", closed)
                .setParameter("id", order).executeUpdate()));
    }
}
