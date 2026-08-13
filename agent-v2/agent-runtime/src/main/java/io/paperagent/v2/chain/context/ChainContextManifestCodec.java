package io.paperagent.v2.chain.context;

import io.paperagent.v2.chain.ChainPersistenceRecords.ContextModuleRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.FormattedJson;

import java.util.Comparator;
import java.util.List;

/** Format-1 codec kept byte-for-byte equivalent to the V71 product persistence gate. */
final class ChainContextManifestCodec {
    FormattedJson manifest(List<ContextModuleRecord> modules) {
        List<ContextModuleRecord> ordered = modules.stream()
                .sorted(Comparator.comparingInt(ContextModuleRecord::moduleOrdinal))
                .toList();
        verifyCompleteSet(ordered);
        StringBuilder json = new StringBuilder(4_096);
        json.append("{\"format\":1,\"modules\":[");
        for (int index = 0; index < ordered.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            ContextModuleRecord module = ordered.get(index);
            json.append("{\"ordinal\":").append(module.moduleOrdinal())
                    .append(",\"kind\":").append(quote(module.module().wireName()))
                    .append(",\"presence\":").append(quote(module.presenceKind().name()))
                    .append(",\"sourceVersionFormat\":").append(module.sourceVersion().formatVersion())
                    .append(",\"sourceVersionSha256\":").append(quote(module.sourceVersion().sha256()))
                    .append(",\"readBoundaryFormat\":").append(module.readBoundary().formatVersion())
                    .append(",\"readBoundarySha256\":").append(quote(module.readBoundary().sha256()))
                    .append(",\"projectionVersion\":").append(quote(module.projectionVersion()))
                    .append(",\"paginationVersion\":").append(quote(module.paginationVersion()))
                    .append(",\"projectionParametersFormat\":")
                    .append(module.projectionParameters().formatVersion())
                    .append(",\"projectionParametersSha256\":")
                    .append(quote(module.projectionParameters().sha256()))
                    .append(",\"projectionFormat\":").append(module.projection().formatVersion())
                    .append(",\"projectionDigest\":").append(quote(module.projection().sha256()))
                    .append('}');
        }
        json.append("]}");
        return new FormattedJson(1, json.toString());
    }

    /** Rebuilds the exact full model input; the persisted manifest remains digest-only. */
    String canonicalPrompt(List<ContextModuleRecord> modules) {
        List<ContextModuleRecord> ordered = modules.stream()
                .sorted(Comparator.comparingInt(ContextModuleRecord::moduleOrdinal)).toList();
        verifyCompleteSet(ordered);
        StringBuilder json = new StringBuilder(16_384);
        json.append("{\"format\":1,\"modules\":[");
        for (int index = 0; index < ordered.size(); index++) {
            if (index > 0) json.append(',');
            ContextModuleRecord module = ordered.get(index);
            json.append("{\"ordinal\":").append(module.moduleOrdinal())
                    .append(",\"kind\":").append(quote(module.module().wireName()))
                    .append(",\"presence\":").append(quote(module.presenceKind().name()))
                    .append(",\"sourceVersion\":").append(module.sourceVersion().json())
                    .append(",\"readBoundary\":").append(module.readBoundary().json())
                    .append(",\"projectionVersion\":").append(quote(module.projectionVersion()))
                    .append(",\"paginationVersion\":").append(quote(module.paginationVersion()))
                    .append(",\"projectionParameters\":")
                    .append(module.projectionParameters().json())
                    .append(",\"projection\":").append(module.projection().json())
                    .append('}');
        }
        return json.append("]}").toString();
    }

    void verifyCompleteSet(List<ContextModuleRecord> modules) {
        if (modules.size() != 13) {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_SOURCE_MODULE_SET_INVALID,
                    "a context revision must contain exactly thirteen modules");
        }
        for (int index = 0; index < modules.size(); index++) {
            ContextModuleRecord module = modules.get(index);
            int expectedOrdinal = index + 1;
            if (module.moduleOrdinal() != expectedOrdinal
                    || module.module().ordinalCode() != expectedOrdinal) {
                throw new ChainContextException(
                        ChainContextErrorCode.CONTEXT_SOURCE_MODULE_SET_INVALID,
                        "context modules must use the frozen ordinal order 1 through 13");
            }
        }
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
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
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }
}
