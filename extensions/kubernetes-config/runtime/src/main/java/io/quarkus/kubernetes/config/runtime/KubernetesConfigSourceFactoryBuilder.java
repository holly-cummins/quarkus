package io.quarkus.kubernetes.config.runtime;

import java.util.Collections;

import org.eclipse.microprofile.config.spi.ConfigSource;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.kubernetes.client.runtime.internal.KubernetesClientConfig;
import io.quarkus.kubernetes.client.runtime.internal.KubernetesClientUtils;
import io.quarkus.runtime.ApplicationLifecycleManager;
import io.quarkus.runtime.configuration.ConfigBuilder;
import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory.ConfigurableConfigSourceFactory;
import io.smallrye.config.SmallRyeConfigBuilder;

public class KubernetesConfigSourceFactoryBuilder implements ConfigBuilder {
    @Override
    public SmallRyeConfigBuilder configBuilder(final SmallRyeConfigBuilder builder) {
        return builder.withSources(new KubernetesConfigFactory());
    }

    static class KubernetesConfigFactory implements ConfigurableConfigSourceFactory<KubernetesClientConfig> {
        @Override
        public Iterable<ConfigSource> getConfigSources(final ConfigSourceContext context,
                final KubernetesClientConfig config) {
            boolean inAppCDsGeneration = Boolean
                    .parseBoolean(System.getProperty(ApplicationLifecycleManager.QUARKUS_APPCDS_GENERATE_PROP, "false"));
            if (inAppCDsGeneration) {
                return Collections.emptyList();
            }
            // TODO what should happen here?
            KubernetesClient client = KubernetesClientUtils.createClient(null, config);
            return new KubernetesConfigSourceFactory(client).getConfigSources(context);
        }
    }
}
