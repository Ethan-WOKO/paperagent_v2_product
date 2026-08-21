package com.yanban.knowledge.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.knowledge.service.EmbeddingClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Test-only synchronous batch embedding client with an append-only local cache. */
public final class CachedBatchDashScopeEmbeddingClient implements EmbeddingClient {

    private static final int MAX_BATCH_SIZE = 10;
    private static final int MAX_ATTEMPTS = 3;

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;
    private final int dimensions;
    private final Path cachePath;
    private final Map<String, List<Double>> cache = new LinkedHashMap<>();

    public CachedBatchDashScopeEmbeddingClient(
            String apiUrl,
            String apiKey,
            String model,
            int dimensions,
            Path cachePath) throws IOException {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("DASHSCOPE_API_KEY is required");
        this.restClient = RestClient.builder().baseUrl(apiUrl).build();
        this.mapper = new ObjectMapper();
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
        this.cachePath = cachePath;
        loadCache();
    }

    @Override
    public synchronized List<Double> embed(String text) {
        String key = key(text);
        List<Double> cached = cache.get(key);
        if (cached != null) return cached;
        warm(List.of(text));
        return cache.get(key);
    }

    public synchronized void warm(List<String> texts) {
        Map<String, String> missing = new LinkedHashMap<>();
        for (String text : texts) {
            String key = key(text);
            if (!cache.containsKey(key)) missing.putIfAbsent(key, text);
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(missing.entrySet());
        for (int offset = 0; offset < entries.size(); offset += MAX_BATCH_SIZE) {
            List<Map.Entry<String, String>> batch = entries.subList(
                    offset, Math.min(entries.size(), offset + MAX_BATCH_SIZE));
            List<List<Double>> vectors = requestWithRetry(batch.stream().map(Map.Entry::getValue).toList());
            if (vectors.size() != batch.size()) throw new IllegalStateException("Embedding response size mismatch");
            List<CacheEntry> additions = new ArrayList<>();
            for (int index = 0; index < batch.size(); index++) {
                List<Double> vector = List.copyOf(vectors.get(index));
                if (vector.size() != dimensions) throw new IllegalStateException("Embedding dimension mismatch");
                cache.put(batch.get(index).getKey(), vector);
                additions.add(new CacheEntry(batch.get(index).getKey(), model, dimensions, vector));
            }
            append(additions);
        }
    }

    public int cachedVectorCount() {
        return cache.size();
    }

    public synchronized void compact() {
        try {
            Files.createDirectories(cachePath.getParent());
            Path temporary = cachePath.resolveSibling(cachePath.getFileName() + ".tmp");
            try (java.io.BufferedWriter writer = Files.newBufferedWriter(
                    temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (Map.Entry<String, List<Double>> entry : cache.entrySet()) {
                    writer.write(mapper.writeValueAsString(
                            new CacheEntry(entry.getKey(), model, dimensions, entry.getValue())));
                    writer.newLine();
                }
            }
            try {
                Files.move(temporary, cachePath,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, cachePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not compact embedding cache", ex);
        }
    }

    private List<List<Double>> requestWithRetry(List<String> texts) {
        RuntimeException failure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                JsonNode response = restClient.post()
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new EmbeddingRequest(model, texts, dimensions, "float"))
                        .retrieve()
                        .body(JsonNode.class);
                if (response == null || !response.path("data").isArray()) {
                    throw new IllegalStateException("DashScope embedding response is malformed");
                }
                List<IndexedVector> indexed = new ArrayList<>();
                for (JsonNode item : response.path("data")) {
                    List<Double> vector = new ArrayList<>();
                    item.path("embedding").forEach(value -> vector.add(value.asDouble()));
                    indexed.add(new IndexedVector(item.path("index").asInt(indexed.size()), vector));
                }
                indexed.sort(Comparator.comparingInt(IndexedVector::index));
                return indexed.stream().map(IndexedVector::vector).toList();
            } catch (RuntimeException ex) {
                failure = ex;
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(1_000L * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Embedding retry interrupted", interrupted);
                    }
                }
            }
        }
        throw new IllegalStateException("DashScope embedding failed after bounded retries", failure);
    }

    private void loadCache() throws IOException {
        if (!Files.exists(cachePath)) return;
        try (BufferedReader reader = Files.newBufferedReader(cachePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                CacheEntry entry = mapper.readValue(line, CacheEntry.class);
                if (model.equals(entry.model()) && dimensions == entry.dimensions()
                        && entry.vector() != null && entry.vector().size() == dimensions) {
                    cache.put(entry.key(), List.copyOf(entry.vector()));
                }
            }
        }
    }

    private void append(List<CacheEntry> additions) {
        try {
            Files.createDirectories(cachePath.getParent());
            StringBuilder lines = new StringBuilder();
            for (CacheEntry addition : additions) lines.append(mapper.writeValueAsString(addition)).append('\n');
            Files.writeString(cachePath, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not persist embedding cache", ex);
        }
    }

    private String key(String text) {
        try {
            String material = model + "\n" + dimensions + "\n" + (text == null ? "" : text);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record EmbeddingRequest(String model, List<String> input, int dimensions, String encoding_format) {
    }

    private record IndexedVector(int index, List<Double> vector) {
    }

    private record CacheEntry(String key, String model, int dimensions, List<Double> vector) {
    }
}
