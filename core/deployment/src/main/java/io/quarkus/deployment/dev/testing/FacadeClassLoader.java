package io.quarkus.deployment.dev.testing;

import java.io.Closeable;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jboss.logging.Logger;

import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.runtime.LaunchMode;

/**
 * JUnit has many interceptors and listeners, but it does not allow us to intercept test discovery in a fine-grained way that
 * would allow us to swap the thread context classloader.
 * Since we can't intercept with a JUnit hook, we hijack from inside the classloader.
 * <p>
 * We need to load all our test classes in one go, during the discovery phase, before we start the applications.
 * We may need several applications and therefore, several classloaders, depending on what profiles are set.
 * To solve that, we prepare the applications, to get classloaders, and file them here.
 */
public class FacadeClassLoader extends ClassLoader implements Closeable {
    private static final Logger log = Logger.getLogger(io.quarkus.bootstrap.classloading.QuarkusClassLoader.class);
    private static final Logger lifecycleLog = Logger
            .getLogger(io.quarkus.bootstrap.classloading.QuarkusClassLoader.class.getName() + ".lifecycle");
    private static final boolean LOG_ACCESS_TO_CLOSED_CLASS_LOADERS = Boolean
            .getBoolean("quarkus-log-access-to-closed-class-loaders");

    private static final byte STATUS_OPEN = 1;
    private static final byte STATUS_CLOSING = 0;
    private static final byte STATUS_CLOSED = -1;

    protected static final String META_INF_SERVICES = "META-INF/services/";
    protected static final String JAVA = "java.";

    private String name = "FacadeLoader";
    // TODO it would be nice, and maybe theoretically possible, to re-use the curated application?
    // TODO and if we don't, how do we get a re-usable deployment classloader?

    // TODO does this need to be a thread safe maps?
    private final Map<String, CuratedApplication> curatedApplications = new HashMap<>();
    private final Map<String, QuarkusClassLoader> runtimeClassLoaders = new HashMap<>();
    private final ClassLoader parent;

    /*
     * It seems kind of wasteful to load every class twice; that's true, but it's been the case (by a different mechanism)
     * ever since Quarkus 1.2 and the move to isolated classloaders, because the test extension would reload classes into the
     * runtime classloader.
     * In the future, https://openjdk.org/jeps/466 would allow us to avoid inspecting the classes to avoid a double load in the
     * delegating
     * classloader
     * // TODO should we use the canary loader, or the parent loader?
     * //If we use the parent loader, does that stop the quarkus classloaders getting a crack at some classes?
     */
    private final ClassLoader canaryLoader;
    // TODO better mechanism; every QuarkusMainTest  gets its own application
    private int mainC = 0;
    private Map<String, String> profiles;
    private String classesPath;
    private ClassLoader otherLoader;
    private QuarkusClassLoader deploymentClassloader;
    private Set<String> quarkusTestClasses;
    private Set<String> quarkusMainTestClasses;

    public FacadeClassLoader(ClassLoader parent) {
        // TODO in dev mode, sometimes this is the deployment classloader, which doesn't seem right?
        System.out.println("HOLLY facade parent is " + parent);
        this.parent = parent;
        String classPath = System.getProperty("java.class.path");
        // This manipulation is needed to work in IDEs
        URL[] urls = Arrays.stream(classPath.split(":"))
                .map(s -> {
                    try {
                        //TODO what if it's not a file?
                        String spec = "file://" + s;

                        if (!spec.endsWith("jar") && !spec.endsWith("/")) {
                            spec = spec + "/";
                        }
                        //    System.out.println("HOLLY added " + new URL(spec) + new URL(spec).openStream().available());
                        return new URL(spec);
                    } catch (MalformedURLException e) {
                        throw new RuntimeException(e);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .toArray(URL[]::new);
        // System.out.println("HOLLY my classpath is " + Arrays.toString(urls));
        //System.out.println("HOLLY their classpath is " + Arrays.toString(urls));

        canaryLoader = new URLClassLoader(urls, null);
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        System.out.println("HOLLY loading " + name);
        boolean isQuarkusTest = false;
        // TODO we need to set this properly
        boolean isMainTest = false;
        // TODO hack that didn't even work
        //        if (runtimeClassLoader != null && name.contains("QuarkusTestProfileAwareClass")) {
        //            return runtimeClassLoader.loadClass(name);
        //        } else if (name.contains("QuarkusTestProfileAwareClass")) {
        //            return this.getClass().getClassLoader().loadClass(name);
        //
        //        }
        // TODO we can almost get away with using a string, except for type safety - maybe a dotname?
        // TODO since of course avoiding the classload would be ideal
        // Lots of downstream logic uses the class to work back to the classpath, so we can't just get rid of it (yet)
        // ... but of course at this stage we don't know anything more about the classpath than anyone else, and are just using the system property
        // ... so anything using this to get the right information will be disappointed
        // TODO we should just pass through the moduleInfo, right?
        Class<?> fromCanary = null;

        try {
            if (otherLoader != null) {
                try {
                    // TODO this is dumb, we are only loading it so that other stuff can discover a classpath from it
                    fromCanary = otherLoader
                            .loadClass(name);
                } catch (ClassNotFoundException e) {
                    System.out.println("Could not load with the OTHER loader " + name);
                    System.out.println("Used class path " + classesPath);
                    return super.loadClass(name);
                }
            } else {
                try {
                    fromCanary = canaryLoader.loadClass(name);
                } catch (ClassNotFoundException e) {
                    System.out.println("Could not load with the canary " + name);
                    //       System.out.println("Used class path " + System.getProperty("java.class.path"));
                    return super.loadClass(name);
                }
            }

            System.out.println("HOLLY yay! did load " + name);
            System.out.println("ANNOTATIONS " + Arrays.toString(fromCanary.getAnnotations()));
            Arrays.stream(fromCanary.getAnnotations())
                    .map(Annotation::annotationType)
                    .forEach(o -> System.out.println("annotation tyoe " + o));

            String profileName = "no-profile";
            Class<?> profile = null;
            if (profiles != null) {
                // TODO the good is that we're re-using what JUnitRunner already worked out, the bad is that this is seriously clunky with multiple code paths, brittle information sharing ...
                // TODO at the very least, should we have a test landscape holder class?
                isMainTest = quarkusMainTestClasses.contains(name);
                // The JUnitRunner counts main tests as quarkus tests
                isQuarkusTest = quarkusTestClasses.contains(name) && !isMainTest;

                profileName = profiles.get(name);
                if (profileName == null) {
                    profileName = "no-profile";
                }
            } else {
                // TODO JUnitRunner already worked all this out for the dev mode case, could we share some logic?

                isQuarkusTest = Arrays.stream(fromCanary.getAnnotations())
                        .anyMatch(annotation -> annotation.annotationType()
                                .getName()
                                .endsWith("QuarkusTest"));

                // TODO want to exclude quarkus component test, but include quarkusmaintest - what about quarkusunittest? and quarkusintegrationtest?
                // TODO knowledge of test annotations leaking in to here, although JUnitTestRunner also has the same leak - should we have a superclass that lives in this package that we check for?
                // TODO be tighter with the names we check for
                // TODO this would be way easier if this was in the same module as the profile, could just do clazz.getAnnotation(TestProfile.class)
                isMainTest = Arrays.stream(fromCanary.getAnnotations())
                        .anyMatch(annotation -> annotation.annotationType()
                                .getName()
                                .endsWith("QuarkusMainTest"));

                System.out.println("HOLLY canary gave " + fromCanary.getClassLoader());

                Optional<Annotation> profileAnnotation = Arrays.stream(fromCanary.getAnnotations())
                        .filter(annotation -> annotation.annotationType()
                                .getName()
                                .endsWith("TestProfile"))
                        .findFirst();
                if (profileAnnotation.isPresent()) {

                    System.out.println("HOLLY got an annotation! " + profileAnnotation.get());
                    // TODO could do getAnnotationsByType if we were in the same module
                    Method m = profileAnnotation.get()
                            .getClass()
                            .getMethod("value");
                    profile = (Class) m.invoke(profileAnnotation.get()); // TODO extends quarkustestprofile
                    System.out.println("HOLLY profile is " + profile);
                    profileName = profile.getName();
                }
            }

            if (!"no-profile".equals(profileName)) {
                //TODO is this the right classloader to use?
                profile = Class.forName(profileName);
                System.out.println("HOLLY setting profile to " + profile);
            }

            // increment the key unconditionally, we just need uniqueness
            mainC++;

            String key = isQuarkusTest ? "QuarkusTest" + "-" + profileName : isMainTest ? "MainTest" + mainC : "vanilla";
            // TODO do we need to do extra work to make sure all of the quarkus app is in the cp? We'll return versions from the parent otherwise
            // TODO think we need to make a 'first' runtime cl, and then switch for each new test?
            // TODO how do we decide what to load with our classloader - everything?
            // Doing it just for the test loads too little, doing it for everything gives java.lang.ClassCircularityError: io/quarkus/runtime/configuration/QuarkusConfigFactory
            // Anything loaded by JUnit will come through this classloader

            System.out.println("HOLLY key 2 " + key);
            if (isQuarkusTest || isMainTest) {
                System.out.println("HOLLY attempting to load " + name);
                QuarkusClassLoader runtimeClassLoader = getQuarkusClassLoader(key, fromCanary, profile);
                System.out.println("the rc parent is " + runtimeClassLoader.getParent());
                System.out.println("the grand- parent is " + runtimeClassLoader.getParent()
                        .getParent());
                Class thing = runtimeClassLoader.loadClass(name);
                System.out.println("HOLLY did load " + thing);
                return thing;
            } else {
                System.out.println("HOLLY sending to " + super.getName());
                return super.loadClass(name);
            }

        } catch (NoSuchMethodException e) {
            System.out.println("Could get method " + e);
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            System.out.println("Could not invoke " + e);
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            System.out.println("Could not access " + e);
            throw new RuntimeException(e);
        }
    }

    private QuarkusClassLoader getQuarkusClassLoader(String key, Class requiredTestClass, Class profile) {
        //TODO need to check if we should make a new one
        // TODO how do we know how to stop them?? - compare the classloader and see if it changed
        // we reload the test resources if we changed test class and the new test class is not a nested class, and if we had or will have per-test test resources
        //  boolean reloadTestResources = isNewTestClass && (hasPerTestResources || hasPerTestResources(extensionContext));
        //  if ((state == null && !failedBoot)) { //  TODO never reload, as it will not work || wrongProfile || reloadTestResources) {

        QuarkusClassLoader runtimeClassLoader = runtimeClassLoaders.get(key);
        if (runtimeClassLoader == null) {
            try {
                runtimeClassLoader = makeClassLoader(key, requiredTestClass, profile);
                // TODO diagnostic, assume everything is a per-test resource
                boolean hasPerTestResources = profile != null;
                if (!hasPerTestResources) {
                    runtimeClassLoaders.put(key, runtimeClassLoader);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return runtimeClassLoader;
    }

    private QuarkusClassLoader makeClassLoader(String key, Class requiredTestClass, Class profile) throws Exception {

        // This interception is only actually needed in limited circumstances; when
        // - running in normal mode
        // - *and* there is a @QuarkusTest to run

        // This class sets a Thead Context Classloader, which JUnit uses to load classes.
        // However, in continuous testing mode, setting a TCCL here isn't sufficient for the
        // tests to come in with our desired classloader;
        // downstream code sets the classloader to the deployment classloader, so we then need
        // to come in *after* that code.

        // TODO sometimes this is called in dev mode and sometimes it isn't? Ah, it's only not
        //  called if we die early, before we get to this

        // In continuous testing mode, the runner code will have executed before this
        // interceptor, so
        // this interceptor doesn't need to do anything.
        // TODO what if we removed the changes in the runner code?

        // Bypass all this in continuous testing mode, where the custom runner will have already initialised things before we hit this class; the startup action holder is our best way
        // of detecting it

        // TODO alternate way of detecting it ? Needs the build item, though
        // TODO could the extension pass this through to us? no, I think we're invoked before anything quarkusy, and junit5 isn't even an extension
        //        DevModeType devModeType = launchModeBuildItem.getDevModeType().orElse(null);
        //        if (devModeType == null || !devModeType.isContinuousTestingSupported()) {
        //            return;
        //        }

        // Some places do this, but that assumes we already have a classloader!         boolean isContinuousTesting = testClassClassLoader instanceof QuarkusClassLoader;

        Thread currentThread = Thread.currentThread();
        ClassLoader originalClassLoader = currentThread.getContextClassLoader();

        System.out.println("HOLLY before launch mode is " + LaunchMode.current());
        //        System.out.println("HOLLY other way us " + ConfigProvider.getConfig()
        //                .unwrap(SmallRyeConfig.class)
        //                .getProfiles());

        System.out.println("HOLLY facade original" + originalClassLoader);
        AppMakerHelper appMakerHelper = new AppMakerHelper();

        CuratedApplication curatedApplication = curatedApplications.get(key);

        if (curatedApplication == null) {
            Collection shutdownTasks = new HashSet();

            String displayName = "JUnit" + key; // TODO come up with a good display name
            curatedApplication = appMakerHelper.makeCuratedApplication(requiredTestClass, displayName,
                    shutdownTasks);
            curatedApplications.put(key, curatedApplication);

        }

        // TODO this is the cut-pasted code from JUnitTestRUnner, is it the same?
        // TODO need to pass through the 'is continuous testing?
        //        ClassLoader rcl = rcls.get(profileName);
        //        System.out.println("HOLLY rcl is " + rcl);
        //        //  rcl = null; // TODO diagnostics
        //        try {
        //            if (rcl == null) {
        //                System.out.println("HOLLY Making a java start with " + testApplication);
        //                // Although it looks like we need to start once per class, the class is just indicative of where classes for this module live
        //
        //                Class profile = null;
        //                // TODO diagnostics
        //                if (!"no-profile".equals(profileName)) {
        //                    //TODO is this the right classloader to use?
        //                    profile = Class.forName(profileName);
        //                    System.out.println("HOLLY setting profile to " + profile);
        //                }
        //                // CuratedApplications cannot (right now) be re-used between restarts. So even though the builder gave us a
        //                // curated application, don't use it.
        //                // TODO can we make the app re-usable, or otherwise leverage the app that we got passed in?
        //                System.out.println("HOLLY will make an app using profile " + profile);
        //                rcl = new AppMakerHelper()
        //                        .getStartupAction(Thread.currentThread().getContextClassLoader().loadClass(i), null,
        //                                true, profile);
        //                rcls.put(profileName, rcl);
        //            }
        //            // TODO do we need to set a TCCL? Behaviour seems the same either way
        //            Thread.currentThread().setContextClassLoader(rcl);
        //
        //            System.out.println("639 HOLLY loading quarkus test with " + Thread.currentThread().getContextClassLoader());

        //        System.out.println("MAKING Curated application with root " + applicationRoot);
        //
        //        System.out.println("An alternate root we couuld do is " + projectRoot);
        //
        //        curatedApplication = QuarkusBootstrap.builder()
        //                //.setExistingModel(gradleAppModel)
        //                // unfortunately this model is not
        //                // re-usable
        //                // due
        //                // to PathTree serialization by Gradle
        //                .setIsolateDeployment(true)
        //                .setMode(
        //                        QuarkusBootstrap.Mode.TEST) //
        //                // Even in continuous testing, we set
        //                // the mode to test - here, if we go
        //                // down this path we know it's normal mode
        //                // is this always right?
        //                .setTest(true)
        //                .setApplicationRoot(applicationRoot)
        //
        //                //                    .setTargetDirectory(
        //                //                            PathTestHelper
        //                //                            .getProjectBuildDir(
        //                //                                    projectRoot, testClassLocation))
        //                .setProjectRoot(projectRoot)
        //                //                        .setApplicationRoot(rootBuilder.build())
        //                .build()
        //                .bootstrap();

        //                    QuarkusClassLoader tcl = curatedApplication
        //                    .createDeploymentClassLoader();
        //                    System.out.println("HOLLY interceptor just made a " +
        //                    tcl);

        // TODO should we set the context classloader to the deployment classloader?
        // If not, how will anyone retrieve it?
        // TODO commenting this out doesn't change much?
        //                    Consumer currentTestAppConsumer = (Consumer) tcl
        //                    .loadClass(CurrentTestApplication.class.getName())
        //                            .getDeclaredConstructor().newInstance();
        //                    currentTestAppConsumer.accept(curatedApplication);

        // TODO   move this to close     shutdownTasks.add(curatedApplication::close);

        //        var appModelFactory = curatedApplication.getQuarkusBootstrap()
        //                .newAppModelFactory();
        //        appModelFactory.setBootstrapAppModelResolver(null);
        //        appModelFactory.setTest(true);
        //        appModelFactory.setLocalArtifacts(Set.of());
        //        // TODO    if (!mainModule) {
        //        //      appModelFactory.setAppArtifact(null);
        //        appModelFactory.setProjectRoot(projectRoot);
        //        //   }

        // To do this deserialization, we need to have an app root, so we can't use it to find the application model

        //        final ApplicationModel testModel = appModelFactory.resolveAppModel()
        //                .getApplicationModel();
        //        System.out.println("HOLLY test model is " + testModel);
        //        //                    System.out.println(
        //        //                            "module dir is " + Arrays.toString(testModel.getWorkspaceModules().toArray()));
        //        //                    System.out.println(
        //        //                            "module dir is " + ((WorkspaceModule) testModel.getWorkspaceModules().toArray()[0]).getModuleDir());
        //        System.out.println(
        //                "app dir is " + testModel.getApplicationModule()
        //                        .getModuleDir());
        //
        //        System.out.println("HOLLY after launch mode is " + LaunchMode.current());
        //        final QuarkusBootstrap.Mode currentMode = curatedApplication.getQuarkusBootstrap()
        //                .getMode();
        // TODO are all these args used?
        // TODO we are hardcoding is continuous testing to the wrong value!
        QuarkusClassLoader loader = appMakerHelper.getStartupAction(requiredTestClass,
                curatedApplication, false, profile);

        // TODO is this a good idea?
        // TODO without this, the parameter dev mode tests regress, but it feels kind of wrong - is there some use of TCCL in JUnitRunner we need to find
        currentThread.setContextClassLoader(loader);

        System.out.println("HOLLY did make a " + currentThread.getContextClassLoader());
        return loader;

    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void close() throws IOException {
        // TODO clearly, an implementation is needed!

    }

    public void setProfiles(Map<String, String> profiles) {
        this.profiles = profiles;
    }

    public void setClassPath(String classesPath) {
        this.classesPath = classesPath;
        System.out.println("HOLLY setting other classpath to " + classesPath);
        URL[] urls = Arrays.stream(classesPath.split(":"))
                .map(s -> {
                    try {
                        //TODO what if it's not a file?
                        String spec = "file://" + s;

                        if (!spec.endsWith("jar") && !spec.endsWith("/")) {
                            spec = spec + "/";
                        }
                        //    System.out.println("HOLLY added " + new URL(spec) + new URL(spec).openStream().available());
                        return new URL(spec);
                    } catch (MalformedURLException e) {
                        throw new RuntimeException(e);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .toArray(URL[]::new);
        System.out.println("HOLLY urls is  " + Arrays.toString(urls));
        otherLoader = new URLClassLoader(urls, null);
    }

    public void setQuarkusTestClasses(Set<String> quarkusTestClasses) {
        this.quarkusTestClasses = quarkusTestClasses;
    }

    public void setQuarkusMainTestClasses(Set<String> quarkusMainTestClasses) {
        this.quarkusMainTestClasses = quarkusMainTestClasses;
    }
}
