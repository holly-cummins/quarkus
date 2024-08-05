package io.quarkus.google.cloud.functions.test;

import java.util.Collections;
import java.util.Map;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.test.common.QuarkusTestResourceConfigurableLifecycleManager;

/**
 * Test resource that starts a Google Cloud Function invoker at the beginning of the test and stops it at the end.
 * It must be configured with the {@link WithFunction} annotation.
 */
public class CloudFunctionTestResource implements QuarkusTestResourceConfigurableLifecycleManager<WithFunction> {

    private FunctionType functionType;
    private String functionName;
    private CloudFunctionsInvoker invoker;

    @Override
    public void init(WithFunction withFunction) {
        this.functionType = withFunction.value();
        this.functionName = withFunction.functionName();
    }

    @Override
    public Map<String, String> start() {
        System.out.println("HOLLY >>>>>>>>>> REAL START INVOKER" + invoker);

        Map<String, String> answer = "".equals(functionName) ? Collections.emptyMap()
                : Map.of(functionType.getFunctionProperty(), functionName);

        return answer;
    }

    @Override
    public void inject(TestInjector testInjector) {
        // This is a hack, we cannot start the invoker in the start() method as Quarkus is not yet initialized,
        // so we start it here as this method is called later (the same for reading the test port).

        System.out.println("HOLLY >>>>>>>>>> ABOUT TO INJECT INVOKER" + invoker);

        // We might call inject several times, since this is an inject, not a start
        // Starting the same invoker on the same port multiple times is not going to succeed
        // TODO check if this can safely be moved to start()
        // TODO check if we can do a stop() + start() instead of skipping it

        if (invoker == null) {
            int port = ConfigProvider.getConfig()
                    .getOptionalValue("quarkus.http.test-port", Integer.class)
                    .orElse(8081);

            this.invoker = new CloudFunctionsInvoker(functionType, port);

            try {
                this.invoker.start();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        System.out.println("HOLLY done inject invoker");

    }

    @Override
    public void stop() {
        try {
            System.out.println("HOLLY<<<<<<<<<<<<<< STOP INVOKER");
            this.invoker.stop();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
