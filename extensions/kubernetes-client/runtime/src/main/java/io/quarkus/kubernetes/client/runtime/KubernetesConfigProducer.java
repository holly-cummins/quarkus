package io.quarkus.kubernetes.client.runtime;

import java.util.List;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.ConfigProvider;

import io.fabric8.kubernetes.client.Config;
import io.quarkus.arc.All;
import io.quarkus.arc.DefaultBean;
import io.quarkus.kubernetes.client.KubernetesConfigCustomizer;
import io.quarkus.kubernetes.client.runtime.internal.KubernetesClientBuildConfig;
import io.quarkus.kubernetes.client.runtime.internal.KubernetesClientConfig;
import io.quarkus.kubernetes.client.runtime.internal.KubernetesClientUtils;

@Singleton
public class KubernetesConfigProducer {

    @DefaultBean
    @Singleton
    @Produces
    public Config config(KubernetesClientBuildConfig buildConfig, KubernetesClientConfig config,
            @All List<KubernetesConfigCustomizer> customizers) {
        System.out.println("HOLLY producing config, using build config " + config.apiServerUrl());

        System.out.println(
                "HOLLY other way is " + ConfigProvider.getConfig().getConfigValue("quarkus.kubernetes-client.api-server-url"));
        var result = KubernetesClientUtils.createConfig(buildConfig, config);
        System.out.println("HOLLY producing config, using customizers " + customizers.size());
        for (KubernetesConfigCustomizer customizer : customizers) {
            customizer.customize(result);
        }
        return result;
    }
}
