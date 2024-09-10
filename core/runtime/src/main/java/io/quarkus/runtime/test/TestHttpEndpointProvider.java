package io.quarkus.runtime.test;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.Function;

/**
 * Interface that can be used to integrate with the TestHTTPEndpoint infrastructure
 */
public interface TestHttpEndpointProvider {

    Function<Class<?>, String> endpointProvider();

    static List<Function<Class<?>, String>> load() {
        List<Function<Class<?>, String>> ret = new ArrayList<>();
        System.out.println("HOLLY wull load " + TestHttpEndpointProvider.class.getClassLoader() + " and tccl "
                + Thread.currentThread().getContextClassLoader());

        ClassLoader targetclassloader = TestHttpEndpointProvider.class
                .getClassLoader(); // Thread.currentThread().getContextClassLoader();
        for (TestHttpEndpointProvider i : ServiceLoader.load(TestHttpEndpointProvider.class,
                targetclassloader)) {
            ret.add(i.endpointProvider());
        }
        return ret;
    }

}
