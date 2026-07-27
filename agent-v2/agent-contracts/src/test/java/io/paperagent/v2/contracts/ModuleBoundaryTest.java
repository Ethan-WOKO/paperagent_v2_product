package io.paperagent.v2.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class ModuleBoundaryTest {
    @Test
    void agentContractsHasNoNonJdkProductionDependency() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        Matcher dependencies = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(pom);
        int count = 0;
        while (dependencies.find()) {
            count++;
            String dependency = dependencies.group(1);
            assertTrue(dependency.contains("<scope>test</scope>"), dependency);
            assertTrue(dependency.contains("<groupId>org.junit.jupiter</groupId>"), dependency);
        }
        assertEquals(1, count);
    }

    @Test
    void productionSourcesDoNotImportFrameworks() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                assertFalse(source.matches("(?s).*import\\s+(org\\.springframework|jakarta\\.persistence"
                        + "|javax\\.persistence|com\\.fasterxml\\.jackson|lombok|com\\.e2b).*"),
                        file.toString());
            }
        }
    }
}
