package com.yanban.api.project;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Server-attested Workspace change accepted by automatic revision publication. */
public record AutomaticProjectFileChange(
        String operation,
        String path,
        String beforeSha256,
        String afterSha256,
        String content,
        byte[] binaryContent,
        String mediaType,
        String attestationRef) {

    private static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    public AutomaticProjectFileChange(
            String operation, String path, String beforeSha256,
            String afterSha256, String content) {
        this(operation, path, beforeSha256, afterSha256, content,
                null, "text/plain; charset=utf-8", null);
    }

    public AutomaticProjectFileChange {
        binaryContent = binaryContent == null ? null : binaryContent.clone();
        byte[] bytes = content == null ? binaryContent
                : content.getBytes(StandardCharsets.UTF_8);
        boolean generatedDocx = content == null && binaryContent != null
                && "ADD".equals(operation) && beforeSha256 == null
                && path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".docx")
                && DOCX_MEDIA_TYPE.equals(mediaType)
                && ("docx-generation." + afterSha256).equals(attestationRef)
                && ProjectAssetAdmissionPolicy.admits(path, binaryContent);
        if (!((content != null && binaryContent == null) || generatedDocx)
                || !("ADD".equals(operation) || "MODIFY".equals(operation))
                || path == null || path.isBlank()
                || afterSha256 == null || !afterSha256.matches("[a-f0-9]{64}")
                || ("ADD".equals(operation) && beforeSha256 != null)
                || ("MODIFY".equals(operation)
                && (beforeSha256 == null || !beforeSha256.matches("[a-f0-9]{64}")))
                || !afterSha256.equals(hash(bytes))) {
            throw new IllegalArgumentException("automatic Project file change is invalid");
        }
    }

    public static AutomaticProjectFileChange generatedDocx(
            String operation, String path, String beforeSha256,
            String afterSha256, byte[] content, String mediaType,
            String attestationRef) {
        return new AutomaticProjectFileChange(operation, path, beforeSha256,
                afterSha256, null, content, mediaType, attestationRef);
    }

    @Override
    public byte[] binaryContent() {
        return binaryContent == null ? null : binaryContent.clone();
    }

    public byte[] bytes() {
        return content == null ? binaryContent() : content.getBytes(StandardCharsets.UTF_8);
    }

    public boolean serverGeneratedDocx() {
        return binaryContent != null;
    }

    private static String hash(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
