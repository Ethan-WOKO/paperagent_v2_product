package com.yanban.knowledge.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BaselineRagRunner {

    private static final List<Integer> RANK_CUTOFFS = List.of(1, 3, 5, 10, 20, 50);

    private final BaselineSearchBackend searchBackend;
    private final String runnerName;

    public BaselineRagRunner(BaselineSearchBackend searchBackend) {
        this("current-rag-baseline", searchBackend);
    }

    public BaselineRagRunner(String runnerName, BaselineSearchBackend searchBackend) {
        this.runnerName = runnerName;
        this.searchBackend = searchBackend;
    }

    public BaselineRagEvaluationResult run(List<RagSpikeEvalCase> cases) {
        List<BaselineRagEvaluationResult.CaseResult> results = new ArrayList<>();
        for (RagSpikeEvalCase evalCase : cases) {
            long startedAt = System.nanoTime();
            List<BaselineRagHit> hits = searchBackend.search(evalCase);
            double latencyMillis = (System.nanoTime() - startedAt) / 1_000_000.0d;
            results.add(evaluateCase(evalCase, hits, latencyMillis));
        }
        return new BaselineRagEvaluationResult(
                runnerName,
                Instant.now(),
                summarize(results),
                results
        );
    }

    public void writeJson(BaselineRagEvaluationResult result, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        ObjectMapper mapper = new ObjectMapper()
                .findAndRegisterModules()
                .enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(outputPath.toFile(), result);
    }

    private BaselineRagEvaluationResult.CaseResult evaluateCase(
            RagSpikeEvalCase evalCase,
            List<BaselineRagHit> hits,
            double latencyMillis) {
        List<Long> retrievedDocumentIds = hits.stream()
                .map(BaselineRagHit::documentId)
                .toList();
        List<String> retrievedCitationIds = hits.stream()
                .map(BaselineRagHit::citationId)
                .filter(value -> value != null && !value.isBlank())
                .toList();
        List<Long> missingExpectedDocumentIds = missing(evalCase.expectedDocumentIds(), retrievedDocumentIds);
        List<Long> forbiddenDocumentIdsHit = intersection(evalCase.forbiddenDocumentIds(), retrievedDocumentIds);
        List<String> missingExpectedCitationIds = missing(evalCase.expectedCitationIds(), retrievedCitationIds);
        Map<String, Boolean> rankingRuleResults = evaluateRankingRules(evalCase, retrievedDocumentIds);
        Map<Integer, Double> recallAtN = metricAtCutoffs(
                cutoff -> recallAt(evalCase.expectedDocumentIds(), retrievedDocumentIds, cutoff));
        Map<Integer, Double> ndcgAtN = metricAtCutoffs(
                cutoff -> ndcgAt(evalCase.expectedDocumentIds(), retrievedDocumentIds, cutoff));
        double reciprocalRank = reciprocalRank(evalCase.expectedDocumentIds(), retrievedDocumentIds, 10);
        boolean passed = missingExpectedDocumentIds.isEmpty()
                && forbiddenDocumentIdsHit.isEmpty()
                && missingExpectedCitationIds.isEmpty()
                && rankingRuleResults.values().stream().allMatch(Boolean::booleanValue);
        return new BaselineRagEvaluationResult.CaseResult(
                evalCase.caseId(),
                evalCase.area(),
                evalCase.expectedDocumentIds() != null && !evalCase.expectedDocumentIds().isEmpty(),
                passed,
                retrievedDocumentIds,
                retrievedCitationIds,
                missingExpectedDocumentIds,
                forbiddenDocumentIdsHit,
                missingExpectedCitationIds,
                rankingRuleResults,
                reciprocalRank,
                recallAtN,
                ndcgAtN,
                latencyMillis
        );
    }

    private BaselineRagEvaluationResult.Summary summarize(List<BaselineRagEvaluationResult.CaseResult> results) {
        int total = results.size();
        List<BaselineRagEvaluationResult.CaseResult> rankingEligible = results.stream()
                .filter(BaselineRagEvaluationResult.CaseResult::rankingEligible)
                .toList();
        int passed = (int) results.stream().filter(BaselineRagEvaluationResult.CaseResult::passed).count();
        int forbiddenHits = results.stream()
                .mapToInt(result -> result.forbiddenDocumentIdsHit().size())
                .sum();
        long metadataChecked = results.stream()
                .flatMap(result -> result.retrievedCitationIds().stream())
                .count();
        long metadataPresent = results.stream()
                .flatMap(result -> result.retrievedCitationIds().stream())
                .filter(value -> value != null && !value.isBlank())
                .count();
        Map<Integer, Double> recallAtN = aggregateAtCutoffs(rankingEligible,
                BaselineRagEvaluationResult.CaseResult::recallAtN);
        Map<Integer, Double> ndcgAtN = aggregateAtCutoffs(rankingEligible,
                BaselineRagEvaluationResult.CaseResult::ndcgAtN);
        double recallAt5 = recallAtN.getOrDefault(5, 0.0d);
        double mrr = rankingEligible.stream()
                .mapToDouble(BaselineRagEvaluationResult.CaseResult::reciprocalRank)
                .average()
                .orElse(0.0d);
        List<Double> latencies = results.stream()
                .map(BaselineRagEvaluationResult.CaseResult::latencyMillis)
                .sorted()
                .toList();
        double metadataRate = metadataChecked == 0 ? 1.0d : metadataPresent / (double) metadataChecked;
        return new BaselineRagEvaluationResult.Summary(
                total,
                rankingEligible.size(),
                passed,
                total - passed,
                recallAt5,
                mrr,
                recallAtN,
                ndcgAtN,
                mrr,
                percentile(latencies, 0.50d),
                percentile(latencies, 0.95d),
                forbiddenHits,
                metadataRate
        );
    }

    private <T> List<T> missing(List<T> expected, List<T> actual) {
        Set<T> actualSet = new LinkedHashSet<>(actual == null ? List.of() : actual);
        List<T> result = new ArrayList<>();
        for (T item : expected == null ? List.<T>of() : expected) {
            if (!actualSet.contains(item)) {
                result.add(item);
            }
        }
        return result;
    }

    private <T> List<T> intersection(List<T> expected, List<T> actual) {
        Set<T> actualSet = new LinkedHashSet<>(actual == null ? List.of() : actual);
        List<T> result = new ArrayList<>();
        for (T item : expected == null ? List.<T>of() : expected) {
            if (actualSet.contains(item)) {
                result.add(item);
            }
        }
        return result;
    }

    private Map<String, Boolean> evaluateRankingRules(RagSpikeEvalCase evalCase, List<Long> retrievedDocumentIds) {
        Map<String, Boolean> results = new LinkedHashMap<>();
        if (evalCase.rankingRules() == null) {
            return results;
        }
        for (RagSpikeEvalCase.RankingRule rule : evalCase.rankingRules()) {
            int preferredIndex = retrievedDocumentIds.indexOf(rule.preferredDocumentId());
            int lowerIndex = retrievedDocumentIds.indexOf(rule.lowerPriorityDocumentId());
            String key = rule.preferredDocumentId() + "_before_" + rule.lowerPriorityDocumentId();
            results.put(key, preferredIndex >= 0 && (lowerIndex < 0 || preferredIndex < lowerIndex));
        }
        return results;
    }

    private Map<Integer, Double> metricAtCutoffs(java.util.function.IntToDoubleFunction metric) {
        Map<Integer, Double> values = new LinkedHashMap<>();
        for (int cutoff : RANK_CUTOFFS) values.put(cutoff, metric.applyAsDouble(cutoff));
        return values;
    }

    private Map<Integer, Double> aggregateAtCutoffs(
            List<BaselineRagEvaluationResult.CaseResult> results,
            java.util.function.Function<BaselineRagEvaluationResult.CaseResult, Map<Integer, Double>> values) {
        Map<Integer, Double> aggregated = new LinkedHashMap<>();
        for (int cutoff : RANK_CUTOFFS) {
            aggregated.put(cutoff, results.stream()
                    .map(values)
                    .mapToDouble(item -> item.getOrDefault(cutoff, 0.0d))
                    .average().orElse(0.0d));
        }
        return aggregated;
    }

    private double recallAt(List<Long> expectedDocumentIds, List<Long> retrievedDocumentIds, int cutoff) {
        if (expectedDocumentIds == null || expectedDocumentIds.isEmpty()) return 0.0d;
        Set<Long> top = new LinkedHashSet<>(retrievedDocumentIds.stream().limit(cutoff).toList());
        long recalled = expectedDocumentIds.stream().filter(top::contains).count();
        return recalled / (double) new LinkedHashSet<>(expectedDocumentIds).size();
    }

    private double ndcgAt(List<Long> expectedDocumentIds, List<Long> retrievedDocumentIds, int cutoff) {
        if (expectedDocumentIds == null || expectedDocumentIds.isEmpty()) return 0.0d;
        Set<Long> relevant = new LinkedHashSet<>(expectedDocumentIds);
        Set<Long> credited = new LinkedHashSet<>();
        double dcg = 0.0d;
        for (int index = 0; index < Math.min(cutoff, retrievedDocumentIds.size()); index++) {
            Long documentId = retrievedDocumentIds.get(index);
            if (relevant.contains(documentId) && credited.add(documentId)) {
                dcg += 1.0d / (Math.log(index + 2.0d) / Math.log(2.0d));
            }
        }
        double ideal = 0.0d;
        for (int index = 0; index < Math.min(cutoff, relevant.size()); index++) {
            ideal += 1.0d / (Math.log(index + 2.0d) / Math.log(2.0d));
        }
        return ideal == 0.0d ? 0.0d : dcg / ideal;
    }

    private double reciprocalRank(List<Long> expectedDocumentIds, List<Long> retrievedDocumentIds, int cutoff) {
        if (expectedDocumentIds == null || expectedDocumentIds.isEmpty()) {
            return 0.0d;
        }
        for (int i = 0; i < Math.min(cutoff, retrievedDocumentIds.size()); i++) {
            if (expectedDocumentIds.contains(retrievedDocumentIds.get(i))) {
                return 1.0d / (i + 1);
            }
        }
        return 0.0d;
    }

    private double percentile(List<Double> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) return 0.0d;
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }
}
