package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.finalization.ChainFinalizationAuthorityPort;

/** Maps retained product Validation status into the stable V2 status domain. */
final class ProductValidationStatus {
    private ProductValidationStatus() {
    }

    static ChainFinalizationAuthorityPort.Validation.Status inspection(
            String status) {
        if ("SUCCEEDED".equals(status)) {
            return ChainFinalizationAuthorityPort.Validation.Status.SUCCESSFUL;
        }
        if ("FAILED".equals(status) || "REJECTED".equals(status)
                || "CANCELLED".equals(status)) {
            return ChainFinalizationAuthorityPort.Validation.Status.FAILED;
        }
        return ChainFinalizationAuthorityPort.Validation.Status.IN_PROGRESS;
    }
}
