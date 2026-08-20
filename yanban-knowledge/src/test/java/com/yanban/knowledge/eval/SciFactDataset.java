package com.yanban.knowledge.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads the frozen BEIR SciFact corpus, test queries and qrels without copying them into Git. */
public final class SciFactDataset {

    public static final int EXPECTED_CORPUS_SIZE = 5_183;
    public static final int EXPECTED_TEST_QUERY_SIZE = 300;
    public static final int EXPECTED_TRAIN_QUERY_SIZE = 809;

    private final Map<Long, Document> documents;
    private final List<Query> testQueries;
    private final List<Query> trainingQueries;

    private SciFactDataset(
            Map<Long, Document> documents,
            List<Query> testQueries,
            List<Query> trainingQueries) {
        this.documents = Map.copyOf(documents);
        this.testQueries = List.copyOf(testQueries);
        this.trainingQueries = List.copyOf(trainingQueries);
    }

    public static SciFactDataset load(Path root) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Map<Long, Document> documents = loadDocuments(root.resolve("corpus.jsonl"), mapper);
        Map<String, String> queryText = loadQueries(root.resolve("queries.jsonl"), mapper);
        List<Query> testQueries = resolveQueries(
                loadQrels(root.resolve("qrels").resolve("test.tsv")), queryText, documents);
        List<Query> trainingQueries = resolveQueries(
                loadQrels(root.resolve("qrels").resolve("train.tsv")), queryText, documents);
        return new SciFactDataset(documents, testQueries, trainingQueries);
    }

    private static List<Query> resolveQueries(
            Map<String, List<Long>> qrels,
            Map<String, String> queryText,
            Map<Long, Document> documents) throws IOException {
        List<Query> testQueries = new ArrayList<>();
        for (Map.Entry<String, List<Long>> entry : qrels.entrySet()) {
            String text = queryText.get(entry.getKey());
            if (text == null || text.isBlank()) {
                throw new IOException("SciFact qrel references missing query " + entry.getKey());
            }
            for (Long documentId : entry.getValue()) {
                if (!documents.containsKey(documentId)) {
                    throw new IOException("SciFact qrel references missing document " + documentId);
                }
            }
            testQueries.add(new Query(entry.getKey(), text, List.copyOf(entry.getValue())));
        }
        testQueries.sort(Comparator.comparing(Query::stableOrder).thenComparing(Query::id));
        return List.copyOf(testQueries);
    }

    public Map<Long, Document> documents() {
        return documents;
    }

    public List<Query> testQueries(int limit) {
        if (limit <= 0 || limit > testQueries.size()) {
            throw new IllegalArgumentException("SciFact query limit must be between 1 and " + testQueries.size());
        }
        return testQueries.subList(0, limit);
    }

    public List<RagSpikeEvalCase> evaluationCases(int limit) {
        return toEvaluationCases(testQueries(limit));
    }

    public List<RagSpikeEvalCase> trainingCases() {
        return toEvaluationCases(trainingQueries);
    }

    private List<RagSpikeEvalCase> toEvaluationCases(List<Query> queries) {
        return queries.stream()
                .map(query -> new RagSpikeEvalCase(
                        "SCIFACT-" + query.id(),
                        "scientific_abstract_retrieval",
                        null,
                        query.text(),
                        1L,
                        null,
                        10,
                        query.relevantDocumentIds(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        "BEIR SciFact test qrel"
                ))
                .toList();
    }

    public void verifyOfficialShape() {
        if (documents.size() != EXPECTED_CORPUS_SIZE
                || testQueries.size() != EXPECTED_TEST_QUERY_SIZE
                || trainingQueries.size() != EXPECTED_TRAIN_QUERY_SIZE) {
            throw new IllegalStateException("Unexpected SciFact shape: corpus=" + documents.size()
                    + ", testQueries=" + testQueries.size()
                    + ", trainingQueries=" + trainingQueries.size());
        }
    }

    private static Map<Long, Document> loadDocuments(Path path, ObjectMapper mapper) throws IOException {
        Map<Long, Document> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode node = mapper.readTree(line);
                long id = Long.parseLong(node.path("_id").asText());
                Document previous = result.put(id, new Document(
                        id, node.path("title").asText(""), node.path("text").asText("")));
                if (previous != null) throw new IOException("Duplicate SciFact document " + id);
            }
        }
        return result;
    }

    private static Map<String, String> loadQueries(Path path, ObjectMapper mapper) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode node = mapper.readTree(line);
                result.put(node.path("_id").asText(), node.path("text").asText());
            }
        }
        return result;
    }

    private static Map<String, List<Long>> loadQrels(Path path) throws IOException {
        Map<String, List<Long>> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line = reader.readLine();
            if (line == null || !line.startsWith("query-id\tcorpus-id\tscore")) {
                throw new IOException("Unsupported SciFact qrels header");
            }
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] columns = line.split("\t");
                if (columns.length != 3) throw new IOException("Malformed SciFact qrel row");
                if (Integer.parseInt(columns[2]) <= 0) continue;
                result.computeIfAbsent(columns[0], ignored -> new ArrayList<>())
                        .add(Long.parseLong(columns[1]));
            }
        }
        return result;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record Document(long id, String title, String text) {
        public String embeddingText() {
            return title == null || title.isBlank() ? text : title + "\n" + text;
        }
    }

    public record Query(String id, String text, List<Long> relevantDocumentIds) {
        private String stableOrder() {
            return sha256(id);
        }
    }
}
