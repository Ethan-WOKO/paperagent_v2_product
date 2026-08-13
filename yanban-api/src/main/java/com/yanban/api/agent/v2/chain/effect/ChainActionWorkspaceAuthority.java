package com.yanban.api.agent.v2.chain.effect;

import com.yanban.core.research.ProjectRelativePath;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Exact, non-persistent authority used to reconstruct one chain action Workspace. */
public record ChainActionWorkspaceAuthority(
        String actionId,
        String versionFenceSha256,
        String workspaceId,
        List<String> readScopes,
        List<String> writeScopes,
        BaseCandidateAuthority baseCandidate) {

    public ChainActionWorkspaceAuthority {
        requireText(actionId, "actionId");
        requireSha256(versionFenceSha256, "versionFenceSha256");
        requireText(workspaceId, "workspaceId");
        readScopes = normalizedScopes(readScopes, "readScopes");
        writeScopes = normalizedScopes(writeScopes, "writeScopes");
        if (readScopes.isEmpty() && writeScopes.isEmpty()) {
            throw new IllegalArgumentException(
                    "chain action Workspace scopes are empty");
        }
        if (baseCandidate == null) {
            throw new IllegalArgumentException("baseCandidate is required");
        }
    }

    public record BaseCandidateAuthority(
            String candidateIdentity,
            String baseProjectVersion,
            Long artifactId,
            List<TypedChange> changes) {

        public BaseCandidateAuthority {
            requireText(candidateIdentity, "candidateIdentity");
            requireText(baseProjectVersion, "baseProjectVersion");
            if (changes == null) {
                throw new IllegalArgumentException(
                        "base Candidate changes are required");
            }
            changes = changes.stream()
                    .sorted(Comparator.comparing(TypedChange::path))
                    .toList();
            LinkedHashSet<String> foldedPaths = new LinkedHashSet<>();
            for (TypedChange change : changes) {
                if (change == null || !foldedPaths.add(
                        change.path().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException(
                            "base Candidate changes are invalid");
                }
            }
            boolean none = "NONE".equals(candidateIdentity);
            if (none != (artifactId == null)
                    || none != changes.isEmpty()
                    || !none && (artifactId < 1
                    || !candidateIdentity.matches("[a-f0-9]{64}"))) {
                throw new IllegalArgumentException(
                        "base Candidate identity and overlay disagree");
            }
        }

        public String overlayDigestSha256() {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                for (TypedChange change : changes) {
                    update(digest, change.type().name());
                    update(digest, change.path());
                    update(digest, change.baseSha256());
                    update(digest, change.resultSha256());
                    update(digest, change.text());
                }
                return HexFormat.of().formatHex(digest.digest());
            } catch (Exception impossible) {
                throw new IllegalStateException(
                        "SHA-256 is unavailable", impossible);
            }
        }

        private static void update(MessageDigest digest, String value) {
            if (value == null) {
                digest.update(ByteBuffer.allocate(Integer.BYTES)
                        .putInt(-1).array());
                return;
            }
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(bytes.length).array());
            digest.update(bytes);
        }
    }

    public enum ChangeType { ADD, MODIFY, DELETE }

    public record TypedChange(
            ChangeType type,
            String path,
            String baseSha256,
            String resultSha256,
            String text) {
        public TypedChange {
            if (type == null || path == null
                    || !new ProjectRelativePath(path).value().equals(path)) {
                throw new IllegalArgumentException(
                        "typed Candidate change path is invalid");
            }
            boolean baseRequired = type != ChangeType.ADD;
            boolean resultRequired = type != ChangeType.DELETE;
            boolean textRequired = type != ChangeType.DELETE;
            if (baseRequired != isSha256(baseSha256)
                    || resultRequired != isSha256(resultSha256)
                    || textRequired != (text != null)
                    || text != null && !sha256(text).equals(resultSha256)) {
                throw new IllegalArgumentException(
                        "typed Candidate change fields disagree");
            }
        }
    }

    private static List<String> normalizedScopes(
            List<String> scopes, String name) {
        if (scopes == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        LinkedHashSet<String> folded = new LinkedHashSet<>();
        try {
            for (String scope : scopes) {
                if (scope == null
                        || !new ProjectRelativePath(scope).value().equals(
                                scope)
                        || !normalized.add(scope)
                        || !folded.add(scope.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException(name + " is invalid");
                }
            }
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return List.copyOf(normalized);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static void requireSha256(String value, String name) {
        if (!isSha256(value)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(
                    "SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
