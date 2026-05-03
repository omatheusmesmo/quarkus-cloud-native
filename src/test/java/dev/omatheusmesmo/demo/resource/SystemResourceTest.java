package dev.omatheusmesmo.demo.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class SystemResourceTest {

    @Test
    void shouldReturnSystemInfo() {
        given()
            .when().get("/api/system/info")
            .then()
            .statusCode(200)
            .body("mode", anyOf(is("jvm"), is("native")))
            .body("javaVersion", notNullValue())
            .body("availableProcessors", greaterThan(0))
            .body("maxMemoryMb", greaterThan(0));
    }

    @Test
    void shouldRunBenchmarkWithDefaultIterations() {
        given()
            .when().get("/api/system/benchmark")
            .then()
            .statusCode(200)
            .body("iterations", is(1000))
            .body("throughputPerSec", greaterThan(0f))
            .body("avgDurationMs", notNullValue());
    }

    @Test
    void shouldRunBenchmarkWithCustomIterations() {
        given()
            .queryParam("iterations", 100)
            .when().get("/api/system/benchmark")
            .then()
            .statusCode(200)
            .body("iterations", is(100));
    }
}
