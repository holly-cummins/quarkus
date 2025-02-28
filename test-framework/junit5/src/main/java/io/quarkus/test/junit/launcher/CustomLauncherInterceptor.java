package io.quarkus.test.junit.launcher;

import org.junit.platform.launcher.LauncherInterceptor;

import io.quarkus.test.junit.classloading.FacadeClassLoader;

public class CustomLauncherInterceptor implements LauncherInterceptor {

    FacadeClassLoader facadeLoader = null;

    public CustomLauncherInterceptor() {
    }

    @Override
    public <T> T intercept(Invocation<T> invocation) {
        // Do not do any classloading dance for prod mode tests;
        if (System.getProperty("prod.mode.tests") != null) {
            return invocation.proceed();

        } else {
            return actuallyIntercept(invocation);
        }

    }

    private <T> T actuallyIntercept(Invocation<T> invocation) {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        // Be aware, this method might be called more than once, for different kinds of invocations; especially for Gradle executions, the executions could happen before the TCCL gets constructed and set
        try {
            //TODO  We want to tidy up classloaders we created, but not ones created upstream
            // Although in principle we only go through a few times
            facadeLoader = FacadeClassLoader.instance(old);
            Thread.currentThread()
                    .setContextClassLoader(facadeLoader);
            return invocation.proceed();
        } finally {
            // TODO It would clearly be nice to tidy up, but I think the gradle
            // devtools tests may be asynchronous, because if we reset the TCCL
            // at this point, the test loads with the wrong classloader
            //                Thread.currentThread()
            //                        .setContextClassLoader(old);
        }

    }

    @Override
    public void close() {

        try {
            // Tidy up classloaders we created, but not ones created upstream
            // TODO this distinction is meaningless since it's a singleton
            if (facadeLoader != null) {
                facadeLoader.close();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to close custom classloader", e);
        }
    }
}
