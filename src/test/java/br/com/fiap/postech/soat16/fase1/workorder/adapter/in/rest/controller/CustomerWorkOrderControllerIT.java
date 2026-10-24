package br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.controller;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import jakarta.inject.Inject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import br.com.fiap.postech.soat16.fase1.customer.adapter.out.persistence.CustomerRepository;
import br.com.fiap.postech.soat16.fase1.customer.domain.model.Customer;
import br.com.fiap.postech.soat16.fase1.customer.domain.model.enums.DocumentType;
import br.com.fiap.postech.soat16.fase1.part.adapter.out.persistence.PartRepository;
import br.com.fiap.postech.soat16.fase1.part.domain.model.Part;
import br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest.dto.ApiErrorResponseDto;
import br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest.pagination.PageableResponseDto;
import br.com.fiap.postech.soat16.fase1.shared.domain.exception.ErrorType;
import br.com.fiap.postech.soat16.fase1.shared.test.infrastructure.JwtKeyPairTestResource;
import br.com.fiap.postech.soat16.fase1.shared.test.infrastructure.PostgresTestResource;
import br.com.fiap.postech.soat16.fase1.vehicle.adapter.out.persistence.VehicleRepository;
import br.com.fiap.postech.soat16.fase1.vehicle.domain.model.Vehicle;
import br.com.fiap.postech.soat16.fase1.vehicle.domain.model.enums.VehicleType;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.EstimateResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.OpenedWorkOrderResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.dto.response.WorkOrderResponseDto;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.EstimateStatus;
import br.com.fiap.postech.soat16.fase1.workorder.domain.model.enums.WorkOrderStatus;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.restassured.common.mapper.TypeRef;
import io.restassured.specification.RequestSpecification;
import io.smallrye.jwt.build.Jwt;
import io.smallrye.mutiny.Uni;

/**
 * O token do cliente é assinado aqui com o mesmo formato que a função de autenticação por CPF
 * emite: {@code sub} com o id do cliente e o grupo {@code CUSTOMER}. É esse contrato que a API
 * precisa honrar, independentemente de quem assinou.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@QuarkusTestResource(JwtKeyPairTestResource.class)
@DisplayName("CustomerWorkOrderController — Integration Tests (HTTP)")
class CustomerWorkOrderControllerIT {

    private static final String MY_WORK_ORDERS_PATH = "/v1/me/work-orders";
    private static final String WORK_ORDERS_PATH = "/v1/work-orders";
    private static final AtomicInteger PLATE_COUNTER = new AtomicInteger(5_000);

    @Inject
    CustomerRepository customerRepository;

    @Inject
    VehicleRepository vehicleRepository;

    @Inject
    PartRepository partRepository;

    private static String staffToken(String role) {
        return Jwt.issuer("oficina-api")
                .upn("integration-test-staff")
                .groups(Set.of(role))
                .expiresIn(Duration.ofHours(1))
                .sign();
    }

    private static String customerToken(UUID customerId) {
        return Jwt.issuer("oficina-api")
                .subject(customerId.toString())
                .groups(Set.of("CUSTOMER"))
                .expiresIn(Duration.ofHours(1))
                .sign();
    }

    private static RequestSpecification asAdmin() {
        return given().header("Authorization", "Bearer " + staffToken("ADMIN"));
    }

    private static RequestSpecification asCustomer(UUID customerId) {
        return given().header("Authorization", "Bearer " + customerToken(customerId));
    }

    private <T> T persistInTransaction(Supplier<Uni<T>> action) {
        try {
            return VertxContextSupport.subscribeAndAwait(() -> Panache.withTransaction(action));
        } catch (Throwable e) {
            throw new IllegalStateException("Falha ao preparar dados de teste", e);
        }
    }

    private UUID seedCustomer() {
        Customer customer = new Customer();
        customer.setFirstName("Ana");
        customer.setLastName("Souza");
        customer.setEmail("ana." + UUID.randomUUID() + "@example.com");
        customer.setPhoneNumber("+5511977777777");
        customer.setDocument("DOC-" + UUID.randomUUID());
        customer.setDocumentType(DocumentType.CPF);
        return persistInTransaction(() -> customerRepository.save(customer)).getId();
    }

    private UUID seedVehicle() {
        Vehicle vehicle = new Vehicle();
        vehicle.setLicensePlate("CPF" + String.format("%04d", PLATE_COUNTER.incrementAndGet() % 10_000));
        vehicle.setManufacturer("VW");
        vehicle.setModel("Gol");
        vehicle.setColor("Prata");
        vehicle.setYear(2019);
        vehicle.setKmDriven(80_000L);
        vehicle.setType(VehicleType.CAR);
        return persistInTransaction(() -> vehicleRepository.save(vehicle)).getId();
    }

    private UUID seedPart() {
        Part part = new Part("Pastilha de freio", "Jogo dianteiro", new BigDecimal("80.00"), 100, "UN");
        return persistInTransaction(() -> partRepository.save(part)).getId();
    }

    private UUID openWorkOrder(UUID customerId) {
        String body = """
                {"customerId":"%s","vehicleId":"%s","description":"Barulho ao frear"}
                """.formatted(customerId, seedVehicle());
        return asAdmin()
                .contentType("application/json")
                .body(body)
        .when()
                .post(WORK_ORDERS_PATH)
        .then()
                .statusCode(201)
                .extract().as(OpenedWorkOrderResponseDto.class)
                .workOrder().workOrderId();
    }

    /**
     * Leva a ordem ao ponto em que o cliente decide: diagnóstico e orçamento enviado, com a peça
     * reservada.
     */
    private UUID awaitCustomerDecision(UUID workOrderId) {
        asAdmin()
                .contentType("application/json")
                .body("{\"status\":\"DIAGNOSIS\"}")
        .when()
                .patch(WORK_ORDERS_PATH + "/" + workOrderId + "/status")
        .then()
                .statusCode(200);

        return asAdmin()
                .contentType("application/json")
                .body("{\"items\":[{\"partId\":\"%s\",\"quantity\":1}]}".formatted(seedPart()))
        .when()
                .post(WORK_ORDERS_PATH + "/" + workOrderId + "/estimate")
        .then()
                .statusCode(201)
                .extract().as(EstimateResponseDto.class)
                .estimateId();
    }

    @Nested
    @DisplayName("Autenticação e autorização")
    class Authorization {

        @Test
        @DisplayName("deve retornar 401 sem token")
        void shouldReturn401WithoutToken() {
            given()
            .when()
                .get(MY_WORK_ORDERS_PATH)
            .then()
                .statusCode(401);
        }

        @Test
        @DisplayName("deve retornar 403 ao funcionário nas rotas do cliente")
        void shouldReturn403ToStaffOnCustomerRoutes() {
            asAdmin()
            .when()
                .get(MY_WORK_ORDERS_PATH)
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("deve retornar 403 ao cliente nas rotas administrativas")
        void shouldReturn403ToCustomerOnAdminRoutes() {
            asCustomer(seedCustomer())
            .when()
                .get(WORK_ORDERS_PATH)
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("deve retornar 403 ao token de cliente cujo sub não é um id")
        void shouldReturn403WhenSubjectIsNotCustomerId() {
            String token = Jwt.issuer("oficina-api")
                    .subject("52998224725")
                    .groups(Set.of("CUSTOMER"))
                    .expiresIn(Duration.ofHours(1))
                    .sign();

            given()
                .header("Authorization", "Bearer " + token)
            .when()
                .get(MY_WORK_ORDERS_PATH)
            .then()
                .statusCode(403);
        }
    }

    @Nested
    @DisplayName("Consulta das próprias ordens")
    class Queries {

        @Test
        @DisplayName("deve listar só as ordens do cliente autenticado")
        void shouldListOnlyOwnWorkOrders() {
            UUID customerId = seedCustomer();
            UUID ownWorkOrder = openWorkOrder(customerId);
            openWorkOrder(seedCustomer());

            PageableResponseDto<WorkOrderResponseDto> page = asCustomer(customerId)
            .when()
                .get(MY_WORK_ORDERS_PATH)
            .then()
                .statusCode(200)
                .extract().as(new TypeRef<>() { });

            assertEquals(1, page.content().size());
            assertEquals(ownWorkOrder, page.content().getFirst().workOrderId());
            assertEquals(customerId, page.content().getFirst().customerId());
            assertEquals(1L, page.pagination().totalElements());
        }

        @Test
        @DisplayName("deve detalhar a própria ordem")
        void shouldReturnOwnWorkOrder() {
            UUID customerId = seedCustomer();
            UUID workOrderId = openWorkOrder(customerId);

            WorkOrderResponseDto workOrder = asCustomer(customerId)
            .when()
                .get(MY_WORK_ORDERS_PATH + "/" + workOrderId)
            .then()
                .statusCode(200)
                .extract().as(WorkOrderResponseDto.class);

            assertEquals(workOrderId, workOrder.workOrderId());
            assertEquals(WorkOrderStatus.RECEIVED, workOrder.status());
        }

        @Test
        @DisplayName("deve responder 404 à ordem de outro cliente, sem revelar que ela existe")
        void shouldReturn404ForWorkOrderOfAnotherCustomer() {
            UUID workOrderId = openWorkOrder(seedCustomer());

            ApiErrorResponseDto error = asCustomer(seedCustomer())
            .when()
                .get(MY_WORK_ORDERS_PATH + "/" + workOrderId)
            .then()
                .statusCode(404)
                .extract().as(ApiErrorResponseDto.class);

            assertEquals(ErrorType.NOT_FOUND, error.type());
            assertEquals("WORK_ORDER_NOT_FOUND", error.code());
        }
    }

    @Nested
    @DisplayName("Decisão do orçamento pelo cliente autenticado")
    class EstimateDecision {

        @Test
        @DisplayName("deve aprovar o orçamento, iniciar a execução e bloquear nova decisão")
        void shouldApproveEstimateAndBlockSecondDecision() {
            UUID customerId = seedCustomer();
            UUID workOrderId = openWorkOrder(customerId);
            UUID estimateId = awaitCustomerDecision(workOrderId);
            String approvePath = MY_WORK_ORDERS_PATH + "/" + workOrderId + "/estimate/" + estimateId + "/approve";

            EstimateResponseDto approved = asCustomer(customerId)
            .when()
                .patch(approvePath)
            .then()
                .statusCode(200)
                .extract().as(EstimateResponseDto.class);

            assertEquals(EstimateStatus.APPROVED, approved.status());
            assertNotNull(approved.approvedAt());
            assertEquals(WorkOrderStatus.IN_PROGRESS, asCustomer(customerId)
                    .get(MY_WORK_ORDERS_PATH + "/" + workOrderId)
                    .as(WorkOrderResponseDto.class).status());

            ApiErrorResponseDto error = asCustomer(customerId)
            .when()
                .patch(approvePath)
            .then()
                .statusCode(409)
                .extract().as(ApiErrorResponseDto.class);

            assertEquals("ESTIMATE_ALREADY_DECIDED", error.code());
        }

        @Test
        @DisplayName("deve recusar o orçamento e concluir a ordem como cancelada")
        void shouldRejectEstimateAndCancelWorkOrder() {
            UUID customerId = seedCustomer();
            UUID workOrderId = openWorkOrder(customerId);
            UUID estimateId = awaitCustomerDecision(workOrderId);

            EstimateResponseDto rejected = asCustomer(customerId)
            .when()
                .patch(MY_WORK_ORDERS_PATH + "/" + workOrderId + "/estimate/" + estimateId + "/reject")
            .then()
                .statusCode(200)
                .extract().as(EstimateResponseDto.class);

            assertEquals(EstimateStatus.REJECTED, rejected.status());
            WorkOrderResponseDto workOrder = asCustomer(customerId)
                    .get(MY_WORK_ORDERS_PATH + "/" + workOrderId)
                    .as(WorkOrderResponseDto.class);
            assertEquals(WorkOrderStatus.COMPLETED, workOrder.status());
            assertNotNull(workOrder.cancelledAt());
        }

        @Test
        @DisplayName("deve impedir que um cliente decida o orçamento de outro")
        void shouldNotLetCustomerDecideEstimateOfAnother() {
            UUID workOrderId = openWorkOrder(seedCustomer());
            UUID estimateId = awaitCustomerDecision(workOrderId);

            ApiErrorResponseDto error = asCustomer(seedCustomer())
            .when()
                .patch(MY_WORK_ORDERS_PATH + "/" + workOrderId + "/estimate/" + estimateId + "/approve")
            .then()
                .statusCode(404)
                .extract().as(ApiErrorResponseDto.class);

            assertEquals("WORK_ORDER_NOT_FOUND", error.code());
            assertEquals(WorkOrderStatus.WAITING_APPROVAL, asAdmin()
                    .get(WORK_ORDERS_PATH + "/" + workOrderId)
                    .as(WorkOrderResponseDto.class).status());
        }
    }
}
