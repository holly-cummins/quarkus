package io.quarkus.test.junit;

import static io.quarkus.commons.classloading.ClassloadHelper.fromClassNameToResourceName;
import static io.quarkus.test.common.PathTestHelper.getAppClassLocationForTestLocation;
import static io.quarkus.test.common.PathTestHelper.getTestClassesLocation;
import static io.quarkus.test.junit.IntegrationTestUtil.activateLogging;
import static io.quarkus.test.junit.IntegrationTestUtil.getAdditionalTestResources;

import java.io.Closeable;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.stream.Collectors;

import jakarta.enterprise.inject.Alternative;

import org.jboss.jandex.Index;
import org.jboss.logmanager.LogContext;
import org.jboss.logmanager.handlers.OutputStreamHandler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.app.StartupAction;
import io.quarkus.bootstrap.logging.InitialConfigurator;
import io.quarkus.bootstrap.logging.QuarkusDelayedHandler;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.runner.Timing;
import io.quarkus.bootstrap.workspace.ArtifactSources;
import io.quarkus.bootstrap.workspace.SourceDir;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.deployment.dev.testing.CurrentTestApplication;
import io.quarkus.deployment.dev.testing.LogCapturingOutputFilter;
import io.quarkus.dev.console.QuarkusConsole;
import io.quarkus.dev.testing.TracingHandler;
import io.quarkus.paths.PathList;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.logging.JBossVersion;
import io.quarkus.test.common.PathTestHelper;
import io.quarkus.test.common.RestorableSystemProperties;
import io.quarkus.test.common.TestClassIndexer;
import io.quarkus.test.common.TestResourceManager;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainIntegrationTest;
import io.quarkus.test.junit.main.QuarkusMainLauncher;

public class QuarkusMainTestExtension extends AbstractJvmQuarkusTestExtension
        implements InvocationInterceptor, BeforeEachCallback, AfterEachCallback, ParameterResolver, BeforeAllCallback,
        AfterAllCallback {

    PrepareResult prepareResult;
    private static boolean hasPerTestResources;

    /**
     * The result from an {@link Launch} test
     */
    LaunchResult result;

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        if (isIntegrationTest(context.getRequiredTestClass())) {
            return;
        }

        Class<? extends QuarkusTestProfile> profile = getQuarkusTestProfile(context);
        ensurePrepared(context, profile);
    }

    private void ensurePrepared(ExtensionContext extensionContext, Class<? extends QuarkusTestProfile> profile)
            throws Exception {
        JBossVersion.disableVersionLogging();
        QuarkusTestExtensionState state = getState(extensionContext);
        boolean wrongProfile = !Objects.equals(profile, quarkusTestProfile);
        // we reload the test resources if we changed test class and if we had or will have per-test test resources
        boolean reloadTestResources = !Objects.equals(extensionContext.getRequiredTestClass(), currentJUnitTestClass)
                && (hasPerTestResources || hasPerTestResources(extensionContext));
        if (wrongProfile || reloadTestResources) {
            if (state != null) {
                try {
                    state.close();
                } catch (Throwable throwable) {
                    throwable.printStackTrace();
                }
            }
            prepareResult = null;
        }
        if (prepareResult == null) {
            final LinkedBlockingDeque<Runnable> shutdownTasks = new LinkedBlockingDeque<>();
            PrepareResult result = createAugmentor(extensionContext, profile, shutdownTasks);
            prepareResult = result;
        }
    }

    private LaunchResult doLaunch(ExtensionContext context, Class<? extends QuarkusTestProfile> selectedProfile,
            String[] arguments) throws Exception {
        ensurePrepared(context, selectedProfile);
        LogCapturingOutputFilter filter = new LogCapturingOutputFilter(prepareResult.curatedApplication, false, false,
                () -> true);
        QuarkusConsole.addOutputFilter(filter);
        try {
            var result = doJavaStart(context, selectedProfile, arguments);
            //merge all the output into one, strip ansi, then split into lines
            List<String> out = Arrays
                    .asList(String.join("", filter.captureOutput()).replaceAll("\\u001B\\[(.*?)[a-zA-Z]", "").split("\n"));
            List<String> err = Arrays
                    .asList(String.join("", filter.captureErrorOutput()).replaceAll("\\u001B\\[(.*?)[a-zA-Z]", "").split("\n"));
            return new LaunchResult() {
                @Override
                public List<String> getOutputStream() {
                    return out;
                }

                @Override
                public List<String> getErrorStream() {
                    return err;
                }

                @Override
                public int exitCode() {
                    return result;
                }
            };
        } finally {
            QuarkusConsole.removeOutputFilter(filter);
            Thread.currentThread().setContextClassLoader(originalCl);
        }
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        result = null;
    }

    private static Handler ORIGINAL_QUARKUS_CONSOLE_HANDLER = null;
    private static Handler REDIRECT_QUARKUS_CONSOLE_HANDLER = null;

    private static void installLoggerRedirect() throws Exception {
        var rootLogger = LogContext.getLogContext().getLogger("");

        ORIGINAL_QUARKUS_CONSOLE_HANDLER = null;
        REDIRECT_QUARKUS_CONSOLE_HANDLER = null;

        for (var topLevelHandler : rootLogger.getHandlers()) {
            if (topLevelHandler instanceof QuarkusDelayedHandler) {
                ORIGINAL_QUARKUS_CONSOLE_HANDLER = topLevelHandler;
                for (var h : ((QuarkusDelayedHandler) topLevelHandler).getHandlers()) {
                    if (h instanceof org.jboss.logmanager.handlers.ConsoleHandler) {
                        REDIRECT_QUARKUS_CONSOLE_HANDLER = new OutputStreamHandler(QuarkusConsole.REDIRECT_OUT,
                                h.getFormatter());
                        break;
                    }
                }
                break;
            }
        }

        if (REDIRECT_QUARKUS_CONSOLE_HANDLER != null) {
            rootLogger.removeHandler(ORIGINAL_QUARKUS_CONSOLE_HANDLER);
            rootLogger.addHandler(REDIRECT_QUARKUS_CONSOLE_HANDLER);
        }
    }

    private static void uninstallLoggerRedirect() throws Exception {
        var rootLogger = LogContext.getLogContext().getLogger("");
        if (REDIRECT_QUARKUS_CONSOLE_HANDLER != null) {
            rootLogger.addHandler(ORIGINAL_QUARKUS_CONSOLE_HANDLER);
            rootLogger.removeHandler(REDIRECT_QUARKUS_CONSOLE_HANDLER);
        }
    }

    private void flushAllLoggers() {
        Enumeration<String> loggerNames = org.jboss.logmanager.LogContext.getLogContext().getLoggerNames();
        while (loggerNames != null && loggerNames.hasMoreElements()) {
            String loggerName = loggerNames.nextElement();
            var logger = org.jboss.logmanager.LogContext.getLogContext().getLogger(loggerName);
            for (Handler h : logger.getHandlers()) {
                h.flush();
            }
        }
    }

    private int doJavaStart(ExtensionContext context, Class<? extends QuarkusTestProfile> profile, String[] arguments)
            throws Exception {
        JBossVersion.disableVersionLogging();

        TracingHandler.quarkusStarting();
        Closeable testResourceManager = null;
        try {
            StartupAction startupAction = prepareResult.augmentAction.createInitialRuntimeApplication();
            Thread.currentThread().setContextClassLoader(startupAction.getClassLoader());
            QuarkusConsole.installRedirects();
            flushAllLoggers();
            installLoggerRedirect();

            QuarkusTestProfile profileInstance = prepareResult.profileInstance;

            //must be done after the TCCL has been set
            testResourceManager = (Closeable) startupAction.getClassLoader().loadClass(TestResourceManager.class.getName())
                    .getConstructor(Class.class, Class.class, List.class, boolean.class, Map.class, Optional.class)
                    .newInstance(context.getRequiredTestClass(),
                            profile != null ? profile : null,
                            getAdditionalTestResources(profileInstance, startupAction.getClassLoader()),
                            profileInstance != null && profileInstance.disableGlobalTestResources(),
                            startupAction.getDevServicesProperties(), Optional.empty());
            testResourceManager.getClass().getMethod("init", String.class).invoke(testResourceManager,
                    profile != null ? profile.getName() : null);
            Map<String, String> properties = (Map<String, String>) testResourceManager.getClass().getMethod("start")
                    .invoke(testResourceManager);
            startupAction.overrideConfig(properties);
            hasPerTestResources = (boolean) testResourceManager.getClass().getMethod("hasPerTestResources")
                    .invoke(testResourceManager);

            testResourceManager.getClass().getMethod("inject", Object.class)
                    .invoke(testResourceManager, context.getRequiredTestInstance());

            var result = startupAction.runMainClassBlocking(arguments);
            flushAllLoggers();
            return result;
        } catch (Throwable e) {
            if (!InitialConfigurator.DELAYED_HANDLER.isActivated()) {
                activateLogging();
            }
            throw e;
        } finally {
            try {
                if (testResourceManager != null) {
                    testResourceManager.close();
                }
            } catch (Exception e) {
                System.err.println("Unable to shutdown resource: " + e.getMessage());
            }

            uninstallLoggerRedirect();
            QuarkusConsole.uninstallRedirects();
            if (originalCl != null) {
                Thread.currentThread().setContextClassLoader(originalCl);
            }
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        if (isIntegrationTest(extensionContext.getRequiredTestClass())) {
            return false;
        }
        Class<?> type = parameterContext.getParameter().getType();
        return type == LaunchResult.class || type == QuarkusMainLauncher.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Class<?> type = parameterContext.getParameter().getType();
        Class<? extends QuarkusTestProfile> profile = getQuarkusTestProfile(extensionContext);
        if (type == LaunchResult.class) {
            var launch = extensionContext.getRequiredTestMethod().getAnnotation(Launch.class);
            if (launch != null) {
                doLaunchAndAssertExitCode(extensionContext, profile, launch);
            } else {
                throw new RuntimeException(
                        "To use 'LaunchResult' as a method parameter, the test must be annotated with '@Launch'");
            }

            return result;
        } else if (type == QuarkusMainLauncher.class) {
            return new QuarkusMainLauncher() {
                @Override
                public LaunchResult launch(String... args) {
                    try {
                        return doLaunch(extensionContext, profile, args);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            };
        } else {
            throw new RuntimeException("Parameter type not supported");
        }
    }

    private void doLaunchAndAssertExitCode(ExtensionContext extensionContext, Class<? extends QuarkusTestProfile> profile,
            Launch launch) {
        // HACK: we launch the application in this method instead of in beforeEach in order to ensure that
        // tests that use @BeforeEach will have a chance to run before the application is launched

        String[] arguments = launch.value();
        LaunchResult r;
        try {
            r = doLaunch(extensionContext, profile, arguments);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Assertions.assertEquals(launch.exitCode(), r.exitCode(),
                "Exit code did not match, output: " + r.getOutput() + " " + r.getErrorOutput());
        this.result = r;
    }

    @Override
    public void interceptTestMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
            ExtensionContext extensionContext) throws Throwable {
        Class<? extends QuarkusTestProfile> profile = getQuarkusTestProfile(extensionContext);
        if (invocationContext.getArguments().isEmpty()) {
            var launch = extensionContext.getRequiredTestMethod().getAnnotation(Launch.class);
            if (launch != null) {
                // in this case, resolveParameter has not been called by JUnit, so we need to make sure the application is launched
                doLaunchAndAssertExitCode(extensionContext, profile, launch);
            }
        }

        invocation.proceed();
    }

    private boolean isIntegrationTest(Class<?> clazz) {
        for (Class<?> i : currentTestClassStack) {
            if (i.isAnnotationPresent(QuarkusMainIntegrationTest.class)) {
                return true;
            }
        }
        if (clazz.isAnnotationPresent(QuarkusMainIntegrationTest.class)) {
            return true;
        }
        return false;
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        currentTestClassStack.pop();
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        currentTestClassStack.push(context.getRequiredTestClass());
    }

    // TODO this is cloned from the pre-rewrite AbstractJvmQuarkusTestExtension to do things the old way, but code can probably be consolidated
    @Override
    protected PrepareResult createAugmentor(ExtensionContext context, Class<? extends QuarkusTestProfile> profile,
            Collection<Runnable> shutdownTasks) throws Exception {

        final PathList.Builder rootBuilder = PathList.builder();
        Consumer<Path> addToBuilderIfConditionMet = path -> {
            if (path != null && Files.exists(path) && !rootBuilder.contains(path)) {
                rootBuilder.add(path);
            }
        };

        final Class<?> requiredTestClass = context.getRequiredTestClass();
        currentJUnitTestClass = requiredTestClass;

        final Path testClassLocation;
        final Path appClassLocation;
        final Path projectRoot = Paths.get("").normalize().toAbsolutePath();

        final ApplicationModel gradleAppModel = getGradleAppModelForIDE(projectRoot);
        // If gradle project running directly with IDE
        if (gradleAppModel != null && gradleAppModel.getApplicationModule() != null) {
            final WorkspaceModule module = gradleAppModel.getApplicationModule();
            final String testClassFileName = fromClassNameToResourceName(requiredTestClass.getName());
            Path testClassesDir = null;
            for (String classifier : module.getSourceClassifiers()) {
                final ArtifactSources sources = module.getSources(classifier);
                if (sources.isOutputAvailable() && sources.getOutputTree().contains(testClassFileName)) {
                    for (SourceDir src : sources.getSourceDirs()) {
                        addToBuilderIfConditionMet.accept(src.getOutputDir());
                        if (Files.exists(src.getOutputDir().resolve(testClassFileName))) {
                            testClassesDir = src.getOutputDir();
                        }
                    }
                    for (SourceDir src : sources.getResourceDirs()) {
                        addToBuilderIfConditionMet.accept(src.getOutputDir());
                    }
                    for (SourceDir src : module.getMainSources().getSourceDirs()) {
                        addToBuilderIfConditionMet.accept(src.getOutputDir());
                    }
                    for (SourceDir src : module.getMainSources().getResourceDirs()) {
                        addToBuilderIfConditionMet.accept(src.getOutputDir());
                    }
                    break;
                }
            }
            if (testClassesDir == null) {
                final StringBuilder sb = new StringBuilder();
                sb.append("Failed to locate ").append(requiredTestClass.getName()).append(" in ");
                for (String classifier : module.getSourceClassifiers()) {
                    final ArtifactSources sources = module.getSources(classifier);
                    if (sources.isOutputAvailable()) {
                        for (SourceDir d : sources.getSourceDirs()) {
                            if (Files.exists(d.getOutputDir())) {
                                sb.append(System.lineSeparator()).append(d.getOutputDir());
                            }
                        }
                    }
                }
                throw new RuntimeException(sb.toString());
            }
            testClassLocation = testClassesDir;

        } else {
            if (System.getProperty(BootstrapConstants.OUTPUT_SOURCES_DIR) != null) {
                final String[] sourceDirectories = System.getProperty(BootstrapConstants.OUTPUT_SOURCES_DIR).split(",");
                for (String sourceDirectory : sourceDirectories) {
                    final Path directory = Paths.get(sourceDirectory);
                    addToBuilderIfConditionMet.accept(directory);
                }
            }

            testClassLocation = getTestClassesLocation(requiredTestClass);
            appClassLocation = getAppClassLocationForTestLocation(testClassLocation.toString());
            if (!appClassLocation.equals(testClassLocation)) {
                addToBuilderIfConditionMet.accept(testClassLocation);
                // if test classes is a dir, we should also check whether test resources dir exists as a separate dir (gradle)
                // TODO: this whole app/test path resolution logic is pretty dumb, it needs be re-worked using proper workspace discovery
                final Path testResourcesLocation = PathTestHelper.getResourcesForClassesDirOrNull(testClassLocation, "test");
                addToBuilderIfConditionMet.accept(testResourcesLocation);
            }

            addToBuilderIfConditionMet.accept(appClassLocation);
            final Path appResourcesLocation = PathTestHelper.getResourcesForClassesDirOrNull(appClassLocation, "main");
            addToBuilderIfConditionMet.accept(appResourcesLocation);
        }

        originalCl = Thread.currentThread().getContextClassLoader();

        // clear the test.url system property as the value leaks into the run when using different profiles
        System.clearProperty("test.url");
        Map<String, String> additional = new HashMap<>();

        QuarkusTestProfile profileInstance = null;
        if (profile != null) {
            profileInstance = profile.getConstructor().newInstance();
            additional.putAll(profileInstance.getConfigOverrides());
            if (!profileInstance.getEnabledAlternatives().isEmpty()) {
                additional.put("quarkus.arc.selected-alternatives", profileInstance.getEnabledAlternatives().stream()
                        .peek((c) -> {
                            if (!c.isAnnotationPresent(Alternative.class)) {
                                throw new RuntimeException(
                                        "Enabled alternative " + c + " is not annotated with @Alternative");
                            }
                        })
                        .map(Class::getName).collect(Collectors.joining(",")));
            }
            if (profileInstance.disableApplicationLifecycleObservers()) {
                additional.put("quarkus.arc.test.disable-application-lifecycle-observers", "true");
            }
            if (profileInstance.getConfigProfile() != null) {
                additional.put(LaunchMode.TEST.getProfileKey(), profileInstance.getConfigProfile());
            }
            //we just use system properties for now
            //it's a lot simpler
            shutdownTasks.add(RestorableSystemProperties.setProperties(additional)::close);
        }

        CuratedApplication curatedApplication;
        if (CurrentTestApplication.curatedApplication != null) {
            curatedApplication = CurrentTestApplication.curatedApplication;
        } else {
            curatedApplication = QuarkusBootstrap.builder()
                    //.setExistingModel(gradleAppModel) unfortunately this model is not re-usable due to PathTree serialization by Gradle
                    .setBaseName(context.getDisplayName() + " (QuarkusTest)")
                    .setIsolateDeployment(true)
                    .setMode(QuarkusBootstrap.Mode.TEST)
                    .setTest(true)
                    .setTargetDirectory(PathTestHelper.getProjectBuildDir(projectRoot, testClassLocation))
                    .setProjectRoot(projectRoot)
                    .setApplicationRoot(rootBuilder.build())
                    .build()
                    .bootstrap();
            shutdownTasks.add(curatedApplication::close);
        }

        if (curatedApplication.getApplicationModel().getRuntimeDependencies().isEmpty()) {
            throw new RuntimeException(
                    "The tests were run against a directory that does not contain a Quarkus project. Please ensure that the test is configured to use the proper working directory.");
        }

        Index testClassesIndex = TestClassIndexer.indexTestClasses(testClassLocation);
        // we need to write the Index to make it reusable from other parts of the testing infrastructure that run in different ClassLoaders
        TestClassIndexer.writeIndex(testClassesIndex, testClassLocation, requiredTestClass);

        Timing.staticInitStarted(curatedApplication.getOrCreateBaseRuntimeClassLoader(),
                curatedApplication.getQuarkusBootstrap().isAuxiliaryApplication());
        final Map<String, Object> props = new HashMap<>();
        props.put(TEST_LOCATION, testClassLocation);
        props.put(TEST_CLASS, requiredTestClass);
        if (profile != null) {
            props.put(TEST_PROFILE, profile.getName());
        }
        quarkusTestProfile = profile;
        return new PrepareResult(curatedApplication
                .createAugmentor(QuarkusTestExtension.TestBuildChainFunction.class.getName(), props), profileInstance,
                curatedApplication, testClassLocation);
    }
}
