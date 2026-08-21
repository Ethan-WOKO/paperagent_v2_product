package com.yanban.knowledge.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Test-only strict JSON query rewriter with validation, bounded retry and an append-only cache. */
public final class CachedDeepSeekQueryRewriter implements ControlledQueryRewriter {

    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_REWRITES = 2;
    private static final int MAX_QUERY_LENGTH = 512;
    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\p{N}])\\d+(?:\\.\\d+)?(?![\\p{L}\\p{N}])");
    private static final Pattern NEGATION = Pattern.compile(
            "(?i)(?:\\b(?:not|no|without|never|neither|nor|lack|lacks|lacking|failed|fails)\\b|不|未|无|否认|缺乏)");
    private static final String SYSTEM_PROMPT = """
            Rewrite the user's research query for document retrieval. Do not answer the query. Return one JSON object with
            exactly two string fields: keywordQuery and semanticQuery. Preserve named entities, technical terms, numbers,
            units, constraints, and positive or negative polarity. Do not invent facts. keywordQuery should be concise search
            terms; semanticQuery should express the same information need in retrieval-friendly language.""";

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String model;
    private final Path cachePath;
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>();
    private long logicalCalls;
    private long apiCalls;
    private long cacheHits;
    private long rejectedRewrites;
    private long inputTokens;
    private long outputTokens;

    public CachedDeepSeekQueryRewriter(
            String apiUrl, String apiKey, String model, Path cachePath) throws IOException {
        this(RestClient.builder().baseUrl(apiUrl).build(), apiKey, model, cachePath);
    }

    CachedDeepSeekQueryRewriter(
            RestClient restClient, String apiKey, String model, Path cachePath) throws IOException {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("DEEPSEEK_API_KEY is required");
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.model = model;
        this.cachePath = cachePath;
        loadCache();
    }

    @Override
    public synchronized List<String> rewrite(String query) {
        if (query == null || query.isBlank()) return List.of();
        String normalized = query.trim();
        String key = key(normalized);
        logicalCalls++;
        CacheEntry cached = cache.get(key);
        if (cached != null) {
            cacheHits++;
            return cached.rewrites();
        }
        CacheEntry created = requestWithRetry(key, normalized);
        cache.put(key, created);
        append(created);
        return created.rewrites();
    }

    public Telemetry telemetry() {
        return new Telemetry(logicalCalls, apiCalls, cacheHits, rejectedRewrites, inputTokens, outputTokens);
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
            throw new IllegalStateException("Could not compact query rewrite cache", ex);
        }
    }

    private CacheEntry requestWithRetry(String key, String query) {
        RuntimeException failure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            apiCalls++;
            try {
                JsonNode response = restClient.post()
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new ChatRequest(
                                model,
                                List.of(new Message("system", SYSTEM_PROMPT), new Message("user", query)),
                                new ResponseFormat("json_object"),
                                0.0d))
                        .retrieve()
                        .body(JsonNode.class);
                if (response == null) throw new IllegalStateException("Query rewrite response is empty");
                inputTokens += response.path("usage").path("prompt_tokens").asLong(0L);
                outputTokens += response.path("usage").path("completion_tokens").asLong(0L);
                String content = response.path("choices").path(0).path("message").path("content").asText();
                JsonNode payload = mapper.readTree(content);
                List<String> rewrites = validate(query, List.of(
                        payload.path("keywordQuery").asText(""),
                        payload.path("semanticQuery").asText("")));
                return new CacheEntry(key, model, List.copyOf(rewrites));
            } catch (RuntimeException | IOException ex) {
                failure = ex instanceof RuntimeException runtime ? runtime : new IllegalStateException(ex);
                if (attempt < MAX_ATTEMPTS) waitBeforeRetry(attempt);
            }
        }
        throw new IllegalStateException("Query rewrite failed after bounded retries", failure);
    }

    private List<String> validate(String original, List<String> proposed) {
        Set<String> originalNumbers = numbers(original);
        boolean originalNegated = NEGATION.matcher(original).find();
        LinkedHashSet<String> accepted = new LinkedHashSet<>();
        for (String candidate : proposed) {
            String rewrite = candidate == null ? "" : candidate.trim();
            boolean valid = !rewrite.isBlank()
                    && rewrite.length() <= MAX_QUERY_LENGTH
                    && !rewrite.equalsIgnoreCase(original)
                    && numbers(rewrite).equals(originalNumbers)
                    && NEGATION.matcher(rewrite).find() == originalNegated;
            if (valid) accepted.add(rewrite);
            else rejectedRewrites++;
            if (accepted.size() == MAX_REWRITES) break;
        }
        return List.copyOf(accepted);
    }

    private Set<String> numbers(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher matcher = NUMBER.matcher(value == null ? "" : value);
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    private void loadCache() throws IOException {
        if (!Files.exists(cachePath)) return;
        try (BufferedReader reader = Files.newBufferedReader(cachePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                CacheEntry entry = mapper.readValue(line, CacheEntry.class);
                if (model.equals(entry.model()) && entry.rewrites() != null) cache.put(entry.key(), entry);
            }
        }
    }

    private void append(CacheEntry entry) {
        try {
            Files.createDirectories(cachePath.getParent());
            Files.writeString(cachePath, mapper.writeValueAsString(entry) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not persist query rewrite cache", ex);
        }
    }

    private String key(String query) {
        try {
            String material = model + "\n" + SYSTEM_PROMPT + "\n" + query;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void waitBeforeRetry(int attempt) {
        try {
            Thread.sleep(1_000L * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Query rewrite retry interrupted", interrupted);
        }
    }

    private record ChatRequest(
            String model, List<Message> messages, ResponseFormat response_format, double temperature) {
    }

    private record Message(String role, String content) {
    }

    private record ResponseFormat(String type) {
    }

    private record CacheEntry(String key, String model, List<String> rewrites) {
    }

    public record Telemetry(
            long logicalCalls,
            long apiCalls,
            long cacheHits,
            long rejectedRewrites,
            long inputTokens,
            long outputTokens) {
    }
}
