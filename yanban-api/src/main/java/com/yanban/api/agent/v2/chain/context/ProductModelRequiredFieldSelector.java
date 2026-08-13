package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.context.ChainContextValue;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Selects only fields with an implemented model-context projection. */
final class ProductModelRequiredFieldSelector {
    private ProductModelRequiredFieldSelector() {
    }

    static Map<String, ChainContextValue> select(
            List<String> requiredFields,
            Map<String, ChainContextValue> available) {
        Map<String, ChainContextValue> result = new TreeMap<>();
        for (String field : requiredFields) {
            ChainContextValue value = available.get(field);
            if (value == null) {
                throw ProductChainContextProjectionSupport.blocked(
                        ChainContextModule.MODEL_INVOCATIONS_AND_PROPOSALS,
                        "required model field has no formal projector: "
                                + field);
            }
            result.put(field, value);
        }
        return result;
    }
}
