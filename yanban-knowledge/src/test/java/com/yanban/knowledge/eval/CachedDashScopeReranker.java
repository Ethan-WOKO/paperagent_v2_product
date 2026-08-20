package com.yanban.knowledge.eval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.knowledge.service.KnowledgeSearchResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Test-only DashScope reranker with deterministic request caching and usage telemetry. */
public final class CachedDashScopeReranker {

    private static final int MAX_ATTEMPTS = 3;

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String model;
    private final Path cachePath;
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>();
    private long logicalCalls;
    private long apiCalls;
    private long cacheHits;
    private long logicalTokens;
    private long billedTokens;
    private long apiLatencyMillis;
    private long retryAttempts;

    public CachedDashScopeReranker(
            String apiUrl, String apiKey, String model, Path cachePath) throws IOException {
        this(RestClient.builder().baseUrl(apiUrl).build(), apiKey, model, cachePath);
    }

    CachedDashScopeReranker(
            RestClient restClient, String apiKey, String model, Path cachePath) throws IOException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("DASHSCOPE_API_KEY is required");
        }
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.model = model;
        this.cachePath = cachePath;
        loadCache();
    }

    public synchronized List<KnowledgeSearchResult> rerank(
            String query, List<KnowledgeSearchResult> candidates, int candidateLimit) {
        return rerank(query, candidates, candidateLimit, RetrievalIntent.API_DEFAULT);
    }

    public synchronized List<KnowledgeSearchResult> rerank(
            String query,
            List<KnowledgeSearchResult> candidates,
            int candidateLimit,
            RetrievalIntent intent) {
        if (intent == null) throw new IllegalArgumentException("Retrieval intent is required");
        int selectedCount = Math.min(candidateLimit, candidates.size());
        if (selectedCount == 0) return List.of();
        List<KnowledgeSearchResult> selected = List.copyOf(candidates.subList(0, selectedCount));
        String key = key(query, selected, candidateLimit, intent);
        logicalCalls++;
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            entry = requestWithRetry(key, query, selected, candidateLimit, intent);
            cache.put(key, entry);
            append(entry);
        } else {
            cacheHits++;
        }
        logicalTokens += entry.totalTokens();

        List<KnowledgeSearchResult> ranked = new ArrayList<>(candidates.size());
        for (RankedIndex item : entry.results()) {
            ranked.add(selected.get(item.index()).withRerank(
                    item.score(), model + ":" + intent.name().toLowerCase()));
        }
        if (selectedCount < candidates.size()) {
            ranked.addAll(candidates.subList(selectedCount, candidates.size()));
        }
        return List.copyOf(ranked);
    }

    public synchronized Telemetry telemetry() {
        return new Telemetry(logicalCalls, apiCalls, cacheHits, logicalTokens,
                billedTokens, apiLatencyMillis, retryAttempts);
    }

    public synchronized void compact() {
        try {
            Files.createDirectories(cachePath.getParent());
            Path temporary = cachePath.resolveSibling(cachePath.getFileName() + ".tmp");
            try (java.io.BufferedWriter writer = Files.newBufferedWriter(
                    temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (CacheEntry entry : cache.values()) {
                    writer.write(mapper.writeValueAsString(entry));
                    writer.newLine();
                }
            }
            try {
                Files.move(temporary, cachePath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, cachePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not compact rerank cache", ex);
        }
    }

    private CacheEntry requestWithRetry(
            String key,
            String query,
            List<KnowledgeSearchResult> selected,
            int candidateLimit,
            RetrievalIntent intent) {
        RuntimeException failure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long started = System.nanoTime();
            apiCalls++;
            if (attempt > 1) retryAttempts++;
            try {
                JsonNode response = restClient.post()
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new RerankRequest(model, query,
                                selected.stream().map(KnowledgeSearchResult::chunkText).toList(),
                                selected.size(), false, intent.instruction()))
                        .retrieve()
                        .body(JsonNode.class);
                apiLatencyMillis += elapsedMillis(started);
                CacheEntry entry = parseResponse(key, candidateLimit, selected.size(), response);
                billedTokens += entry.totalTokens();
                return entry;
            } catch (RuntimeException ex) {
                apiLatencyMillis += elapsedMillis(started);
                failure = ex;
                if (attempt < MAX_ATTEMPTS) waitBeforeRetry(attempt);
            }
        }
        throw new IllegalStateException("DashScope rerank failed after bounded retries", failure);
    }

    private CacheEntry parseResponse(
            String key, int candidateLimit, int selectedCount, JsonNode response) {
        if (response == null || !response.path("results").isArray()) {
            throw new IllegalStateException("DashScope rerank response is malformed");
        }
        List<RankedIndex> results = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (JsonNode item : response.path("results")) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= selectedCount || !seen.add(index)
                    || !item.has("relevance_score")) {
                throw new IllegalStateException("DashScope rerank returned invalid indices");
            }
            results.add(new RankedIndex(index, item.path("relevance_score").asDouble()));
        }
        if (results.size() != selectedCount) {
            throw new IllegalStateException("DashScope rerank response size mismatch");
        }
        results.sort(Comparator.comparingDouble(RankedIndex::score).reversed());
        long totalTokens = response.path("usage").path("total_tokens").asLong(0L);
        return new CacheEntry(key, model, candidateLimit, List.copyOf(results), totalTokens);
    }

    private void loadCache() throws IOException {
        if (!Files.exists(cachePath)) return;
        try (BufferedReader reader = Files.newBufferedReader(cachePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                CacheEntry entry = mapper.readValue(line, CacheEntry.class);
                if (model.equals(entry.model()) && entry.results() != null) cache.put(entry.key(), entry);
            }
        }
    }

    private void append(CacheEntry entry) {
        try {
            Files.createDirectories(cachePath.getParent());
            Files.writeString(cachePath, mapper.writeValueAsString(entry) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not persist rerank cache", ex);
        }
    }

    private String key(
            String query,
            List<KnowledgeSearchResult> candidates,
            int candidateLimit,
            RetrievalIntent intent) {
        StringBuilder material = new StringBuilder(model).append('\n')
                .append(candidateLimit).append('\n').append(query == null ? "" : query);
        if (intent.instruction() != null) material.append('\n').append(intent.instruction());
        for (KnowledgeSearchResult candidate : candidates) {
            material.append('\n').append(candidate.documentId()).append(':')
                    .append(candidate.chunkIndex()).append(':').append(candidate.chunkText());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void waitBeforeRetry(int attempt) {
        try {
            Thread.sleep(1_000L * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Rerank retry interrupted", interrupted);
        }
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record RerankRequest(
            String model,
            String query,
            List<String> documents,
            int top_n,
            boolean return_documents,
            String instruct) {
    }

    private record RankedIndex(int index, double score) {
    }

    private record CacheEntry(
            String key, String model, int candidateLimit, List<RankedIndex> results, long totalTokens) {
    }

    public record Telemetry(
            long logicalCalls,
            long apiCalls,
            long cacheHits,
            long logicalTokens,
            long billedTokensThisRun,
            long apiLatencyMillis,
            long retryAttempts) {
    }
}
