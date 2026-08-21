package com.yanban.knowledge.eval;

import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import com.yanban.knowledge.service.EmbeddingClient;
import com.yanban.knowledge.service.KnowledgeReranker;
import com.yanban.knowledge.service.KnowledgeSearchIndexClient;
import com.yanban.knowledge.service.KnowledgeSearchIndexHit;
import com.yanban.knowledge.service.KnowledgeSearchOptions;
import com.yanban.knowledge.service.KnowledgeSearchResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Controlled retrieval-only baseline. Every mode uses the same query, filters and candidate budget. */
public final class ElasticsearchRetrievalBaselineBackend implements BaselineSearchBackend {

    private static final int CANDIDATE_LIMIT = 50;
    private static final int RESULT_LIMIT = 50;

    private final RetrievalBaselineMode mode;
    private final KnowledgeSearchIndexClient index;
    private final EmbeddingClient embeddings;
    private final KbDocumentRepository documents;
    private final Function<Long, KnowledgeSearchResult> documentResolver;
    private final KnowledgeReranker reranker;
    private final CachedDashScopeReranker modelReranker;
    private final RrfConfiguration rrfConfiguration;
    private final RetrievalIntent retrievalIntent;
    private final ControlledQueryRewriter queryRewriter;
    private final double rewrittenRouteWeight;

    public ElasticsearchRetrievalBaselineBackend(
            RetrievalBaselineMode mode,
            KnowledgeSearchIndexClient index,
            EmbeddingClient embeddings,
            KbDocumentRepository documents) {
        this.mode = mode;
        this.index = index;
        this.embeddings = embeddings;
        this.documents = documents;
        this.documentResolver = null;
        this.reranker = new KnowledgeReranker();
        this.modelReranker = null;
        this.rrfConfiguration = RrfConfiguration.EQUAL_WEIGHT_BASELINE;
        this.retrievalIntent = RetrievalIntent.API_DEFAULT;
        this.queryRewriter = null;
        this.rewrittenRouteWeight = 0.0d;
    }

    public ElasticsearchRetrievalBaselineBackend(
            RetrievalBaselineMode mode,
            KnowledgeSearchIndexClient index,
            EmbeddingClient embeddings,
            Function<Long, KnowledgeSearchResult> documentResolver) {
        this.mode = mode;
        this.index = index;
        this.embeddings = embeddings;
        this.documents = null;
        this.documentResolver = documentResolver;
        this.reranker = new KnowledgeReranker();
        this.modelReranker = null;
        this.rrfConfiguration = RrfConfiguration.EQUAL_WEIGHT_BASELINE;
        this.retrievalIntent = RetrievalIntent.API_DEFAULT;
        this.queryRewriter = null;
        this.rewrittenRouteWeight = 0.0d;
    }

    public ElasticsearchRetrievalBaselineBackend(
            RetrievalBaselineMode mode,
            KnowledgeSearchIndexClient index,
            EmbeddingClient embeddings,
            Function<Long, KnowledgeSearchResult> documentResolver,
            CachedDashScopeReranker modelReranker) {
        this(mode, index, embeddings, documentResolver, modelReranker,
                RrfConfiguration.EQUAL_WEIGHT_BASELINE, RetrievalIntent.API_DEFAULT);
    }

    public ElasticsearchRetrievalBaselineBackend(
            RetrievalBaselineMode mode,
            KnowledgeSearchIndexClient index,
            EmbeddingClient embeddings,
            Function<Long, KnowledgeSearchResult> documentResolver,
            CachedDashScopeReranker modelReranker,
            RrfConfiguration rrfConfiguration,
            RetrievalIntent retrievalIntent) {
        this(mode, index, embeddings, documentResolver, modelReranker,
                rrfConfiguration, retrievalIntent, null, 0.0d);
    }

    public ElasticsearchRetrievalBaselineBackend(
            RetrievalBaselineMode mode,
            KnowledgeSearchIndexClient index,
            EmbeddingClient embeddings,
            Function<Long, KnowledgeSearchResult> documentResolver,
            CachedDashScopeReranker modelReranker,
            RrfConfiguration rrfConfiguration,
            RetrievalIntent retrievalIntent,
            ControlledQueryRewriter queryRewriter,
            double rewrittenRouteWeight) {
        if (!Double.isFinite(rewrittenRouteWeight) || rewrittenRouteWeight < 0.0d) {
            throw new IllegalArgumentException("Rewritten route weight must be finite and non-negative");
        }
        this.mode = mode;
        this.index = index;
        this.embeddings = embeddings;
        this.documents = null;
        this.documentResolver = documentResolver;
        this.reranker = new KnowledgeReranker();
        this.modelReranker = modelReranker;
        this.rrfConfiguration = rrfConfiguration;
        this.retrievalIntent = retrievalIntent;
        this.queryRewriter = queryRewriter;
        this.rewrittenRouteWeight = rewrittenRouteWeight;
    }

    @Override
    public List<BaselineRagHit> search(RagSpikeEvalCase evalCase) {
        KnowledgeSearchOptions options = new KnowledgeSearchOptions(
                evalCase.userId(), RESULT_LIMIT, evalCase.projectId(), false);
        List<KnowledgeSearchIndexHit> lexical = List.of();
        List<KnowledgeSearchIndexHit> vector = List.of();
        if (mode != RetrievalBaselineMode.KNN) {
            lexical = safeLexical(evalCase.query(), options);
        }
        if (mode != RetrievalBaselineMode.BM25) {
            vector = safeVector(evalCase.query(), options);
        }
        List<KnowledgeSearchIndexHit> ranked = switch (mode) {
            case BM25 -> lexical;
            case KNN -> vector;
            case RRF, RRF_RULE_RERANK, RRF_MODEL_RERANK_20, RRF_MODEL_RERANK_30,
                    RRF_MODEL_RERANK_40, RRF_MODEL_RERANK_50 ->
                    reciprocalRankFusion(evalCase.query(), lexical, vector, options);
        };
        List<KnowledgeSearchResult> hydrated = hydrate(ranked, options);
        if (mode == RetrievalBaselineMode.RRF_RULE_RERANK) {
            hydrated = reranker.rerank(evalCase.query(), hydrated, RESULT_LIMIT);
        }
        if (mode.requiresModelReranker()) {
            if (modelReranker == null) throw new IllegalStateException("Model reranker is required for " + mode);
            hydrated = modelReranker.rerank(
                    evalCase.query(), hydrated, mode.modelRerankCandidateLimit(), retrievalIntent);
        }
        return hydrated.stream().limit(RESULT_LIMIT).map(this::toHit).toList();
    }

    private List<KnowledgeSearchIndexHit> safeLexical(String query, KnowledgeSearchOptions options) {
        List<KnowledgeSearchIndexHit> hits = index.searchLexical(query, options, CANDIDATE_LIMIT);
        if (hits == null) throw new IllegalStateException("BM25 baseline returned null hits");
        return hits;
    }

    private List<KnowledgeSearchIndexHit> safeVector(String query, KnowledgeSearchOptions options) {
        List<KnowledgeSearchIndexHit> hits = index.searchVector(
                embeddings.embed(query), options, CANDIDATE_LIMIT);
        if (hits == null) throw new IllegalStateException("KNN baseline returned null hits");
        return hits;
    }

    private List<KnowledgeSearchIndexHit> reciprocalRankFusion(
            String originalQuery,
            List<KnowledgeSearchIndexHit> lexical,
            List<KnowledgeSearchIndexHit> vector,
            KnowledgeSearchOptions options) {
        Map<String, FusionCandidate> fused = new LinkedHashMap<>();
        addRoute(fused, lexical, rrfConfiguration.lexicalWeight());
        addRoute(fused, vector, rrfConfiguration.vectorWeight());
        if (queryRewriter != null && rewrittenRouteWeight > 0.0d) {
            List<String> rewrites = queryRewriter.rewrite(originalQuery);
            double perRewriteWeight = rewrites.isEmpty() ? 0.0d : rewrittenRouteWeight / rewrites.size();
            for (String rewrite : rewrites) {
                addRoute(fused, safeLexical(rewrite, options),
                        rrfConfiguration.lexicalWeight() * perRewriteWeight);
                addRoute(fused, safeVector(rewrite, options),
                        rrfConfiguration.vectorWeight() * perRewriteWeight);
            }
        }
        return fused.values().stream()
                .sorted(Comparator.comparingDouble(FusionCandidate::score).reversed())
                .limit(CANDIDATE_LIMIT)
                .map(candidate -> new KnowledgeSearchIndexHit(
                        candidate.hit().documentId(), candidate.hit().chunkIndex(),
                        candidate.hit().chunkText(), candidate.score()))
                .toList();
    }

    private void addRoute(
            Map<String, FusionCandidate> fused,
            List<KnowledgeSearchIndexHit> route,
            double weight) {
        if (weight == 0.0d) return;
        for (int rank = 0; rank < route.size(); rank++) {
            KnowledgeSearchIndexHit hit = route.get(rank);
            String key = hit.documentId() + ":" + hit.chunkIndex();
            double contribution = weight / (rrfConfiguration.rankConstant() + rank + 1.0d);
            fused.compute(key, (ignored, previous) -> previous == null
                    ? new FusionCandidate(hit, contribution)
                    : new FusionCandidate(previous.hit(), previous.score() + contribution));
        }
    }

    private List<KnowledgeSearchResult> hydrate(
            List<KnowledgeSearchIndexHit> hits,
            KnowledgeSearchOptions options) {
        List<KnowledgeSearchResult> results = new ArrayList<>();
        for (KnowledgeSearchIndexHit hit : hits) {
            if (documents != null) {
                KbDocument document = documents.findById(hit.documentId()).orElse(null);
                if (!allowed(document, options)) continue;
                results.add(new KnowledgeSearchResult(
                        document.getId(), document.getFilename(), hit.chunkIndex(), hit.chunkText(),
                        hit.retrievalScore(), Boolean.TRUE.equals(document.getIsPublic()),
                        document.getSourceType(), document.getVersionStatus(), document.getLineageId(),
                        document.getVersionNo(), document.getProjectId(), document.getCanonicalKey()));
                continue;
            }
            KnowledgeSearchResult document = documentResolver.apply(hit.documentId());
            if (!allowed(document, options)) continue;
            results.add(new KnowledgeSearchResult(
                    document.documentId(), document.filename(), hit.chunkIndex(), hit.chunkText(),
                    hit.retrievalScore(), document.isPublic(), document.sourceType(),
                    document.versionStatus(), document.lineageId(), document.versionNo(),
                    document.projectId(), document.canonicalKey()));
        }
        return results;
    }

    private boolean allowed(KbDocument document, KnowledgeSearchOptions options) {
        if (document == null || !"READY".equalsIgnoreCase(document.getStatus())) return false;
        boolean visible = Boolean.TRUE.equals(document.getIsPublic())
                || options.userId() != null && options.userId().equals(document.getUserId());
        boolean projectAllowed = options.projectId() == null || document.getProjectId() == null
                || options.projectId().equals(document.getProjectId());
        String version = document.getVersionStatus() == null
                ? "ACTIVE" : document.getVersionStatus().trim().toUpperCase(Locale.ROOT);
        return visible && projectAllowed && "ACTIVE".equals(version);
    }

    private boolean allowed(KnowledgeSearchResult document, KnowledgeSearchOptions options) {
        if (document == null) return false;
        boolean visible = document.isPublic();
        boolean projectAllowed = options.projectId() == null || document.projectId() == null
                || options.projectId().equals(document.projectId());
        String version = document.versionStatus() == null
                ? "ACTIVE" : document.versionStatus().trim().toUpperCase(Locale.ROOT);
        return visible && projectAllowed && "ACTIVE".equals(version);
    }

    private BaselineRagHit toHit(KnowledgeSearchResult result) {
        double score = result.rerankScore() == null ? result.score() : result.rerankScore();
        return new BaselineRagHit(
                result.documentId(), result.filename(), result.chunkIndex(), result.chunkText(), score,
                result.citationId(), result.source(), result.versionStatus(),
                result.isPublic() ? "PUBLIC" : "PRIVATE");
    }

    private record FusionCandidate(KnowledgeSearchIndexHit hit, double score) {
    }
}
