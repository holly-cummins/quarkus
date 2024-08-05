package io.quarkus.test.junit.util;

import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.ClassDescriptor;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;
import org.junit.jupiter.api.Nested;

/**
 * TODO copied code for experi,entatopn
 */
public class QuarkusTestProfileAwareClassOrderer implements ClassOrderer {

    protected static final String DEFAULT_ORDER_PREFIX_QUARKUS_TEST = "20_";
    protected static final String DEFAULT_ORDER_PREFIX_QUARKUS_TEST_WITH_PROFILE = "40_";
    protected static final String DEFAULT_ORDER_PREFIX_QUARKUS_TEST_WITH_RESTRICTED_RES = "45_";
    protected static final String DEFAULT_ORDER_PREFIX_NON_QUARKUS_TEST = "60_";

    static final String CFGKEY_ORDER_PREFIX_QUARKUS_TEST = "junit.quarkus.orderer.prefix.quarkus-test";

    static final String CFGKEY_ORDER_PREFIX_QUARKUS_TEST_WITH_PROFILE = "junit.quarkus.orderer.prefix.quarkus-test-with-profile";

    static final String CFGKEY_ORDER_PREFIX_QUARKUS_TEST_WITH_RESTRICTED_RES = "junit.quarkus.orderer.prefix.quarkus-test-with-restricted-resource";

    static final String CFGKEY_ORDER_PREFIX_NON_QUARKUS_TEST = "junit.quarkus.orderer.prefix.non-quarkus-test";

    static final String CFGKEY_SECONDARY_ORDERER = "junit.quarkus.orderer.secondary-orderer";

    @Override
    public void orderClasses(ClassOrdererContext context) {
        // don't do anything if there is just one test class or the current order request is for @Nested tests
        if (context.getClassDescriptors().size() <= 1 || context.getClassDescriptors().get(0).isAnnotated(Nested.class)) {
            return;
        }
        var prefixQuarkusTest = getConfigParam(
                CFGKEY_ORDER_PREFIX_QUARKUS_TEST,
                DEFAULT_ORDER_PREFIX_QUARKUS_TEST,
                context);
        var prefixQuarkusTestWithProfile = getConfigParam(
                CFGKEY_ORDER_PREFIX_QUARKUS_TEST_WITH_PROFILE,
                DEFAULT_ORDER_PREFIX_QUARKUS_TEST_WITH_PROFILE,
                context);
        var prefixQuarkusTestWithRestrictedResource = getConfigParam(
                CFGKEY_ORDER_PREFIX_QUARKUS_TEST_WITH_RESTRICTED_RES,
                DEFAULT_ORDER_PREFIX_QUARKUS_TEST_WITH_RESTRICTED_RES,
                context);
        var prefixNonQuarkusTest = getConfigParam(
                CFGKEY_ORDER_PREFIX_NON_QUARKUS_TEST,
                DEFAULT_ORDER_PREFIX_NON_QUARKUS_TEST,
                context);

        // first pass: run secondary orderer first (!), which is easier than running it per "grouping"
        buildSecondaryOrderer(context).orderClasses(context);
        var classDecriptors = context.getClassDescriptors();
        var firstPassIndexMap = IntStream.range(0, classDecriptors.size()).boxed()
                .collect(Collectors.toMap(classDecriptors::get, i -> String.format("%06d", i)));

        // second pass: apply the actual Quarkus aware ordering logic, using the first pass indices as order key suffixes
        classDecriptors.sort(Comparator.comparing(classDescriptor -> {
            var secondaryOrderSuffix = firstPassIndexMap.get(classDescriptor);
            Optional<String> customOrderKey = getCustomOrderKey(classDescriptor, context, secondaryOrderSuffix)
                    .or(() -> getCustomOrderKey(classDescriptor, context));
            if (customOrderKey.isPresent()) {
                return customOrderKey.get();
            }
            //            if (classDescriptor.isAnnotated(QuarkusTest.class)
            //                    || classDescriptor.isAnnotated(QuarkusIntegrationTest.class)
            //                    || classDescriptor.isAnnotated(QuarkusMainTest.class)) {
            //                return classDescriptor.findAnnotation(TestProfile.class)
            //                        .map(TestProfile::value)
            //                        .map(profileClass -> prefixQuarkusTestWithProfile + profileClass.getName() + "@" + secondaryOrderSuffix)
            //                        .orElseGet(() -> {
            //                            var prefix = hasRestrictedResource(classDescriptor)
            //                                    ? prefixQuarkusTestWithRestrictedResource
            //                                    : prefixQuarkusTest;
            //                            return prefix + secondaryOrderSuffix;
            //                        });
            //            }
            return prefixNonQuarkusTest + secondaryOrderSuffix;
        }));
    }

    private String getConfigParam(String key, String fallbackValue, ClassOrdererContext context) {
        return context.getConfigurationParameter(key).orElse(fallbackValue);
    }

    private ClassOrderer buildSecondaryOrderer(ClassOrdererContext context) {
        return Optional.ofNullable(getConfigParam(CFGKEY_SECONDARY_ORDERER, null, context))
                .map(fqcn -> {
                    try {
                        return (ClassOrderer) Class.forName(fqcn).getDeclaredConstructor().newInstance();
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalArgumentException("Failed to instantiate " + fqcn, e);
                    }
                })
                .orElseGet(ClassName::new);
    }

    private boolean hasRestrictedResource(ClassDescriptor classDescriptor) {
        return false;
        //        return classDescriptor.findRepeatableAnnotations(WithTestResource.class).stream()
        //                .anyMatch(res -> res.restrictToAnnotatedClass() || isMetaTestResource(res, classDescriptor)) ||
        //                classDescriptor.findRepeatableAnnotations(QuarkusTestResource.class).stream()
        //                        .anyMatch(res -> res.restrictToAnnotatedClass() || isMetaTestResource(res, classDescriptor));
    }

    //    @Deprecated(forRemoval = true)
    //    private boolean isMetaTestResource(QuarkusTestResource resource, ClassDescriptor classDescriptor) {
    ////        return Arrays.stream(classDescriptor.getTestClass().getAnnotationsByType(QuarkusTestResource.class))
    ////                .map(QuarkusTestResource::value)
    ////                .noneMatch(resource.value()::equals);
    //    }
    //
    //    private boolean isMetaTestResource(WithTestResource resource, ClassDescriptor classDescriptor) {
    //        return Arrays.stream(classDescriptor.getTestClass().getAnnotationsByType(WithTestResource.class))
    //                .map(WithTestResource::value)
    //                .noneMatch(resource.value()::equals);
    //    }

    /**
     * Template method that provides an optional custom order key for the given {@code classDescriptor}.
     *
     * @param classDescriptor the respective test class
     * @param context for config lookup
     * @return optional custom order key for the given test class
     * @deprecated use {@link #getCustomOrderKey(ClassDescriptor, ClassOrdererContext, String)} instead
     */
    @Deprecated(forRemoval = true, since = "2.7.0.CR1")
    protected Optional<String> getCustomOrderKey(ClassDescriptor classDescriptor, ClassOrdererContext context) {
        return Optional.empty();
    }

    /**
     * Template method that provides an optional custom order key for the given {@code classDescriptor}.
     *
     * @param classDescriptor the respective test class
     * @param context for config lookup
     * @param secondaryOrderSuffix the secondary order suffix that was calculated by the secondary orderer
     * @return optional custom order key for the given test class
     */
    protected Optional<String> getCustomOrderKey(ClassDescriptor classDescriptor, ClassOrdererContext context,
            String secondaryOrderSuffix) {
        return Optional.empty();
    }
}
