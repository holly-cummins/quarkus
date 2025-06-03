package io.quarkus.deployment.builditem;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.jboss.logging.Logger;

import io.quarkus.builder.item.MultiBuildItem;
import io.quarkus.deployment.dev.devservices.DevServicesConfig;
import io.quarkus.devservices.crossclassloader.runtime.DevServiceOwner;
import io.quarkus.runtime.LaunchMode;

/**
 * BuildItem for running dev services.
 * Combines injected configs to the application with container id (if it exists).
 * <p>
 * Processors are expected to return this build item not only when the dev service first starts,
 * but also if a running dev service already exists.
 * <p>
 * {@link RunningDevService} helps to manage the lifecycle of the running dev service.
 */
public final class DevServicesResultBuildItem extends MultiBuildItem {

    private static final Logger log = Logger.getLogger(DevServicesResultBuildItem.class);

    private final String name;
    private final String description;
    private final String containerId;
    protected final Map<String, String> config;
    protected RunnableDevService runnableDevService;

    public DevServicesResultBuildItem(String name, String containerId, Map<String, String> config) {
        this(name, null, containerId, config);
    }

    public DevServicesResultBuildItem(String name, String description, String containerId, Map<String, String> config) {
        this.name = name;
        this.description = description;
        this.containerId = containerId;
        this.config = config;
    }

    public DevServicesResultBuildItem(String name, String description, String containerId, Map<String, String> config,
            RunnableDevService runnableDevService) {
        this(name, description, containerId, config);
        this.runnableDevService = runnableDevService;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getContainerId() {
        return containerId;
    }

    public Map<String, String> getConfig() {
        return config;
    }

    public void start() {
        if (runnableDevService != null) {
            runnableDevService.start();
        } else {
            log.debugf("Not starting %s because runnable dev service is null (it is probably a running dev service.", name);
        }
    }

    // Ideally everyone would use the config source, but if people need to ask for config directly, make it possible
    public Map<String, String> getDynamicConfig() {
        if (runnableDevService != null && runnableDevService.isRunning()) {
            return runnableDevService.get();
        } else {
            return Collections.emptyMap();
        }
    }

    public static class RunningDevService implements Closeable {

        protected final String name;
        protected final String description;
        protected final String containerId;
        protected final Map<String, String> config;
        protected final Closeable closeable;
        protected volatile boolean isRunning = true;

        private static Map<String, String> mapOf(String key, String value) {
            Map<String, String> map = new HashMap<>();
            map.put(key, value);
            return map;
        }

        public RunningDevService(String name, String containerId, Closeable closeable, String key,
                String value) {
            this(name, null, containerId, closeable, mapOf(key, value));
        }

        public RunningDevService(String name, String description, String containerId, Closeable closeable, String key,
                String value) {
            this(name, description, containerId, closeable, mapOf(key, value));
        }

        public RunningDevService(String name, String containerId, Closeable closeable,
                Map<String, String> config) {
            this(name, null, containerId, closeable, config);
        }

        public RunningDevService(String name, String description, String containerId, Closeable closeable,
                Map<String, String> config) {
            this.name = name;
            this.description = description;
            this.containerId = containerId;
            this.closeable = closeable;
            this.config = Collections.unmodifiableMap(config);
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public String getContainerId() {
            return containerId;
        }

        public Map<String, String> getConfig() {
            return config;
        }

        public Closeable getCloseable() {
            return closeable;
        }

        // This method should be on RunningDevService, but not on RunnableDevService, where we use different logic to
        // decide when it's time to close a container. For now, leave it where it is and hope it doesn't get called when it shouldn't.
        // We can either make a common parent class or throw unsupported when this is called from Runnable.
        public boolean isOwner() {
            return closeable != null;
        }

        @Override
        public void close() throws IOException {
            if (this.closeable != null) {
                this.closeable.close();
                isRunning = false;
            }
        }

        public DevServicesResultBuildItem toBuildItem() {
            return new DevServicesResultBuildItem(name, description, containerId, config);
        }
    }

    public static class RunnableDevService extends RunningDevService implements Supplier<Map<String, String>> {
        final DevServicesTrackerBuildItem tracker;

        private final Startable container;
        private final DevServiceOwner owner;
        private final DevServicesConfig globalConfig;
        private final Object identifyingConfig;
        private Map<String, Supplier<String>> lazyConfig;

        /**
         * There are 4 configs in this argument, but there's a reason! (For now, at least.)
         * The second two are what the user passed in, and are what we use to establish ownership and reusability.
         * The first two are generated by the processor, usually based on what the user passes in. They're passed on to the
         * application.
         *
         * @param launchMode
         * @param containerId
         * @param container
         * @param config
         * @param lazyConfig
         * @param globalConfig
         * @param identifyingConfig
         * @param tracker
         */
        public RunnableDevService(String featureName, LaunchMode launchMode, String configName, String containerId,
                Startable container, Map config,
                Map lazyConfig, DevServicesConfig globalConfig, Object identifyingConfig, DevServicesTrackerBuildItem tracker) {
            super(featureName, containerId, container::close, config);

            this.owner = new DevServiceOwner(featureName, launchMode.name(), configName);
            this.container = container;
            this.tracker = tracker;
            isRunning = false;
            this.lazyConfig = lazyConfig;
            this.globalConfig = globalConfig;
            this.identifyingConfig = identifyingConfig;
        }

        public boolean isRunning() {
            return isRunning;
        }

        /**
         * Starts the service, after first checking for a compatible service in the tracker.
         * Calling classes may wish to do their own checks for compatible services before calling start().
         */
        public void start() {
            // We want to do two things; find things with the same config as us to reuse them, and find things with different config to close them
            // We figure out if we need to shut down existing redis containers that might have been started in previous profiles or restarts

            // These RunnableDevService classes could be from another classloader, so don't make assumptions about the class
            Collection<?> matchedDevServices = tracker.getRunningServices(owner, globalConfig,
                    identifyingConfig);
            // if the redis containers have already started we just return; if we wanted to be very cautious we could check the entries for an isRunningStatus, but they might be in the wrong classloader, so that's hard work
            if (matchedDevServices == null || matchedDevServices.isEmpty()) {
                // There isn't a running container that has the right config, we need to do work
                // Let's get all the running dev services associated with this feature (+ launch mode plus named section), so we can close them
                Collection<Closeable> unusableDevServices = tracker.getAllRunningServices(owner);
                if (unusableDevServices != null) {
                    for (Closeable closeable : unusableDevServices) {
                        try {
                            closeable.close();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }

                reallyStart();
            }
        }

        /**
         * Starts, without doing any duplicate checking, and without doing any cleanup.
         * The duplicate checking is optional, the cleanup is not.
         */
        private void reallyStart() {
            if (container != null) {
                container.start();
                //  tell the tracker that we started
                isRunning = true;
                tracker.addRunningService(owner, globalConfig, identifyingConfig, this);
                // Ideally we'd print out a port number here, but we can only do that if we add a dependency on GenericContainer (or update startable to add a method)

                log.infof("The %s dev service is ready to accept connections", name);
            }
        }

        @Override
        public void close() throws IOException {
            tracker.removeRunningService(owner, globalConfig, identifyingConfig, this);
            super.close();
        }

        public DevServicesResultBuildItem toBuildItem() {
            return new DevServicesResultBuildItem(name, description, containerId, config, this);
        }

        /**
         * This is a supplier interface to maintain type-compatibility across classloaders.
         * What this is actually giving is an aggregate of the hardcoded and lazy (dynamic at runtime) config.
         *
         * @return
         */
        @Override
        public Map<String, String> get() {
            // printlns show this gets called super often, so want to be as efficient as we can in this code
            Map<String, String> newConfig = new HashMap<>(getConfig());
            for (Map.Entry<String, Supplier<String>> entry : lazyConfig.entrySet()) {
                newConfig.put(entry.getKey(), entry.getValue().get());
            }
            return newConfig;
        }

    }
}
