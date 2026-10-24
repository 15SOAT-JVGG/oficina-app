package br.com.fiap.postech.soat16.fase1.customer.adapter.in.rest.controller;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import br.com.fiap.postech.soat16.fase1.customer.adapter.in.rest.dto.response.CustomerResponseDto;
import br.com.fiap.postech.soat16.fase1.customer.adapter.out.persistence.CustomerRepository;
import br.com.fiap.postech.soat16.fase1.customer.domain.model.Customer;
import br.com.fiap.postech.soat16.fase1.customer.domain.model.enums.DocumentType;
import br.com.fiap.postech.soat16.fase1.shared.adapter.in.rest.dto.ApiErrorResponseDto;
import br.com.fiap.postech.soat16.fase1.shared.domain.exception.ErrorType;
import br.com.fiap.postech.soat16.fase1.shared.test.infrastructure.JwtKeyPairTestResource;
import br.com.fiap.postech.soat16.fase1.shared.test.infrastructure.PostgresTestResource;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.restassured.specification.RequestSpecification;
import io.smallrye.jwt.build.Jwt;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@QuarkusTestResource(JwtKeyPairTestResource.class)
@DisplayName("CustomerController — Integration Tests (HTTP)")
class CustomerControllerIT {

    private static final String CUSTOMERS_PATH = "/v1/customer";

    @Inject
    CustomerRepository customerRepository;

    private static RequestSpecification as(String role) {
        String token = Jwt.issuer("oficina-api")
                .upn("integration-test-" + role)
                .groups(Set.of(role))
                .expiresIn(Duration.ofHours(1))
                .sign();
        return given().header("Authorization", "Bearer " + token);
    }

    private UUID seedCustomer() {
        Customer customer = new Customer();
        customer.setFirstName("Ana");
        customer.setLastName("Souza");
        customer.setEmail("ana." + UUID.randomUUID() + "@example.com");
        customer.setPhoneNumber("+5511977777777");
        customer.setDocument("DOC-" + UUID.randomUUID());
        customer.setDocumentType(DocumentType.CPF);
        try {
            return VertxContextSupport.subscribeAndAwait(
                    () -> Panache.withTransaction(() -> customerRepository.save(customer))).getId();
        } catch (Throwable e) {
            throw new IllegalStateException("Falha ao preparar dados de teste", e);
        }
    }

    @Nested
    @DisplayName("PATCH /v1/customer/{id}/activate e /deactivate")
    class Status {

        @Test
        @DisplayName("deve cadastrar o cliente ativo, desativá-lo e reativá-lo como ADMIN")
        void shouldDeactivateAndActivateAsAdmin() {
            UUID customerId = seedCustomer();

            CustomerResponseDto registered = as("ADMIN")
            .when()
                .get(CUSTOMERS_PATH + "/" + customerId)
            .then()
                .statusCode(200)
                .extract().as(CustomerResponseDto.class);
            assertTrue(registered.active());

            CustomerResponseDto deactivated = as("ADMIN")
            .when()
                .patch(CUSTOMERS_PATH + "/" + customerId + "/deactivate")
            .then()
                .statusCode(200)
                .extract().as(CustomerResponseDto.class);
            assertFalse(deactivated.active());

            CustomerResponseDto activated = as("ADMIN")
            .when()
                .patch(CUSTOMERS_PATH + "/" + customerId + "/activate")
            .then()
                .statusCode(200)
                .extract().as(CustomerResponseDto.class);
            assertTrue(activated.active());
        }

        @Test
        @DisplayName("deve retornar 401 sem token")
        void shouldReturn401WithoutToken() {
            given()
            .when()
                .patch(CUSTOMERS_PATH + "/" + UUID.randomUUID() + "/deactivate")
            .then()
                .statusCode(401);
        }

        @Test
        @DisplayName("deve retornar 403 ao MECHANIC")
        void shouldReturn403ToMechanic() {
            as("MECHANIC")
            .when()
                .patch(CUSTOMERS_PATH + "/" + seedCustomer() + "/deactivate")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("deve retornar 404 para cliente inexistente")
        void shouldReturn404ForUnknownCustomer() {
            ApiErrorResponseDto error = as("ADMIN")
            .when()
                .patch(CUSTOMERS_PATH + "/" + UUID.randomUUID() + "/deactivate")
            .then()
                .statusCode(404)
                .extract().as(ApiErrorResponseDto.class);

            assertEquals(ErrorType.NOT_FOUND, error.type());
            assertEquals("CUSTOMER_NOT_FOUND", error.code());
        }
    }
}
