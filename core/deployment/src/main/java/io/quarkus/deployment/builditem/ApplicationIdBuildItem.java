package io.quarkus.deployment.builditem;

import java.util.UUID;

import io.quarkus.builder.item.SimpleBuildItem;

public final class ApplicationIdBuildItem extends SimpleBuildItem {

    final String UUID;

    public ApplicationIdBuildItem(UUID uuid) {
        this.UUID = uuid.toString();
    }

    public String getUUID() {
        return UUID;
    }
}
