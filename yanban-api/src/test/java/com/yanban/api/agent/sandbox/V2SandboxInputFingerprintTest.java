package com.yanban.api.agent.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V2SandboxInputFingerprintTest {

    @Test
    void v1RemainsStableAndOrderIndependent() {
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("b.txt", "b");
        reversed.put("a.txt", "a");

        assertThat(V2SandboxInputFingerprint.digest(reversed))
                .isEqualTo(V2SandboxInputFingerprint.digest(Map.of(
                        "a.txt", "a", "b.txt", "b")));
        assertThat(V2SandboxInputFingerprint.artifactReference(reversed)
                .value()).startsWith("sandbox-inputs:");
    }

    @Test
    void v2BindsPresentTextAndAbsentTombstonesInStablePathOrder() {
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("z.txt", "complete text");
        reversed.put("a.txt", "another text");
        String digest = V2SandboxInputFingerprint.stateDigest(
                reversed, Set.of("gone.txt"));

        assertThat(digest).hasSize(64).isEqualTo(
                V2SandboxInputFingerprint.stateDigest(
                        Map.of("a.txt", "another text",
                                "z.txt", "complete text"),
                        Set.of("gone.txt")));
        assertThat(digest).isNotEqualTo(
                V2SandboxInputFingerprint.stateDigest(
                        Map.of("a.txt", "another text",
                                "z.txt", "changed text"),
                        Set.of("gone.txt")));
        assertThat(V2SandboxInputFingerprint.stateArtifactReference(
                reversed, Set.of("gone.txt")).value())
                .isEqualTo("sandbox-input-states-v2:" + digest);
        assertThat(V2SandboxInputFingerprint.stateDigest(
                Map.of(), Set.of("gone.txt"))).hasSize(64);
    }

    @Test
    void v2RejectsEmptyNoncanonicalAndCaseFoldConflictingStates() {
        assertThatThrownBy(() -> V2SandboxInputFingerprint.stateDigest(
                Map.of(), Set.of())).isInstanceOf(
                IllegalArgumentException.class);
        assertThatThrownBy(() -> V2SandboxInputFingerprint.stateDigest(
                Map.of("folder/../file.txt", "text"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V2SandboxInputFingerprint.stateDigest(
                Map.of("File.txt", "text"), Set.of("file.TXT")))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, String> duplicate = new LinkedHashMap<>();
        duplicate.put("File.txt", "one");
        duplicate.put("file.TXT", "two");
        assertThatThrownBy(() -> V2SandboxInputFingerprint.stateDigest(
                duplicate, Set.of())).isInstanceOf(
                IllegalArgumentException.class);
    }
}
