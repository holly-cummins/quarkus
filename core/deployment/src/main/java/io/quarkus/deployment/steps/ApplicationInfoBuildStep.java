package io.quarkus.deployment.steps;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ApplicationInfoBuildItem;
import io.quarkus.runtime.ApplicationConfig;

public class ApplicationInfoBuildStep {

    @BuildStep
    public ApplicationInfoBuildItem create(ApplicationConfig applicationConfig) {
        System.out.println("HOLLY app info build step" + applicationConfig.name() + " " + applicationConfig.version()
                + " and cl " + this.getClass().getClassLoader());
        return new ApplicationInfoBuildItem(applicationConfig.name(), applicationConfig.version());
    }
}
