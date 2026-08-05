package com.yanban.api.agent.sandbox;

import io.paperagent.v2.contracts.ArtifactRef;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Stable proof that a sandbox Receipt executed one exact text-file set. */
public final class V2SandboxInputFingerprint {
    private static final String PREFIX = "sandbox-inputs:";

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
}
