package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.ProjectPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.paperagent.v2.workspace.WorkspaceTestSupport.VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceManifestFingerprintTest {
    @TempDir
    Path root;

    @Test
    void canonicalEncodingIgnoresListAndMapIterationOrder() {
        ProjectFileSnapshot alpha = file("alpha.txt", "alpha", ordered(
                "zeta", "last",
                "alpha", "first"));
        ProjectFileSnapshot beta = file("nested/beta.txt", "beta", ordered(
                "two", "2",
                "one", "1"));
        ProjectVersionSnapshot first = new ProjectVersionSnapshot(
                VERSION,
                List.of(beta, alpha),
                ordered("zeta", "last", "alpha", "first"));
        ProjectVersionSnapshot reordered = new ProjectVersionSnapshot(
                VERSION,
                List.of(alpha, beta),
                ordered("alpha", "first", "zeta", "last"));

        assertEquals(
                WorkspaceManifestFingerprint.calculate(first),
                WorkspaceManifestFingerprint.calculate(reordered));
    }

    @Test
    void everyAuthoritativeManifestComponentChangesFingerprint() {
        ProjectVersionSnapshot baseline = snapshot(
                List.of(file("paper.txt", "paper", Map.of("kind", "text"))),
                Map.of("source", "fixture"));
        ContentHash expected = WorkspaceManifestFingerprint.calculate(baseline);

        assertNotEquals(expected, fingerprint(snapshot(
                List.of(file("renamed.txt", "paper", Map.of("kind", "text"))),
                Map.of("source", "fixture"))));
        assertNotEquals(expected, fingerprint(snapshot(
                List.of(file("paper.txt", "paper!", Map.of("kind", "text"))),
                Map.of("source", "fixture"))));
        assertNotEquals(expected, fingerprint(snapshot(
                List.of(file("paper.txt", "paper", Map.of("kind", "binary"))),
                Map.of("source", "fixture"))));
        assertNotEquals(expected, fingerprint(snapshot(
                List.of(file("paper.txt", "paper", Map.of("kind", "text"))),
                Map.of("source", "changed"))));
        assertNotEquals(expected, fingerprint(snapshot(
                List.of(
                        file("paper.txt", "paper", Map.of("kind", "text")),
                        file("second.txt", "", Map.of())),
                Map.of("source", "fixture"))));
        assertNotEquals(expected, WorkspaceManifestFingerprint.calculate(
                new ProjectVersionSnapshot(
                        new io.paperagent.v2.contracts.ProjectVersionRef(
                                "project-1",
                                "version-2"),
                        baseline.files(),
                        baseline.metadata())));
    }

    @Test
    void emptyManifestHasStableVersionedFingerprint() {
        ProjectVersionSnapshot empty = snapshot(List.of(), Map.of());

        /*
         * Independently checked bytes are: length-prefixed v1 domain,
         * "project-1", "version-1", then big-endian zero snapshot-metadata
         * entry count and zero file count.
         */
        assertEquals(
                new ContentHash(
                        "sha256",
                        "85ca9adb9853c4afde5efcd48660e2ceaaa1425b12d6f8ca2986a901723e878b"),
                WorkspaceManifestFingerprint.calculate(empty));
    }

    @Test
    void unpairedHighAndLowSurrogatesInPathsFailBeforeAnyWrite() {
        assertMalformedPathRejected("\uD800.txt", "path-high");
        assertMalformedPathRejected("\uDC00.txt", "path-low");
    }

    @Test
    void unpairedHighAndLowSurrogatesInSnapshotMetadataFailBeforeAnyWrite() {
        assertMalformedMetadataRejected(
                snapshot(
                        List.of(file("paper.txt", "paper", Map.of())),
                        Map.of("key", "\uD800")),
                "snapshot-high");
        assertMalformedMetadataRejected(
                snapshot(
                        List.of(file("paper.txt", "paper", Map.of())),
                        Map.of("\uDC00", "value")),
                "snapshot-low");
    }

    @Test
    void unpairedHighAndLowSurrogatesInFileMetadataFailBeforeAnyWrite() {
        assertMalformedMetadataRejected(
                snapshot(
                        List.of(file(
                                "paper.txt",
                                "paper",
                                Map.of("key", "\uD800"))),
                        Map.of()),
                "file-high");
        assertMalformedMetadataRejected(
                snapshot(
                        List.of(file(
                                "paper.txt",
                                "paper",
                                Map.of("\uDC00", "value"))),
                        Map.of()),
                "file-low");
    }

    @Test
    void supplementaryCodePointsRemainStableAndOrderIndependent()
            throws Exception {
        String supplementary = "\uD83D\uDCC4";
        ProjectFileSnapshot alpha = file(
                supplementary + "-alpha.txt",
                "alpha",
                ordered(
                        supplementary + "-zeta", "last-" + supplementary,
                        "alpha", "first"));
        ProjectFileSnapshot beta = file(
                "nested/" + supplementary + "-beta.txt",
                "beta",
                ordered(
                        "two", supplementary + "-2",
                        supplementary + "-one", "1"));
        ProjectVersionSnapshot first = snapshot(
                List.of(beta, alpha),
                ordered(
                        supplementary + "-zeta", "last",
                        "alpha", supplementary + "-first"));
        ProjectVersionSnapshot reordered = snapshot(
                List.of(alpha, beta),
                ordered(
                        "alpha", supplementary + "-first",
                        supplementary + "-zeta", "last"));

        assertEquals(
                WorkspaceManifestFingerprint.calculate(first),
                WorkspaceManifestFingerprint.calculate(reordered));

        Path providerRoot = root.resolve("supplementary");
        LocalWorkspaceProvider provider =
                new LocalWorkspaceProvider(providerRoot, ignored -> first);
        VerifiedWorkspaceMaterialization materialization =
                provider.materialize(
                        WorkspaceTestSupport.spec("supplementary"));
        assertEquals(2, provider.list(materialization.workspace()).size());
        provider.cleanup(materialization.workspace());
        try (var entries = Files.list(providerRoot)) {
            assertTrue(entries.findAny().isEmpty());
        }
    }

    private void assertMalformedPathRejected(
            String path,
            String workspaceId) {
        ProjectVersionSnapshot invalid = snapshot(
                List.of(file(path, "paper", Map.of())),
                Map.of());
        assertRejectedBeforeWrite(
                WorkspaceErrorCode.PATH_COLLISION,
                invalid,
                workspaceId);
    }

    private void assertMalformedMetadataRejected(
            ProjectVersionSnapshot invalid,
            String workspaceId) {
        assertRejectedBeforeWrite(
                WorkspaceErrorCode.INVALID_METADATA,
                invalid,
                workspaceId);
    }

    private void assertRejectedBeforeWrite(
            WorkspaceErrorCode expected,
            ProjectVersionSnapshot invalid,
            String workspaceId) {
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger publishes = new AtomicInteger();
        Path providerRoot = root.resolve(workspaceId);
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                providerRoot,
                ignored -> invalid,
                (target, content, options) -> {
                    writes.incrementAndGet();
                    Files.write(target, content, options);
                },
                (pending, target) -> {
                    publishes.incrementAndGet();
                    LocalWorkspaceProvider.defaultPublish(pending, target);
                },
                LocalWorkspaceProvider::deleteTree);

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> provider.materialize(
                        WorkspaceTestSupport.spec(workspaceId)));

        assertEquals(expected, failure.code());
        assertEquals(0, writes.get());
        assertEquals(0, publishes.get());
        assertEquals(null, failure.getCause());
    }

    private static ContentHash fingerprint(ProjectVersionSnapshot snapshot) {
        return WorkspaceManifestFingerprint.calculate(snapshot);
    }

    private static ProjectVersionSnapshot snapshot(
            List<ProjectFileSnapshot> files,
            Map<String, String> metadata) {
        return new ProjectVersionSnapshot(VERSION, files, metadata);
    }

    private static ProjectFileSnapshot file(
            String path,
            String content,
            Map<String, String> metadata) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new ProjectFileSnapshot(
                new ProjectPath(path),
                bytes,
                WorkspaceHashes.sha256(bytes),
                metadata);
    }

    private static Map<String, String> ordered(String... keysAndValues) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            result.put(keysAndValues[index], keysAndValues[index + 1]);
        }
        return result;
    }
}
