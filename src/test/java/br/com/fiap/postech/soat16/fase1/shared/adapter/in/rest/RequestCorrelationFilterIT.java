package br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.fiap.postech.soat16.fase1.shared.test.infrastructure.JwtKeyPairTestResource;
import br.com.fiap.postech.soat16.fase1.shared.test.infrastructure.PostgresTestResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@QuarkusTestResource(JwtKeyPairTestResource.class)
@DisplayName("RequestCorrelationFilter — reactive HTTP integration")
class RequestCorrelationFilterIT {

    @Test
    @DisplayName("keeps request ids isolated across concurrent reactive requests")
    void shouldPropagateMdcAcrossConcurrentUniRequests() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Response> first = CompletableFuture.supplyAsync(
                    () -> requestWithId("request-alpha"), executor);
            CompletableFuture<Response> second = CompletableFuture.supplyAsync(
                    () -> requestWithId("request-beta"), executor);

            Response firstResponse = first.get();
            Response secondResponse = second.get();

            assertEquals(200, firstResponse.statusCode());
            assertEquals("request-alpha", firstResponse.header("X-Request-ID"));
            assertEquals("request-alpha", firstResponse.asString());
            assertEquals(200, secondResponse.statusCode());
            assertEquals("request-beta", secondResponse.header("X-Request-ID"));
            assertEquals("request-beta", secondResponse.asString());
        }
    }

    private static Response requestWithId(String requestId) {
        return given()
                .header("X-Request-ID", requestId)
                .get("/test/observability/correlation");
    }
}
