package com.yanban.api.agent.v2.chain.effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChainActionWorkspaceAuthorityTest {

    @Test
    void freezesCanonicalTypedChangesAndDigest() {
        var add = change(
                ChainActionWorkspaceAuthority.ChangeType.ADD,
                "new.txt", null, "new");
        var modify = change(
                ChainActionWorkspaceAuthority.ChangeType.MODIFY,
                "old.txt", sha256("old"), "changed");
        var delete = change(
                ChainActionWorkspaceAuthority.ChangeType.DELETE,
                "gone.txt", sha256("gone"), null);
        var base = new ChainActionWorkspaceAuthority.BaseCandidateAuthority(
                "c".repeat(64), "version.1", 7L,
                List.of(add, modify, delete));

        assertEquals(List.of(delete, add, modify), base.changes());
        assertEquals(64, base.overlayDigestSha256().length());
        assertNotEquals(base.overlayDigestSha256(),
                new ChainActionWorkspaceAuthority.BaseCandidateAuthority(
                        "c".repeat(64), "version.1", 7L,
                        List.of(add, change(
                                ChainActionWorkspaceAuthority.ChangeType.MODIFY,
                                "old.txt", sha256("old"), "other"),
                                delete)).overlayDigestSha256());
        assertThrows(UnsupportedOperationException.class,
                () -> base.changes().add(add));
    }

    @Test
    void noneAndTypedFieldCombinationsFailClosed() {
        assertEquals(List.of(),
                new ChainActionWorkspaceAuthority.BaseCandidateAuthority(
                        "NONE", "version.1", null, List.of()).changes());
        assertThrows(IllegalArgumentException.class, () ->
                new ChainActionWorkspaceAuthority.BaseCandidateAuthority(
                        "NONE", "version.1", 1L, List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new ChainActionWorkspaceAuthority.BaseCandidateAuthority(
                        "c".repeat(64), "version.1", 1L, List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new ChainActionWorkspaceAuthority.TypedChange(
                        ChainActionWorkspaceAuthority.ChangeType.ADD,
                        "new.txt", sha256("old"), sha256("new"), "new"));
        assertThrows(IllegalArgumentException.class, () ->
                new ChainActionWorkspaceAuthority.TypedChange(
                        ChainActionWorkspaceAuthority.ChangeType.MODIFY,
                        "old.txt", sha256("old"), sha256("wrong"), "new"));
        assertThrows(IllegalArgumentException.class, () ->
                new ChainActionWorkspaceAuthority.TypedChange(
                        ChainActionWorkspaceAuthority.ChangeType.DELETE,
                        "old.txt", sha256("old"), sha256("new"), "new"));
        assertThrows(IllegalArgumentException.class, () ->
                new ChainActionWorkspaceAuthority.BaseCandidateAuthority(
                        "c".repeat(64), "version.1", 1L,
                        List.of(
                                change(ChainActionWorkspaceAuthority.ChangeType.ADD,
                                        "A.txt", null, "a"),
                                change(ChainActionWorkspaceAuthority.ChangeType.ADD,
                                        "a.TXT", null, "b"))));
    }

    private static ChainActionWorkspaceAuthority.TypedChange change(
            ChainActionWorkspaceAuthority.ChangeType type,
            String path,
            String baseSha256,
            String text) {
        return new ChainActionWorkspaceAuthority.TypedChange(
                type, path, baseSha256,
                text == null ? null : sha256(text), text);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(
                    "SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
