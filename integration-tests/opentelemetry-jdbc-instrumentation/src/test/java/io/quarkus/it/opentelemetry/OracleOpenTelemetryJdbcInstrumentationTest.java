package io.quarkus.it.opentelemetry;

import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;

@QuarkusTest
@QuarkusTestResource(value = OracleLifecycleManager.class, restrictToAnnotatedClass = true)
// TODO between Feb 4th and 17th something changed which meant postgres state is contaminating the ability to connect to Oracle if everything shares a ClassLoader
@TestProfile(OracleOpenTelemetryJdbcInstrumentationTest.SomeProfile.class)
public class OracleOpenTelemetryJdbcInstrumentationTest extends OpenTelemetryJdbcInstrumentationTest {

    @Test
    void testOracleQueryTraced() {
        testQueryTraced("oracle", "OracleHit");
    }

    public static class SomeProfile implements QuarkusTestProfile {
        public SomeProfile() {
        }
    }
}
