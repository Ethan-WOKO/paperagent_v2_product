package com.yanban.api.agent.v2.chain.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainPersistenceRecords;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Verifies canonical JSON digests and reads only explicit string refs. */
final class ProductMemoryEvidenceRefParser {
    private final ObjectMapper json;

    ProductMemoryEvidenceRefParser(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    List<String> refs(ChainPersistenceRecords.CanonicalJson value) {
        verifyDigest(value);
        try {
            JsonNode root = json.readTree(value.json());
            if (!root.isArray()) throw blocked("evidence refs are not an array");
            List<String> result = new ArrayList<>();
            for (JsonNode item : root) {
                if (!item.isTextual() || item.textValue().isBlank()) {
                    throw blocked("evidence ref vector is malformed");
                }
                result.add(item.textValue());
            }
            if (result.stream().distinct().count() != result.size()) {
                throw blocked("evidence ref vector contains duplicates");
            }
            return List.copyOf(result);
        } catch (io.paperagent.v2.chain.context.ChainContextException typed) {
            throw typed;
        } catch (Exception invalid) {
            throw blocked("evidence ref vector cannot be verified");
        }
    }

    void verifyDigest(ChainPersistenceRecords.CanonicalJson value) {
        String actual = ProductChainContractProjectionCodec.sha256(value.json());
        if (!actual.equals(value.sha256())) {
            throw blocked("canonical evidence digest mismatches its body");
        }
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(
                ChainContextModule.MEMORY_RETRIEVAL_AND_KNOWLEDGE_EVIDENCE,
                reason);
    }
}
