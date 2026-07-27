package com.yanban.api.agent.v2.persistence;

import java.io.Serializable;
import java.util.Objects;

class ProductLeaseId implements Serializable {
    private String planId;
    private long fencingToken;

    protected ProductLeaseId() {
    }

    ProductLeaseId(String planId, long fencingToken) {
        this.planId = planId;
        this.fencingToken = fencingToken;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProductLeaseId that)) {
            return false;
        }
        return fencingToken == that.fencingToken
                && Objects.equals(planId, that.planId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(planId, fencingToken);
    }
}
