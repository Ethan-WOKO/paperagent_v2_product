package com.yanban.api.agent.v2.context;

public interface VersionedTokenCounter {
    String version();

    long count(String value);
}
