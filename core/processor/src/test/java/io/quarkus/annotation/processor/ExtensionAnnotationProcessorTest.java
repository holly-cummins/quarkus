package io.quarkus.annotation.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.wildfly.common.Assert.assertNotNull;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import javax.tools.JavaFileObject;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.karuslabs.elementary.Results;
import com.karuslabs.elementary.junit.JavacExtension;
import com.karuslabs.elementary.junit.annotations.Classpath;
import com.karuslabs.elementary.junit.annotations.Processors;

import io.quarkus.annotation.processor.fs.CustomMemoryFileSystem;
import io.quarkus.annotation.processor.fs.CustomMemoryFileSystemProvider;

@ExtendWith(JavacExtension.class)
@Processors({ ExtensionAnnotationProcessor.class })
class ExtensionAnnotationProcessorTest {
    @BeforeAll
    public static void beforeAll() throws IOException {
        CustomMemoryFileSystemProvider customProvider = new CustomMemoryFileSystemProvider();
        CustomMemoryFileSystem fs = new CustomMemoryFileSystem(customProvider);
        fs.addFile(URI.create("mem:///Test1.txt"), "This is a test file content".getBytes());

        URI uri = URI.create("mem:///Test2.txt");

        Path path = fs.provider()
                .getPath(uri);
        byte[] content = Files.readAllBytes(path);
        System.out.println(new String(content));

        // Get the default FileSystemProvider map
        System.out.printf(Arrays.toString(FileSystems.getDefault()
                .provider()
                .installedProviders()
                .toArray()));

        //         TODO close it
    }

    @BeforeEach
    void beforeEach() {
        // This is of limited use, since the filesystem doesn't seem to directly generate files, in the current usage
        CustomMemoryFileSystemProvider.reset();
    }

    @Test
    @Classpath("org.acme.examples.ClassWithBuildStep")
    void shouldProcessClassWithBuildStepWithoutErrors(Results results) throws IOException {
        assertNoErrrors(results);
    }

    @Test
    @Classpath("org.acme.examples.ClassWithBuildStep")
    void shouldGenerateABscFile(Results results) throws IOException {
        assertNoErrrors(results);
        List<JavaFileObject> sources = results.sources;
        JavaFileObject bscFile = sources.stream()
                .filter(source -> source.getName()
                        .endsWith(".bsc"))
                .findAny()
                .orElse(null);
        assertNotNull(bscFile);

        String contents = removeLineBreaks(new String(bscFile
                .openInputStream()
                .readAllBytes(), StandardCharsets.UTF_8));
        assertEquals("org.acme.examples.ClassWithBuildStep", contents);
    }

    private String removeLineBreaks(String s) {
        return s.replace(System.getProperty("line.separator"), "")
                .replace("\n", "");
    }

    @Test
    @Classpath("org.acme.examples.ClassWithoutBuildStep")
    void shouldProcessEmptyClassWithoutErrors(Results results) {
        assertNoErrrors(results);
    }

    private static void assertNoErrrors(Results results) {
        assertEquals(0, results.find()
                .errors()
                .count(),
                "Errors were: " + results.find()
                        .errors()
                        .diagnostics());
    }
}
