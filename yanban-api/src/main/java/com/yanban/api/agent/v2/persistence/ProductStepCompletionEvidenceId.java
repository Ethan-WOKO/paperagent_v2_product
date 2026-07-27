package com.yanban.api.agent.v2.persistence;

import java.io.Serializable;
import java.util.Objects;

final class ProductStepCompletionEvidenceId implements Serializable {
    private String completionEventId;
    private int ordinal;

    ProductStepCompletionEvidenceId() {
    }

    ProductStepCompletionEvidenceId(String completionEventId, int ordinal) {
        this.completionEventId = completionEventId;
        this.ordinal = ordinal;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProductStepCompletionEvidenceId that
                && ordinal == that.ordinal
                && Objects.equals(completionEventId, that.completionEventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(completionEventId, ordinal);
    }
}
