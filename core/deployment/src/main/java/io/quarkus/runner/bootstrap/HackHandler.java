package io.quarkus.runner.bootstrap;

import java.util.function.BiConsumer;

import io.quarkus.builder.BuildResult;

public class HackHandler implements BiConsumer<Object, BuildResult> {
    @Override
    public void accept(Object o, BuildResult buildResult) {
        System.out.println("HOLLY HAckHandler accepting 0" + o);
        System.out.println("HOLLY us " + buildResult);
    }
}
