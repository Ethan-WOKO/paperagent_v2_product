package com.yanban.api.agent.sandbox;

import io.paperagent.v2.contracts.ArtifactRef;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Stable proof that a sandbox Receipt executed one exact text-file set. */
public final class V2SandboxInputFingerprint {
    private static final String PREFIX = "sandbox-inputs:";
    private static final String STATE_PREFIX = "sandbox-input-states-v2:";

    private V2SandboxInputFingerprint() {
    }

    public static ArtifactRef artifactReference(Map<String, String> files) {
        return new ArtifactRef(PREFIX + digest(files));
    }

    public static String digest(Map<String, String> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException(
                    "sandbox input files are required");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (var entry : new TreeMap<>(files).entrySet()) {
                byte[] path = entry.getKey().getBytes(StandardCharsets.UTF_8);
                byte[] content = entry.getValue()
                        .getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES)
                        .putInt(path.length).array());
                digest.update(path);
                digest.update(ByteBuffer.allocate(Integer.BYTES)
                        .putInt(content.length).array());
                digest.update(content);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static ArtifactRef stateArtifactReference(
            Map<String, String> presentFiles,
            Set<String> absentPaths) {
        return new ArtifactRef(STATE_PREFIX + stateDigest(
                presentFiles, absentPaths));
    }

    public static String stateDigest(
            Map<String, String> presentFiles,
            Set<String> absentPaths) {
        State state = state(presentFiles, absentPaths);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String path : state.paths()) {
                update(digest, path);
                if (state.presentFiles().containsKey(path)) {
                    update(digest, "PRESENT");
                    update(digest, state.presentFiles().get(path));
                } else {
                    update(digest, "ABSENT");
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static State state(
            Map<String, String> presentFiles,
            Set<String> absentPaths) {
        if (presentFiles == null || absentPaths == null
                || presentFiles.isEmpty() && absentPaths.isEmpty()) {
            throw new IllegalArgumentException(
                    "sandbox input states are required");
        }
        Map<String, String> present = new LinkedHashMap<>();
        Set<String> absent = new LinkedHashSet<>();
        Set<String> folded = new LinkedHashSet<>();
        presentFiles.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String path = canonicalPath(entry.getKey());
                    if (entry.getValue() == null
                            || !folded.add(path.toLowerCase(Locale.ROOT))) {
                        throw new IllegalArgumentException(
                                "sandbox input states conflict");
                    }
                    present.put(path, entry.getValue());
                });
        absentPaths.stream().sorted().forEach(raw -> {
            String path = canonicalPath(raw);
            if (!folded.add(path.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "sandbox input states conflict");
            }
            absent.add(path);
        });
        return new State(
                Map.copyOf(present), Set.copyOf(absent),
                java.util.stream.Stream.concat(
                                present.keySet().stream(), absent.stream())
                        .sorted().toList());
    }

    private static String canonicalPath(String raw) {
        try {
            String path = new io.paperagent.v2.contracts.ProjectPath(raw)
                    .value();
            if (!path.equals(raw)) {
                throw new IllegalArgumentException(
                        "sandbox input state path is not canonical");
            }
            return path;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "sandbox input state path is not canonical");
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(bytes.length).array());
        digest.update(bytes);
    }

    private record State(
            Map<String, String> presentFiles,
            Set<String> absentPaths,
            java.util.List<String> paths) {
    }
}
