package br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.openapi;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import br.com.fiap.postech.soat16.fase1.shared.test.infrastructure.JwtKeyPairTestResource;
import br.com.fiap.postech.soat16.fase1.shared.test.infrastructure.PostgresTestResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;

/**
 * O contrato publicado é o que a oficina e o cliente leem antes de chamar a API, então as respostas
 * documentadas de cada operação são conferidas contra o documento realmente servido em /q/openapi.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@QuarkusTestResource(JwtKeyPairTestResource.class)
@DisplayName("OpenAPI — contrato publicado das ordens de serviço")
class WorkOrderOpenApiContractIT {

    private static final String WORK_ORDERS_PATH = "/v1/work-orders";
    private static final String METRICS_PATH = WORK_ORDERS_PATH + "/metrics/average-execution-time";
    private static final String PUBLIC_PATH = "/v1/public/work-orders";
    private static final String UNAUTHORIZED = "401";
    private static final String FORBIDDEN = "403";

    private static JsonPath openApi;

    private record Operation(String method, String path) {

        @Override
        public String toString() {
            return method.toUpperCase(Locale.ROOT) + " " + path;
        }
    }

    private static final List<Operation> ADMIN_OPERATIONS = List.of(
            new Operation("get", WORK_ORDERS_PATH),
            new Operation("post", WORK_ORDERS_PATH),
            new Operation("get", WORK_ORDERS_PATH + "/{id}"),
            new Operation("get", METRICS_PATH),
            new Operation("patch", WORK_ORDERS_PATH + "/{id}/status"),
            new Operation("post", WORK_ORDERS_PATH + "/{id}/estimate"),
            new Operation("patch", WORK_ORDERS_PATH + "/{id}/estimate/{estimateId}/approve"),
            new Operation("patch", WORK_ORDERS_PATH + "/{id}/estimate/{estimateId}/reject"),
            new Operation("post", WORK_ORDERS_PATH + "/{id}/services"),
            new Operation("patch", WORK_ORDERS_PATH + "/{id}/close"));

    // O documento é o mesmo para toda a classe, mas só pode ser buscado depois que o @QuarkusTest
    // publica a porta de teste — o que acontece adiante de um @BeforeAll.
    private static JsonPath publishedContract() {
        if (openApi == null) {
            openApi = given()
                    .queryParam("format", "json")
            .when()
                    .get("/q/openapi")
            .then()
                    .statusCode(200)
                    .extract().jsonPath();
        }
        return openApi;
    }

    @SuppressWarnings("unchecked")
    private static List<String> documentedStatuses(Operation operation) {
        Map<String, Object> documented =
                publishedContract().getMap("paths.'" + operation.path() + "'." + operation.method());
        assertNotNull(documented, operation + " não está documentada");
        return List.copyOf(((Map<String, Object>) documented.get("responses")).keySet());
    }

    private static String description(Operation operation) {
        return publishedContract().getString("paths.'" + operation.path() + "'." + operation.method() + ".description");
    }

    @Nested
    @DisplayName("Rotas administrativas")
    class AdminRoutes {

        // O 401 e o 403 saem do esquema de segurança configurado, não de anotação por operação: o
        // que este teste guarda é que toda operação da OS continua sob ele.
        @Test
        @DisplayName("devem documentar o 401 e o 403 da autenticação em todas as operações")
        void shouldDocumentTheSecurityResponses() {
            ADMIN_OPERATIONS.forEach(operation -> {
                List<String> statuses = documentedStatuses(operation);
                assertTrue(statuses.contains(UNAUTHORIZED), operation + " sem 401 documentado");
                assertTrue(statuses.contains(FORBIDDEN), operation + " sem 403 documentado");
            });
        }

        @Test
        @DisplayName("devem descrever a métrica de tempo médio como restrita ao ADMIN")
        void shouldDescribeTheAdminOnlyMetric() {
            assertTrue(description(new Operation("get", METRICS_PATH)).contains("ADMIN"));
        }

        @Test
        @DisplayName("devem documentar a indisponibilidade da criação de orçamento")
        void shouldDocumentTheInsufficientStockResponse() {
            assertTrue(documentedStatuses(new Operation("patch", WORK_ORDERS_PATH + "/{id}/status"))
                    .contains("422"));
            assertTrue(documentedStatuses(new Operation("post", WORK_ORDERS_PATH + "/{id}/estimate"))
                    .contains("503"));
        }

        // A recusa devolve ao estoque as peças reservadas na entrada em WAITING_APPROVAL; o contrato
        // já afirmou o contrário, e é essa afirmação que não pode voltar.
        @Test
        @DisplayName("não devem afirmar que a recusa do orçamento não mexe no estoque")
        void shouldNotClaimRejectionLeavesStockUntouched() {
            String reject = description(new Operation("patch",
                    WORK_ORDERS_PATH + "/{id}/estimate/{estimateId}/reject"));

            assertFalse(reject.contains("No stock is reserved on rejection"));
        }
    }

    @Nested
    @DisplayName("Canal público do cliente")
    class PublicChannel {

        @Test
        @DisplayName("deve documentar os erros do link de acompanhamento")
        void shouldDocumentTrackingLinkFailures() {
            List<String> statuses = documentedStatuses(new Operation("get", PUBLIC_PATH + "/tracking/{token}"));

            assertTrue(statuses.containsAll(List.of("200", "400", "404", "410")));
        }

        @Test
        @DisplayName("deve documentar os erros do link de decisão")
        void shouldDocumentDecisionLinkFailures() {
            List<String> statuses =
                    documentedStatuses(new Operation("post", PUBLIC_PATH + "/estimate-decisions/{token}"));

            assertTrue(statuses.contains("503"));
        }

        @Test
        @DisplayName("não deve documentar respostas de autenticação, porque não exige login")
        void shouldNotDocumentAuthenticationResponses() {
            List<String> statuses = documentedStatuses(new Operation("get", PUBLIC_PATH + "/tracking/{token}"));

            assertFalse(statuses.contains(UNAUTHORIZED));
            assertFalse(statuses.contains(FORBIDDEN));
        }
    }
}
