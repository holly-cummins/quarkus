package io.quarkus.it.opentelemetry.reactive.enduser;

import org.junit.jupiter.api.Order;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(EndUserProfile.class)
@Order(7)
public class EagerAuthEndUserEnabledTest extends AbstractEndUserTest {

    @Override
    protected boolean isProactiveAuthEnabled() {
        return true;
    }

}
