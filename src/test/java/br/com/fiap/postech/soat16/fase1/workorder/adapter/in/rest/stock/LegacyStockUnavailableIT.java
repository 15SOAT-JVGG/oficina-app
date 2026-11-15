package br.com.fiap.postech.soat16.fase1.workorder.adapter.in.rest.stock;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.fiap.postech.soat16.fase1.shared.test.infrastructure.JwtKeyPairTestResource;
import br.com.fiap.postech.soat16.fase1.shared.test.infrastructure.PostgresTestResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.build.Jwt;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@QuarkusTestResource(JwtKeyPairTestResource.class)
class LegacyStockUnavailableIT {
    @Test
    void stockRoutesReturnUnavailableWithoutWeakeningAuthorization() {
        String admin = token("ADMIN");
        String mechanic = token("MECHANIC");
        String stock = "/admin/parts/" + UUID.randomUUID() + "/stock?adjustment=5";
        given().auth().oauth2(admin).when().get("/admin/parts/low-stock").then()
                .statusCode(503).body("code", equalTo("LEGACY_FLOW_UNAVAILABLE"));
        given().auth().oauth2(admin).when().patch(stock).then()
                .statusCode(503).body("code", equalTo("LEGACY_FLOW_UNAVAILABLE"));
        given().auth().oauth2(mechanic).when().patch(stock).then().statusCode(403);
        given().when().get("/admin/parts/low-stock").then().statusCode(401);
        given().auth().oauth2(mechanic).when().get("/admin/parts/low-stock").then().statusCode(503);
    }

    @Test
    void catalogueRoutesAreAbsentFromOs() {
        String admin = token("ADMIN");
        given().auth().oauth2(admin).when().get("/admin/parts").then().statusCode(404);
        given().auth().oauth2(admin).when().get("/admin/services").then().statusCode(404);
    }

    @Test
    void openApiDocumentsUnavailableStockRoutes() throws Exception {
        String document = given().accept("application/json").when().get("/q/openapi?format=JSON")
                .then().statusCode(200).extract().asString();
        var root = new ObjectMapper().readTree(document);
        assertTrue(root.at("/paths/~1admin~1parts~1low-stock/get/responses/503").isObject());
        assertTrue(root.at("/paths/~1admin~1parts~1{id}~1stock/patch/responses/503").isObject());
    }

    private String token(String role) {
        return Jwt.issuer("oficina-api").upn("stock-test").groups(Set.of(role)).expiresIn(Duration.ofHours(1)).sign();
    }
}
