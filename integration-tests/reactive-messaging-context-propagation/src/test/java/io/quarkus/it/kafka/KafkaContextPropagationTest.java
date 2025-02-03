package io.quarkus.it.kafka;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kafka.KafkaCompanionResource;

@QuarkusTest
@QuarkusTestResource(KafkaCompanionResource.class)
public class KafkaContextPropagationTest {
    @Test
    void testAbsenceOfContextPropagationBlockingUni() {
        given().body("rose").post("/flowers/uni/blocking").then()
                .statusCode(500)
                .body(assertBodyRequestScopedContextWasNotActive());
    }

    protected Matcher<String> assertBodyRequestScopedContextWasNotActive() {
        return containsString("RequestScoped context was not active");
    }



}
