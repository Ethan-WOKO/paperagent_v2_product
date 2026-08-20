package com.yanban.knowledge.eval;

/** Immutable weighted reciprocal-rank-fusion parameters used by controlled evaluation. */
public record RrfConfiguration(double lexicalWeight, double vectorWeight, int rankConstant) {

    public static final RrfConfiguration EQUAL_WEIGHT_BASELINE = new RrfConfiguration(1.0d, 1.0d, 60);

    public RrfConfiguration {
        if (!Double.isFinite(lexicalWeight) || lexicalWeight < 0.0d
                || !Double.isFinite(vectorWeight) || vectorWeight < 0.0d
                || lexicalWeight + vectorWeight <= 0.0d) {
            throw new IllegalArgumentException("At least one finite non-negative RRF route weight is required");
        }
        if (rankConstant < 1) throw new IllegalArgumentException("RRF rank constant must be positive");
    }
}
