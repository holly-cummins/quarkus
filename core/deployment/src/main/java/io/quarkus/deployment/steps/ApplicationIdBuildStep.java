package io.quarkus.deployment.steps;

import java.util.UUID;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ApplicationIdBuildItem;
import io.quarkus.deployment.builditem.CuratedApplicationShutdownBuildItem;

public class ApplicationIdBuildStep {

    private static boolean didClose = false;
    private static volatile UUID uuid = null;

    @BuildStep
    public ApplicationIdBuildItem create(CuratedApplicationShutdownBuildItem buildItem) {
        if (uuid == null) {
            uuid = UUID.randomUUID();
        }
        UUID finalUuid = uuid;
        buildItem.addCloseTask(() -> {
            System.out.println("HOLLY close task " + finalUuid);
            didClose = true;
            uuid = null;

        }, true);
        return new ApplicationIdBuildItem(uuid);
    }
}
