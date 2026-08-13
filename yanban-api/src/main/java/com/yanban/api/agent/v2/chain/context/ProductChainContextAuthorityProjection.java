package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.context.ChainContextValue;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Typed values read from one product authority before runtime encoding. */
public record ProductChainContextAuthorityProjection(
        ChainContextModuleStatus presenceKind,
        Map<String, ChainContextValue> sourceVersionComponents,
        Map<String, ChainContextValue> readBoundaryComponents,
        String projectionVersion,
        String paginationVersion,
        Map<String, ChainContextValue> projectionParameters,
        Map<String, ChainContextValue> projectionFields,
        String emptyWatermark) {
    public ProductChainContextAuthorityProjection {
        Objects.requireNonNull(presenceKind, "presenceKind");
        sourceVersionComponents = copy(
                sourceVersionComponents, "sourceVersionComponents");
        readBoundaryComponents = copy(
                readBoundaryComponents, "readBoundaryComponents");
        projectionVersion = required(
                projectionVersion, "projectionVersion");
        paginationVersion = required(
                paginationVersion, "paginationVersion");
        projectionParameters = copy(
                projectionParameters, "projectionParameters");
        projectionFields = copy(projectionFields, "projectionFields");
    }

    private static Map<String, ChainContextValue> copy(
            Map<String, ChainContextValue> source,
            String name) {
        Objects.requireNonNull(source, name);
        TreeMap<String, ChainContextValue> result = new TreeMap<>();
        source.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException(
                        name + " keys must not be blank");
            }
            result.put(key, Objects.requireNonNull(
                    value, name + " value"));
        });
        return Map.copyOf(result);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
