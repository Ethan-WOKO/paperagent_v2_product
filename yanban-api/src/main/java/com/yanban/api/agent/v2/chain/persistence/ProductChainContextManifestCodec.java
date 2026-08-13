package com.yanban.api.agent.v2.chain.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextModuleRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.FormattedJson;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/** Frozen format-1 manifest and prompt codec shared with the runtime gate. */
@Component
public final class ProductChainContextManifestCodec {
    public ProductChainContextManifestCodec(ObjectMapper json) {
        java.util.Objects.requireNonNull(json, "json");
    }

    public FormattedJson manifest(List<ContextModuleRecord> modules) {
        List<ContextModuleRecord> ordered = modules.stream()
                .sorted(Comparator.comparingInt(
                        ContextModuleRecord::moduleOrdinal))
                .toList();
        verifyCompleteSet(ordered);
        StringBuilder manifest = new StringBuilder(4_096);
        manifest.append("{\"format\":1,\"modules\":[");
        for (int index = 0; index < ordered.size(); index++) {
            if (index > 0) {
                manifest.append(',');
            }
            ContextModuleRecord module = ordered.get(index);
            manifest.append("{\"ordinal\":")
                    .append(module.moduleOrdinal())
                    .append(",\"kind\":")
                    .append(quote(module.module().wireName()))
                    .append(",\"presence\":")
                    .append(quote(module.presenceKind().name()))
                    .append(",\"sourceVersionFormat\":")
                    .append(module.sourceVersion().formatVersion())
                    .append(",\"sourceVersionSha256\":")
                    .append(quote(module.sourceVersion().sha256()))
                    .append(",\"readBoundaryFormat\":")
                    .append(module.readBoundary().formatVersion())
                    .append(",\"readBoundarySha256\":")
                    .append(quote(module.readBoundary().sha256()))
                    .append(",\"projectionVersion\":")
                    .append(quote(module.projectionVersion()))
                    .append(",\"paginationVersion\":")
                    .append(quote(module.paginationVersion()))
                    .append(",\"projectionParametersFormat\":")
                    .append(module.projectionParameters().formatVersion())
                    .append(",\"projectionParametersSha256\":")
                    .append(quote(module.projectionParameters().sha256()))
                    .append(",\"projectionFormat\":")
                    .append(module.projection().formatVersion())
                    .append(",\"projectionDigest\":")
                    .append(quote(module.projection().sha256()))
                    .append('}');
        }
        manifest.append("]}");
        return new FormattedJson(1, manifest.toString());
    }

    public String digest(FormattedJson manifest) {
        return ProductChainRecordCodec.sha256(manifest.json());
    }

    public String canonicalPrompt(List<ContextModuleRecord> modules) {
        List<ContextModuleRecord> ordered = modules.stream()
                .sorted(Comparator.comparingInt(
                        ContextModuleRecord::moduleOrdinal))
                .toList();
        verifyCompleteSet(ordered);
        StringBuilder prompt = new StringBuilder(16_384);
        prompt.append("{\"format\":1,\"modules\":[");
        for (int index = 0; index < ordered.size(); index++) {
            if (index > 0) {
                prompt.append(',');
            }
            ContextModuleRecord module = ordered.get(index);
            prompt.append("{\"ordinal\":")
                    .append(module.moduleOrdinal())
                    .append(",\"kind\":")
                    .append(quote(module.module().wireName()))
                    .append(",\"presence\":")
                    .append(quote(module.presenceKind().name()))
                    .append(",\"sourceVersion\":")
                    .append(module.sourceVersion().json())
                    .append(",\"readBoundary\":")
                    .append(module.readBoundary().json())
                    .append(",\"projectionVersion\":")
                    .append(quote(module.projectionVersion()))
                    .append(",\"paginationVersion\":")
                    .append(quote(module.paginationVersion()))
                    .append(",\"projectionParameters\":")
                    .append(module.projectionParameters().json())
                    .append(",\"projection\":")
                    .append(module.projection().json())
                    .append('}');
        }
        return prompt.append("]}").toString();
    }

    private static void verifyCompleteSet(
            List<ContextModuleRecord> modules) {
        if (modules.size() != 13) {
            throw new ProductChainPersistenceException(
                    "CHAIN_CONTEXT_MODULES_INCOMPLETE");
        }
        for (int index = 0; index < modules.size(); index++) {
            ContextModuleRecord module = modules.get(index);
            int expectedOrdinal = index + 1;
            if (module.moduleOrdinal() != expectedOrdinal
                    || module.module().ordinalCode() != expectedOrdinal) {
                throw new ProductChainPersistenceException(
                        "CHAIN_CONTEXT_MODULE_ORDER_INVALID");
            }
        }
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2)
                .append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(
                                "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }
}
