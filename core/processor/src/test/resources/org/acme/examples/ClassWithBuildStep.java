package org.acme.examples;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

public class ClassWithBuildStep {
    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem("EXAMPLE_FEATURE");
    }
}
