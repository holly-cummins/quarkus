package io.quarkus.kubernetes.client.runtime.internal;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "quarkus.kubernetes-client")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface KubernetesClientConfig {

    /**
     * URL of the Kubernetes API server
     */
    Optional<String> apiServerUrl();

    /**
     * Use api-server-url instead.
     */
    @Deprecated(forRemoval = true)
    Optional<String> masterUrl();

    /**
     * CA certificate file
     */
    Optional<String> caCertFile();

    /**
     * CA certificate data
     */
    Optional<String> caCertData();

}
