package com.yanban.api.project;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Server-attested UTF-8 Workspace change accepted by automatic revision publication. */
public record AutomaticProjectFileChange(
        String operation,
        String path,
        String beforeSha256,
        String afterSha256,
        String content) {

    public AutomaticProjectFileChange {
        if (!("ADD".equals(operation) || "MODIFY".equals(operation))
                || path == null || path.isBlank() || content == null
                || afterSha256 == null || !afterSha256.matches("[a-f0-9]{64}")
                || ("ADD".equals(operation) && beforeSha256 != null)
                || ("MODIFY".equals(operation)
                && (beforeSha256 == null || !beforeSha256.matches("[a-f0-9]{64}")))
                || !afterSha256.equals(hash(content))) {
            throw new IllegalArgumentException("automatic Project file change is invalid");
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
