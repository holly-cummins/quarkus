package io.quarkus.test.junit.launcher;

import org.junit.platform.launcher.LauncherInterceptor;

import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.deployment.dev.testing.FacadeClassLoader;

public class CustomLauncherInterceptor implements LauncherInterceptor {

    private final ClassLoader customClassLoader;
    private static int count = 0;
    private static int constructCount = 0;

    public CustomLauncherInterceptor() throws Exception {
        System.out.println(constructCount++ + "HOLLY interceipt construct" + getClass().getClassLoader());
        ClassLoader parent = Thread.currentThread()
                .getContextClassLoader();
        System.out.println("HOLLY CCL is " + parent);

        customClassLoader = parent;
        System.out.println("HOLLY stored variable loader" + customClassLoader);
    }

    @Override
    public <T> T intercept(Invocation<T> invocation) {
        System.out.println("HOLLY intercept");
        if (System.getProperty("prod.mode.tests") != null) {
            return invocation.proceed();

        } else {
            try {
                return nintercept(invocation);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

    }

    private <T> T nintercept(Invocation<T> invocation) {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        System.out.println("Interceipt, TCCL is " + old);
        // Don't make a facade loader if the JUnitRunner got there ahead of us
        // they set a runtime classloader so handle that too
        if (!(old instanceof FacadeClassLoader) || old instanceof QuarkusClassLoader && old.getName().contains("Runtime")) {
            System.out.println("HOLLY INTERCEPT RESTART ------------------------------");
            try {
                // TODO should this be a static variable, so we don't make zillions and cause too many files exceptions?
                // Although in principle we only go through a few times
                FacadeClassLoader facadeLoader = new FacadeClassLoader(old);
                Thread.currentThread()
                        .setContextClassLoader(facadeLoader);
                return invocation.proceed();
            } finally {
                Thread.currentThread()
                        .setContextClassLoader(old);
            }
        } else {
            return invocation.proceed();
        }
    }

    @Override
    public void close() {

        //        //        try {
        //        //            // TODO     customClassLoader.close();
        //        //        } catch (Exception e) {
        //        //            throw new UncheckedIOException("Failed to close custom class
        //        loader", e);
        //        //        }
    }
}
