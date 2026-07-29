package com.yanban.api.agent.v2.compatibility;

import java.util.List;

public record V2ProductAvailabilityDocument(
        int formatVersion,
        boolean enabled,
        List<String> capabilities) {

    public V2ProductAvailabilityDocument {
        capabilities = List.copyOf(capabilities);
    }
}
