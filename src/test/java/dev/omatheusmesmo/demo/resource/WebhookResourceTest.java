package dev.omatheusmesmo.demo.resource;

import dev.omatheusmesmo.demo.dto.WebhookRequest;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class WebhookResourceTest {

    @Test
    void shouldCreateWebhook() {
        given()
            .contentType(ContentType.JSON)
            .body(new WebhookRequest("github", "push", "{\"ref\":\"refs/heads/main\"}"))
            .when().post("/api/webhooks")
            .then()
            .statusCode(201)
            .body("source", is("github"))
            .body("eventType", is("push"))
            .body("processingMode", is("blocking"))
            .body("id", notNullValue());
    }

    @Test
    void shouldReturnNotFoundForMissingWebhook() {
        given()
            .when().get("/api/webhooks/99999")
            .then()
            .statusCode(404);
    }

    @Test
    void shouldRejectInvalidWebhook() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"source\":\"\",\"eventType\":\"\"}")
            .when().post("/api/webhooks")
            .then()
            .statusCode(400);
    }

    @Test
    void shouldCreateAndDeleteWebhook() {
        int id = given()
            .contentType(ContentType.JSON)
            .body(new WebhookRequest("stripe", "payment.completed", "{\"amount\":100}"))
            .when().post("/api/webhooks")
            .then()
            .statusCode(201)
            .extract().path("id");

        given()
            .when().delete("/api/webhooks/" + id)
            .then()
            .statusCode(204);

        given()
            .when().get("/api/webhooks/" + id)
            .then()
            .statusCode(404);
    }

    @Test
    void shouldListWebhooks() {
        given()
            .when().get("/api/webhooks")
            .then()
            .statusCode(200)
            .body("size()", greaterThanOrEqualTo(0));
    }
}
