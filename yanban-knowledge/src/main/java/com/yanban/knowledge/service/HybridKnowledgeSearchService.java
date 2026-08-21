package com.yanban.knowledge.service;

import com.yanban.knowledge.config.KnowledgeRetrievalProperties;
import com.yanban.knowledge.domain.KbDocument;
import com.yanban.knowledge.domain.KbDocumentRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Primary
@Service
public class HybridKnowledgeSearchService implements KnowledgeSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridKnowledgeSearchService.class);
    private static final KnowledgeModelReranker NO_MODEL_RERANKER = new KnowledgeModelReranker() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public List<KnowledgeSearchResult> rerank(
                String query, List<KnowledgeSearchResult> candidates, int topK) {
            return candidates.stream().limit(topK).toList();
        }
    };

    private final EmbeddingClient embeddingClient;
    private final KnowledgeSearchIndexClient indexClient;
    private final KbDocumentRepository documents;
    private final SimpleKnowledgeSearchService fallbackSearchService;
    private final KnowledgeRetrievalProperties retrievalProperties;
    private final KnowledgeModelReranker modelReranker;

    public HybridKnowledgeSearchService(EmbeddingClient embeddingClient,
                                        KnowledgeSearchIndexClient indexClient,
                                        KbDocumentRepository documents,
                                        SimpleKnowledgeSearchService fallbackSearchService) {
        this(embeddingClient, indexClient, documents, fallbackSearchService,
                new KnowledgeRetrievalProperties(), NO_MODEL_RERANKER);
    }

    @Autowired
    public HybridKnowledgeSearchService(EmbeddingClient embeddingClient,
                                        KnowledgeSearchIndexClient indexClient,
                                        KbDocumentRepository documents,
                                        SimpleKnowledgeSearchService fallbackSearchService,
                                        KnowledgeRetrievalProperties retrievalProperties,
                                        KnowledgeModelReranker modelReranker) {
        this.embeddingClient = embeddingClient;
        this.indexClient = indexClient;
        this.documents = documents;
        this.fallbackSearchService = fallbackSearchService;
        this.retrievalProperties = retrievalProperties;
        this.modelReranker = modelReranker;
    }

    @Override
    public List<KnowledgeSearchResult> search(String query, Long userId, int topK) {
        return search(query, KnowledgeSearchOptions.activeOnly(userId, topK));
    }

    @Override
    public List<KnowledgeSearchResult> search(String query, KnowledgeSearchOptions options) {
        if (!StringUtils.hasText(query) || options == null || options.topK() <= 0) {
            return List.of();
        }
        int topK = options.topK();
        int candidateLimit = Math.max(topK, retrievalProperties.getCandidateLimit());
        RouteHits lexical = searchLexicalVariants(query, options, candidateLimit);
        RouteHits vector = searchVector(query, options, candidateLimit);
        List<KnowledgeSearchIndexHit> hits = reciprocalRankFusion(lexical.hits(), vector.hits(), candidateLimit);
        if (hits.isEmpty()) {
            return fallbackSearchService.search(query, options);
        }
        List<KnowledgeSearchResult> results = toResults(hits, options);
        if (results.isEmpty()) return fallbackSearchService.search(query, options);
        if (!modelReranker.available()) return results.stream().limit(topK).toList();
        try {
            List<KnowledgeSearchResult> reranked = modelReranker.rerank(query.trim(), results, topK);
            if (reranked == null) throw new IllegalStateException("Model reranker returned null");
            return reranked;
        } catch (RuntimeException failure) {
            log.warn("Knowledge model rerank failed; using weighted RRF fallback: {}",
                    failure.getClass().getSimpleName());
            return results.stream().limit(topK).toList();
        }
    }

    private RouteHits searchLexicalVariants(String query,
                                            KnowledgeSearchOptions options,
                                            int candidateLimit) {
        Map<String, KnowledgeSearchIndexHit> deduped = new LinkedHashMap<>();
        for (String variant : KnowledgeQueryVariants.expand(query)) {
            List<KnowledgeSearchIndexHit> variantHits;
            try {
                variantHits = indexClient.searchLexical(variant, options, candidateLimit);
            } catch (Exception ex) {
                break;
            }
            if (variantHits == null) {
                continue;
            }
            for (KnowledgeSearchIndexHit hit : variantHits) {
                String key = hit.documentId() + ":" + hit.chunkIndex();
                KnowledgeSearchIndexHit previous = deduped.get(key);
                if (previous == null || hit.retrievalScore() > previous.retrievalScore()) {
                    deduped.put(key, hit);
                }
            }
        }
        List<KnowledgeSearchIndexHit> ranked = deduped.values().stream()
                .sorted(Comparator.comparingDouble(KnowledgeSearchIndexHit::retrievalScore).reversed())
                .limit(candidateLimit)
                .toList();
        return new RouteHits(ranked);
    }

    private RouteHits searchVector(String query,
                                   KnowledgeSearchOptions options,
                                   int candidateLimit) {
        try {
            List<Double> queryVector = embeddingClient.embed(query.trim());
            List<KnowledgeSearchIndexHit> hits = indexClient.searchVector(queryVector, options, candidateLimit);
            return new RouteHits(hits == null ? List.of() : hits);
        } catch (Exception ex) {
            return new RouteHits(List.of());
        }
    }

    private List<KnowledgeSearchIndexHit> reciprocalRankFusion(List<KnowledgeSearchIndexHit> lexical,
                                                               List<KnowledgeSearchIndexHit> vector,
                                                               int candidateLimit) {
        Map<String, FusionCandidate> fused = new LinkedHashMap<>();
        addRankedRoute(fused, lexical, retrievalProperties.getLexicalWeight());
        addRankedRoute(fused, vector, retrievalProperties.getVectorWeight());
        double scale = retrievalProperties.getRrfRankConstant() + 1.0d;
        return fused.values().stream()
                .sorted(Comparator.comparingDouble(FusionCandidate::score).reversed())
                .limit(candidateLimit)
                .map(candidate -> new KnowledgeSearchIndexHit(
                        candidate.hit().documentId(),
                        candidate.hit().chunkIndex(),
                        candidate.hit().chunkText(),
                        candidate.score() * scale
                ))
                .toList();
    }

    private void addRankedRoute(
            Map<String, FusionCandidate> fused,
            List<KnowledgeSearchIndexHit> route,
            double weight) {
        for (int rank = 0; rank < route.size(); rank++) {
            KnowledgeSearchIndexHit hit = route.get(rank);
            String key = hit.documentId() + ":" + hit.chunkIndex();
            double contribution = weight / (retrievalProperties.getRrfRankConstant() + rank + 1.0d);
            FusionCandidate previous = fused.get(key);
            if (previous == null) {
                fused.put(key, new FusionCandidate(hit, contribution));
            } else {
                fused.put(key, new FusionCandidate(previous.hit(), previous.score() + contribution));
            }
        }
    }

    private List<KnowledgeSearchResult> toResults(
            List<KnowledgeSearchIndexHit> hits,
            KnowledgeSearchOptions options) {
        List<KnowledgeSearchResult> results = new ArrayList<>();
        for (KnowledgeSearchIndexHit hit : hits) {
            KbDocument document = documents.findById(hit.documentId()).orElse(null);
            if (!KnowledgeDocumentSearchPolicy.canInject(document, options)) {
                continue;
            }
            results.add(KnowledgeDocumentSearchPolicy.toResult(
                    document,
                    hit.chunkIndex(),
                    hit.chunkText(),
                    hit.retrievalScore()
            ));
        }
        results.sort(Comparator.comparingDouble(KnowledgeSearchResult::score).reversed());
        return List.copyOf(results);
    }

    private record RouteHits(List<KnowledgeSearchIndexHit> hits) {
    }

    private record FusionCandidate(KnowledgeSearchIndexHit hit, double score) {
    }
}
