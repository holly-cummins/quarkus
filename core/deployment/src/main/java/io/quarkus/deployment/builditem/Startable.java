package io.quarkus.deployment.builditem;

import java.io.Closeable;

public interface Startable extends Closeable {
    void start();

    String getConnectionInfo();

    // This starts to couple to containers, so we could move it to sub-interface and use that in dev services
    String getContainerId();

    /**
     * If this is true, a container will *never* be shut down.
     * It typically maps to testcontainers reuse, and relies on testcontainers ryuk to shut containers down.
     * 
     */
    default boolean isReusableBetweenProcesses() {
        return false;
    }

}
