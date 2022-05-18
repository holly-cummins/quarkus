package io.quarkus.datasource.deployment.spi;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

public class DevServicesDatasourceContainerConfig {

    private final Optional<String> imageName;
    private final Map<String, String> containerProperties;
    private final Map<String, String> additionalJdbcUrlProperties;
    private final OptionalInt fixedExposedPort;

    public DevServicesDatasourceContainerConfig(Optional<String> imageName,
            Map<String, String> containerProperties,
            Map<String, String> additionalJdbcUrlProperties,
            OptionalInt port) {
        this.imageName = imageName;
        this.containerProperties = containerProperties;
        this.additionalJdbcUrlProperties = additionalJdbcUrlProperties;
        this.fixedExposedPort = port;
    }

    public Optional<String> getImageName() {
        return imageName;
    }

    public Map<String, String> getContainerProperties() {
        return containerProperties;
    }

    public Map<String, String> getAdditionalJdbcUrlProperties() {
        return additionalJdbcUrlProperties;
    }

    public OptionalInt getFixedExposedPort() {
        return fixedExposedPort;
    }
}
