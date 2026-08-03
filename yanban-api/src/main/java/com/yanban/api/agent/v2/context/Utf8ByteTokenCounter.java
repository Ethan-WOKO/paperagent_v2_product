package com.yanban.api.agent.v2.context;

import java.nio.charset.StandardCharsets;

public final class Utf8ByteTokenCounter implements VersionedTokenCounter {
    public static final String VERSION = "utf8-byte-v1";

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public long count(String value) {
        return value == null
                ? 0L
                : value.getBytes(StandardCharsets.UTF_8).length;
    }
}
