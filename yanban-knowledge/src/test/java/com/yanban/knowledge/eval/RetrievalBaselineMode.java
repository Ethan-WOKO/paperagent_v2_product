package com.yanban.knowledge.eval;

public enum RetrievalBaselineMode {
    BM25,
    KNN,
    RRF,
    RRF_RULE_RERANK,
    RRF_MODEL_RERANK_20,
    RRF_MODEL_RERANK_30,
    RRF_MODEL_RERANK_40,
    RRF_MODEL_RERANK_50;

    public boolean requiresModelReranker() {
        return switch (this) {
            case RRF_MODEL_RERANK_20, RRF_MODEL_RERANK_30,
                    RRF_MODEL_RERANK_40, RRF_MODEL_RERANK_50 -> true;
            default -> false;
        };
    }

    public int modelRerankCandidateLimit() {
        return switch (this) {
            case RRF_MODEL_RERANK_20 -> 20;
            case RRF_MODEL_RERANK_30 -> 30;
            case RRF_MODEL_RERANK_40 -> 40;
            case RRF_MODEL_RERANK_50 -> 50;
            default -> throw new IllegalStateException(name() + " does not use a model reranker");
        };
    }
}
