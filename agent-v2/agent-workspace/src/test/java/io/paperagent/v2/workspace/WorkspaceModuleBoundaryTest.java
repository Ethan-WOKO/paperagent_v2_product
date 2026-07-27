package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import io.paperagent.v2.contracts.WorkspaceRef;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceModuleBoundaryTest {
    @Test
    void productionDependsOnlyOnContractsAndJdk() throws Exception {
        Path module = moduleDirectory();
        var document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(module.resolve("pom.xml").toFile());
        var dependencies = document.getElementsByTagName("dependency");
        List<String> productionDependencies = new ArrayList<>();
        for (int index = 0; index < dependencies.getLength(); index++) {
            Element dependency = (Element) dependencies.item(index);
            String scope = text(dependency, "scope");
            if (!"test".equals(scope)) {
                productionDependencies.add(
                        text(dependency, "groupId") + ":" + text(dependency, "artifactId"));
            }
        }
        assertEquals(List.of("io.paperagent.v2:agent-contracts"), productionDependencies);

        try (var sources = Files.walk(module.resolve("src/main/java"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(source)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("import ")) {
                        assertTrue(
                                trimmed.startsWith("import java.")
                                        || trimmed.startsWith("import static java.")
                                        || trimmed.startsWith(
                                                "import io.paperagent.v2.contracts."),
                                () -> "production import crosses module boundary: " + trimmed);
                    }
                }
            }
        }
    }

    @Test
    void materializationPublicSurfaceIsHardCutToSharedSpecAndVerifiedResult()
            throws Exception {
        Method materialize = WorkspacePort.class.getMethod(
                "materialize",
                WorkspaceMaterializationSpec.class);
        Method inspect = WorkspacePort.class.getMethod(
                "inspectMaterialization",
                WorkspaceMaterializationSpec.class);
        assertEquals(VerifiedWorkspaceMaterialization.class, materialize.getReturnType());
        assertEquals(VerifiedWorkspaceMaterialization.class, inspect.getReturnType());
        assertEquals(
                1,
                Arrays.stream(WorkspacePort.class.getMethods())
                        .filter(method -> method.getName().equals("materialize"))
                        .count());
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("io.paperagent.v2.workspace.WorkspaceLimits"));

        assertTrue(VerifiedWorkspaceMaterialization.class.isRecord());
        assertEquals(
                List.of(WorkspaceMaterializationSpec.class, ContentHash.class),
                Arrays.stream(VerifiedWorkspaceMaterialization.class.getRecordComponents())
                        .map(component -> component.getType())
                        .toList());
        assertEquals(
                WorkspaceRef.class,
                VerifiedWorkspaceMaterialization.class.getMethod("workspace").getReturnType());
    }

    private static Path moduleDirectory() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path direct = current.resolve("pom.xml");
        if (Files.isRegularFile(direct)
                && current.getFileName().toString().equals("agent-workspace")) {
            return current;
        }
        return current.resolve("agent-workspace");
    }

    private static String text(Element parent, String name) {
        var elements = parent.getElementsByTagName(name);
        return elements.getLength() == 0 ? "" : elements.item(0).getTextContent().trim();
    }
}
