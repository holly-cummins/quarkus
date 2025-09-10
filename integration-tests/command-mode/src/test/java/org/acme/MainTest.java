package org.acme;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;

@QuarkusMainTest
public class MainTest {

    @Test
    @Launch(value = { "one" }, exitCode = 10)
    public void testMain(LaunchResult result) {
        Assertions.assertTrue(result.getOutput().contains("ARGS: [one]"));
    }
}
