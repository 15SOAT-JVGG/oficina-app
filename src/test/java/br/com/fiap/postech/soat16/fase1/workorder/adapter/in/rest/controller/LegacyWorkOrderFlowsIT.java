package br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.controller;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import br.com.fiap.postech.soat16.fase1.shared.test.infrastructure.JwtKeyPairTestResource;
import br.com.fiap.postech.soat16.fase1.shared.test.infrastructure.PostgresTestResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@QuarkusTestResource(JwtKeyPairTestResource.class)
class LegacyWorkOrderFlowsIT {
    @Test
    void legacyFlowsReturn503WithoutAccessingTheDatabase() {
        String admin = token("ADMIN");
        String order = UUID.randomUUID().toString();
        String estimate = UUID.randomUUID().toString();
        String base = "/v1/work-orders/" + order;
        given().auth().oauth2(admin).contentType("application/json")
                .body("{\"items\":[{\"partId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}]}")
                .when().post(base + "/estimate").then().statusCode(503).body("code", equalTo("LEGACY_FLOW_UNAVAILABLE"));
        for (String action : List.of("approve", "reject")) {
            given().auth().oauth2(admin).when().patch(base + "/estimate/" + estimate + "/" + action)
                    .then().statusCode(503).body("code", equalTo("LEGACY_FLOW_UNAVAILABLE"));
        }
        given().auth().oauth2(admin).contentType("application/json").body("{\"description\":\"Reparo\",\"price\":10}")
                .when().post(base + "/services").then().statusCode(503);
        given().auth().oauth2(admin).contentType("application/json").body("{}")
                .when().patch(base + "/close").then().statusCode(503);
        for (String status : List.of("WAITING_APPROVAL", "IN_PROGRESS", "COMPLETED", "DELIVERED")) {
            given().auth().oauth2(admin).contentType("application/json").body("{\"status\":\"" + status + "\"}")
                    .when().patch(base + "/status").then().statusCode(503);
        }
        given().auth().oauth2(admin).contentType("application/json").body("{\"status\":\"DIAGNOSIS\"}")
                .when().patch(base + "/status").then().statusCode(404);
        given().when().post("/v1/public/work-orders/estimate-decisions/token").then().statusCode(503)
                .body("code", equalTo("LEGACY_FLOW_UNAVAILABLE"));
        String customer = token("CUSTOMER");
        for (String action : List.of("approve", "reject")) {
            String path = "/v1/me/work-orders/" + order + "/estimate/" + estimate + "/" + action;
            given().auth().oauth2(customer).when().patch(path).then().statusCode(503);
            given().when().patch(path).then().statusCode(401);
            given().auth().oauth2(admin).when().patch(path).then().statusCode(403);
        }
        given().when().patch(base + "/estimate/" + estimate + "/approve").then().statusCode(401);
    }

    private String token(String role) {
        return Jwt.issuer("oficina-api").upn("legacy-test").subject(UUID.randomUUID().toString())
                .groups(Set.of(role)).expiresIn(Duration.ofHours(1)).sign();
    }
}
