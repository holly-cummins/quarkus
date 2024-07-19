package org.acme;

import static java.util.Arrays.asList;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;

public class MyContextProvider implements TestTemplateInvocationContextProvider {

    @Override
    public boolean supportsTestTemplate(ExtensionContext extensionContext) {
        System.out.println("HOLLY checking test template");
        Annotation[] myAnnotations = extensionContext.getClass().getAnnotations();
        Assertions.assertTrue(Arrays.toString(myAnnotations).contains("AnnotationAddedByExtensionHAHAHANO"),
                "The context provider does not see the annotation, only sees " + Arrays.toString(myAnnotations)
                        + ". The classloader is " + this.getClass().getClassLoader());

        return true;
    }

    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext extensionContext) {
        Class testClass = extensionContext.getTestClass().get();

        return Stream.of(
                context(new AnnotationCountingTestCase(testClass.getAnnotations(), testClass.getClassLoader())));

    }

    private TestTemplateInvocationContext context(AnnotationCountingTestCase testCase) {
        return new TestTemplateInvocationContext() {
            @Override
            public String getDisplayName(int invocationIndex) {
                return testCase.getDisplayString();
            }

            @Override
            public List<Extension> getAdditionalExtensions() {
                return asList(
                        new ParameterResolver() {
                            @Override
                            public boolean supportsParameter(ParameterContext parameterContext,
                                    ExtensionContext extensionContext) throws ParameterResolutionException {
                                return true;
                            }

                            @Override
                            public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
                                    throws ParameterResolutionException {
                                return extensionContext;
                            }
                        });
            }

        };
    }
}
