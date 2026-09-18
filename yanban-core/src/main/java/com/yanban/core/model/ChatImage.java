package com.yanban.core.model;

/** Inline pixels only; arbitrary remote URLs are intentionally not accepted. */
public record ChatImage(String mimeType, String base64) {
    public ChatImage {
        if (!java.util.Set.of("image/png", "image/jpeg").contains(mimeType)
                || base64 == null || base64.isBlank()) throw new IllegalArgumentException("Invalid inline image");
    }
    public String dataUrl() { return "data:" + mimeType + ";base64," + base64; }
    @Override public String toString() { return "ChatImage[mimeType=" + mimeType + ", bytes=redacted]"; }
}
