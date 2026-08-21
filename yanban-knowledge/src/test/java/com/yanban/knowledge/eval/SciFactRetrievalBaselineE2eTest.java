package com.yanban.knowledge.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.knowledge.config.KnowledgeChunkingProperties;
import com.yanban.knowledge.config.KnowledgeElasticsearchProperties;
import com.yanban.knowledge.service.ElasticsearchKnowledgeIndexProvisioner;
import com.yanban.knowledge.service.ElasticsearchKnowledgeSearchIndexClient;
import com.yanban.knowledge.service.KnowledgeSearchResult;
import com.yanban.knowledge.service.KnowledgeTextChunker;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Comparator;
import org.apache.http.HttpHost;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;

class SciFactRetrievalBaselineE2eTest {

    private static final String DEFAULT_ENDPOINT = "http://localhost:9200";
    private static final String DEFAULT_EMBEDDING_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings";
    private static final String DEFAULT_MODEL = "text-embedding-v4";
    private static final String DEFAULT_RERANK_URL =
            "https://dashscope.aliyuncs.com/compatible-api/v1/reranks";
    private static final String DEFAULT_RERANK_MODEL = "qwen3-rerank";
    private static final String DEFAULT_REWRITE_URL = "https://api.deepseek.com/chat/completions";
    private static final String DEFAULT_REWRITE_MODEL = "deepseek-chat";
    private static final int DEFAULT_DIMENSIONS = 1_024;
    private static final List<Integer> TIERS = List.of(50, 100, 300);

    @Test
    void evaluatesCompleteSciFactCorpusAtStableQueryTiers() throws Exception {
        assumeTrue(Boolean.getBoolean("yanban.scifact-eval"),
                "Set -Dyanban.scifact-eval=true to run the complete SciFact baseline.");

        Path runLockPath = Path.of("target", "rag-eval", "scifact-evaluation.lock");
        Files.createDirectories(runLockPath.getParent());
        try (FileChannel lockChannel = FileChannel.open(runLockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock runLock = lockChannel.tryLock()) {
            if (runLock == null) {
                throw new IllegalStateException("Another SciFact evaluation is already running");
            }
            runEvaluation();
        }
    }

    private void runEvaluation() throws Exception {

        Path datasetRoot = Path.of(System.getProperty(
                "yanban.scifact-dataset", "target/rag-eval/datasets/scifact"));
        Path cachePath = Path.of(System.getProperty(
                "yanban.scifact-embedding-cache", "target/rag-eval/cache/scifact-embeddings-v4-1024.jsonl"));
        String endpoint = System.getProperty("yanban.real-es-endpoint", DEFAULT_ENDPOINT);
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        String apiUrl = System.getProperty("yanban.scifact-embedding-url", DEFAULT_EMBEDDING_URL);
        String model = System.getProperty("yanban.scifact-embedding-model", DEFAULT_MODEL);
        int dimensions = Integer.getInteger("yanban.scifact-embedding-dimensions", DEFAULT_DIMENSIONS);
        int maxQueries = Integer.getInteger("yanban.scifact-max-queries", 300);
        boolean includeModelRerank = Boolean.getBoolean("yanban.scifact-model-rerank");
        boolean tuneRrf = Boolean.getBoolean("yanban.scifact-rrf-tuning");
        boolean evaluateRerankIntents = Boolean.getBoolean("yanban.scifact-rerank-intent-eval");
        boolean evaluateQueryRewrite = Boolean.getBoolean("yanban.scifact-query-rewrite-eval");
        boolean evaluateOptimizedPipeline = Boolean.getBoolean("yanban.scifact-optimized-pipeline-eval");
        boolean evaluateFrozenFinal = Boolean.getBoolean("yanban.scifact-frozen-final-eval");
        boolean evaluateRerankWindows = Boolean.getBoolean("yanban.scifact-rerank-window-eval");
        boolean evaluateChunking = Boolean.getBoolean("yanban.scifact-chunking-eval");
        String rerankApiUrl = System.getProperty("yanban.scifact-rerank-url", DEFAULT_RERANK_URL);
        String rerankModel = System.getProperty("yanban.scifact-rerank-model", DEFAULT_RERANK_MODEL);
        String indexName = System.getProperty("yanban.scifact-index",
                "yanban-scifact-eval-" + model.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9-]", "-") + "-" + dimensions + "-v1");

        SciFactDataset dataset = SciFactDataset.load(datasetRoot);
        dataset.verifyOfficialShape();
        CachedBatchDashScopeEmbeddingClient embeddings = new CachedBatchDashScopeEmbeddingClient(
                apiUrl, apiKey, model, dimensions, cachePath);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        KnowledgeElasticsearchProperties properties = new KnowledgeElasticsearchProperties();
        properties.setEndpoint(endpoint);
        properties.setIndexName(indexName);
        properties.setVectorDimensions(dimensions);
        properties.setMigrateLegacyIndex(false);

        try (RestClient restClient = RestClient.builder(HttpHost.create(endpoint)).build()) {
            if (evaluateChunking) {
                runChunkingEvaluation(restClient, mapper, endpoint, dimensions, dataset, embeddings,
                        rerankApiUrl, apiKey, rerankModel, maxQueries);
                return;
            }
            ElasticsearchKnowledgeIndexProvisioner provisioner = new ElasticsearchKnowledgeIndexProvisioner(
                    restClient, mapper, properties);
            provisioner.ensureReady();
            ensureCorpus(restClient, mapper, indexName, dataset, embeddings);
            ElasticsearchKnowledgeSearchIndexClient indexClient = new ElasticsearchKnowledgeSearchIndexClient(
                    restClient, mapper, properties, provisioner);
            Map<Long, SciFactDataset.Document> documents = dataset.documents();

            if (tuneRrf) {
                runRrfTuning(dataset, indexClient, embeddings, documents, mapper);
                return;
            }
            if (evaluateRerankIntents) {
                runRerankIntentEvaluation(dataset, indexClient, embeddings, documents, mapper,
                        rerankApiUrl, apiKey, rerankModel);
                return;
            }
            if (evaluateQueryRewrite) {
                runQueryRewriteEvaluation(dataset, indexClient, embeddings, documents, mapper);
                return;
            }
            if (evaluateOptimizedPipeline) {
                runOptimizedPipelineEvaluation(dataset, indexClient, embeddings, documents, mapper,
                        rerankApiUrl, apiKey, rerankModel);
                return;
            }
            if (evaluateFrozenFinal) {
                runFrozenFinalEvaluation(dataset, indexClient, embeddings, documents, mapper,
                        rerankApiUrl, apiKey, rerankModel);
                return;
            }
            if (evaluateRerankWindows) {
                runRerankWindowEvaluation(dataset, indexClient, embeddings, documents, mapper,
                        rerankApiUrl, apiKey, rerankModel);
                return;
            }

            List<Integer> tiers = TIERS.stream().filter(tier -> tier <= maxQueries).toList();
            assertThat(tiers).isNotEmpty();
            embeddings.warm(dataset.testQueries(maxQueries).stream().map(SciFactDataset.Query::text).toList());
            embeddings.compact();
            for (int tier : tiers) {
                List<RagSpikeEvalCase> cases = dataset.evaluationCases(tier);
                Path cacheRoot = Path.of("target", "rag-eval", "cache");
                CachedDashScopeReranker reranker20 = includeModelRerank
                        ? new CachedDashScopeReranker(rerankApiUrl, apiKey, rerankModel,
                                cacheRoot.resolve("scifact-" + rerankModel + "-top20.jsonl")) : null;
                CachedDashScopeReranker reranker30 = includeModelRerank
                        ? new CachedDashScopeReranker(rerankApiUrl, apiKey, rerankModel,
                                cacheRoot.resolve("scifact-" + rerankModel + "-top30.jsonl")) : null;
                CachedDashScopeReranker reranker40 = includeModelRerank
                        ? new CachedDashScopeReranker(rerankApiUrl, apiKey, rerankModel,
                                cacheRoot.resolve("scifact-" + rerankModel + "-top40.jsonl")) : null;
                CachedDashScopeReranker reranker50 = includeModelRerank
                        ? new CachedDashScopeReranker(rerankApiUrl, apiKey, rerankModel,
                                cacheRoot.resolve("scifact-" + rerankModel + "-top50.jsonl")) : null;
                List<BaselineRagEvaluationResult> modeResults = Arrays.stream(RetrievalBaselineMode.values())
                        .filter(mode -> includeModelRerank || !mode.requiresModelReranker())
                        .map(mode -> new BaselineRagRunner(
                                "scifact-" + mode.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                                new ElasticsearchRetrievalBaselineBackend(
                                        mode, indexClient, embeddings,
                                        documentId -> resolveDocument(documents.get(documentId)),
                                        modelReranker(mode, reranker20, reranker30, reranker40, reranker50)))
                                .run(cases))
                        .toList();
                RagModeComparisonEvaluationResult comparison = RagModeComparisonEvaluationResult.from(
                        "beir-scifact-" + tier + "-" + model + "-" + dimensions, modeResults);
                Path outputRoot = Path.of("target", "rag-eval", "scifact");
                RagModeComparisonReportWriter.writeJson(
                        comparison, outputRoot.resolve("scifact-" + tier + ".json"));
                RagModeComparisonReportWriter.writeMarkdown(
                        comparison, outputRoot.resolve("scifact-" + tier + ".md"));
                if (includeModelRerank) {
                    writeRerankUsage(mapper, outputRoot.resolve(
                            "scifact-" + tier + "-model-rerank-usage.json"),
                            rerankModel, reranker20, reranker30, reranker40, reranker50);
                    reranker20.compact();
                    reranker30.compact();
                    reranker40.compact();
                    reranker50.compact();
                }
                assertThat(comparison.summary().totalModes()).isEqualTo(includeModelRerank ? 8 : 4);
                assertThat(comparison.summary().totalCases()).isEqualTo(tier);
                assertThat(comparison.modeResults()).allSatisfy(result -> {
                    assertThat(result.summary().rankingEligibleCases()).isEqualTo(tier);
                    assertThat(result.summary().forbiddenHitCount()).isZero();
                });
            }
        }
    }

    private void runRrfTuning(
            SciFactDataset dataset,
            ElasticsearchKnowledgeSearchIndexClient indexClient,
            CachedBatchDashScopeEmbeddingClient embeddings,
            Map<Long, SciFactDataset.Document> documents,
            ObjectMapper mapper) throws Exception {
        List<RagSpikeEvalCase> allTrainingCases = dataset.trainingCases();
        List<RagSpikeEvalCase> tuningCases = allTrainingCases.subList(0, 600);
        List<RagSpikeEvalCase> validationCases = allTrainingCases.subList(600, allTrainingCases.size());
        embeddings.warm(allTrainingCases.stream().map(RagSpikeEvalCase::query).toList());
        embeddings.compact();
        CachingKnowledgeSearchIndexClient cachedIndex = new CachingKnowledgeSearchIndexClient(indexClient);
        List<TuningRun> tuningRuns = new ArrayList<>();
        for (double lexicalWeight : List.of(0.25d, 0.5d, 0.75d, 1.0d)) {
            for (int rankConstant : List.of(10, 30, 60)) {
                RrfConfiguration configuration = new RrfConfiguration(lexicalWeight, 1.0d, rankConstant);
                BaselineRagEvaluationResult result = runRrfConfiguration(
                        "rrf-l" + lexicalWeight + "-v1.0-k" + rankConstant,
                        RetrievalBaselineMode.RRF, configuration, tuningCases,
                        cachedIndex, embeddings, documents);
                tuningRuns.add(new TuningRun(configuration, result));
            }
        }
        TuningRun selected = tuningRuns.stream().max(Comparator
                .comparingDouble((TuningRun run) -> run.result().summary().ndcgAtN().get(10))
                .thenComparingDouble(run -> run.result().summary().meanReciprocalRankAt10())
                .thenComparingDouble(run -> run.result().summary().recallAtN().get(50)))
                .orElseThrow();
        List<BaselineRagEvaluationResult> validationResults = List.of(
                runRrfConfiguration("knn-validation", RetrievalBaselineMode.KNN,
                        RrfConfiguration.EQUAL_WEIGHT_BASELINE, validationCases,
                        cachedIndex, embeddings, documents),
                runRrfConfiguration("rrf-equal-validation", RetrievalBaselineMode.RRF,
                        RrfConfiguration.EQUAL_WEIGHT_BASELINE, validationCases,
                        cachedIndex, embeddings, documents),
                runRrfConfiguration("rrf-selected-validation", RetrievalBaselineMode.RRF,
                        selected.configuration(), validationCases,
                        cachedIndex, embeddings, documents));
        Path outputRoot = Path.of("target", "rag-eval", "scifact");
        RagModeComparisonEvaluationResult tuningComparison = RagModeComparisonEvaluationResult.from(
                "beir-scifact-train-tuning-600", tuningRuns.stream().map(TuningRun::result).toList());
        RagModeComparisonReportWriter.writeJson(
                tuningComparison, outputRoot.resolve("scifact-rrf-tuning-600.json"));
        RagModeComparisonReportWriter.writeMarkdown(
                tuningComparison, outputRoot.resolve("scifact-rrf-tuning-600.md"));
        RagModeComparisonEvaluationResult validationComparison = RagModeComparisonEvaluationResult.from(
                "beir-scifact-train-validation-209", validationResults);
        RagModeComparisonReportWriter.writeJson(
                validationComparison, outputRoot.resolve("scifact-rrf-validation-209.json"));
        RagModeComparisonReportWriter.writeMarkdown(
                validationComparison, outputRoot.resolve("scifact-rrf-validation-209.md"));
        Files.createDirectories(outputRoot);
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                outputRoot.resolve("scifact-rrf-selection.json").toFile(), Map.of(
                        "selected", selected.configuration(),
                        "selectionMetric", "nDCG@10_then_MRR@10_then_Recall@50",
                        "tuningCases", tuningCases.size(),
                        "validationCases", validationCases.size(),
                        "routeCache", cachedIndex.telemetry()));
        assertThat(tuningRuns).hasSize(12);
        assertThat(validationResults).hasSize(3);
    }

    private BaselineRagEvaluationResult runRrfConfiguration(
            String runner,
            RetrievalBaselineMode mode,
            RrfConfiguration configuration,
            List<RagSpikeEvalCase> cases,
            CachingKnowledgeSearchIndexClient index,
            CachedBatchDashScopeEmbeddingClient embeddings,
            Map<Long, SciFactDataset.Document> documents) {
        return new BaselineRagRunner(runner, new ElasticsearchRetrievalBaselineBackend(
                mode, index, embeddings,
                documentId -> resolveDocument(documents.get(documentId)), null,
                configuration, RetrievalIntent.GENERAL_RESEARCH)).run(cases);
    }

    private record TuningRun(RrfConfiguration configuration, BaselineRagEvaluationResult result) {
    }

    private void runQueryRewriteEvaluation(
            SciFactDataset dataset,
            ElasticsearchKnowledgeSearchIndexClient indexClient,
            CachedBatchDashScopeEmbeddingClient embeddings,
            Map<Long, SciFactDataset.Document> documents,
            ObjectMapper mapper) throws Exception {
        List<RagSpikeEvalCase> cases = dataset.trainingCases().subList(650, 700);
        embeddings.warm(cases.stream().map(RagSpikeEvalCase::query).toList());
        embeddings.compact();
        CachingKnowledgeSearchIndexClient cachedIndex = new CachingKnowledgeSearchIndexClient(indexClient);
        String rewriteApiKey = System.getenv("DEEPSEEK_API_KEY");
        String rewriteApiUrl = System.getProperty("yanban.scifact-rewrite-url", DEFAULT_REWRITE_URL);
        String rewriteModel = System.getProperty("yanban.scifact-rewrite-model", DEFAULT_REWRITE_MODEL);
        Path outputRoot = Path.of("target", "rag-eval", "scifact");
        Path cacheRoot = Path.of("target", "rag-eval", "cache");
        CachedDeepSeekQueryRewriter rewriter = new CachedDeepSeekQueryRewriter(
                rewriteApiUrl, rewriteApiKey, rewriteModel,
                cacheRoot.resolve("scifact-" + rewriteModel + "-query-rewrite.jsonl"));
        RrfConfiguration selectedRrf = new RrfConfiguration(0.5d, 1.0d, 10);
        List<BaselineRagEvaluationResult> results = new ArrayList<>();
        results.add(runRrfConfiguration(
                "rrf-selected-no-rewrite", RetrievalBaselineMode.RRF, selectedRrf,
                cases, cachedIndex, embeddings, documents));
        for (double rewriteWeight : List.of(0.25d, 0.5d, 0.75d)) {
            results.add(new BaselineRagRunner(
                    "rrf-selected-rewrite-w" + rewriteWeight,
                    new ElasticsearchRetrievalBaselineBackend(
                            RetrievalBaselineMode.RRF,
                            cachedIndex,
                            embeddings,
                            documentId -> resolveDocument(documents.get(documentId)),
                            null,
                            selectedRrf,
                            RetrievalIntent.API_DEFAULT,
                            rewriter,
                            rewriteWeight)).run(cases));
        }
        RagModeComparisonEvaluationResult comparison = RagModeComparisonEvaluationResult.from(
                "beir-scifact-train-query-rewrite-50", results);
        RagModeComparisonReportWriter.writeJson(
                comparison, outputRoot.resolve("scifact-query-rewrite-50.json"));
        RagModeComparisonReportWriter.writeMarkdown(
                comparison, outputRoot.resolve("scifact-query-rewrite-50.md"));
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                outputRoot.resolve("scifact-query-rewrite-50-usage.json").toFile(), Map.of(
                        "model", rewriteModel,
                        "cases", cases.size(),
                        "telemetry", rewriter.telemetry(),
                        "routeCache", cachedIndex.telemetry()));
        rewriter.compact();
        assertThat(results).hasSize(4);
    }

    private void runOptimizedPipelineEvaluation(
            SciFactDataset dataset,
            ElasticsearchKnowledgeSearchIndexClient indexClient,
            CachedBatchDashScopeEmbeddingClient embeddings,
            Map<Long, SciFactDataset.Document> documents,
            ObjectMapper mapper,
            String rerankApiUrl,
            String rerankApiKey,
            String rerankModel) throws Exception {
        List<RagSpikeEvalCase> cases = dataset.trainingCases().subList(650, 700);
        embeddings.warm(cases.stream().map(RagSpikeEvalCase::query).toList());
        embeddings.compact();
        CachingKnowledgeSearchIndexClient cachedIndex = new CachingKnowledgeSearchIndexClient(indexClient);
        String rewriteApiKey = System.getenv("DEEPSEEK_API_KEY");
        String rewriteApiUrl = System.getProperty("yanban.scifact-rewrite-url", DEFAULT_REWRITE_URL);
        String rewriteModel = System.getProperty("yanban.scifact-rewrite-model", DEFAULT_REWRITE_MODEL);
        Path outputRoot = Path.of("target", "rag-eval", "scifact");
        Path cacheRoot = Path.of("target", "rag-eval", "cache");
        CachedDeepSeekQueryRewriter rewriter = new CachedDeepSeekQueryRewriter(
                rewriteApiUrl, rewriteApiKey, rewriteModel,
                cacheRoot.resolve("scifact-" + rewriteModel + "-query-rewrite.jsonl"));
        RrfConfiguration selectedRrf = new RrfConfiguration(0.5d, 1.0d, 10);
        List<PipelineRun> runs = List.of(
                new PipelineRun("rrf-equal-model-top50", RetrievalBaselineMode.RRF_MODEL_RERANK_50,
                        RrfConfiguration.EQUAL_WEIGHT_BASELINE, null, 0.0d),
                new PipelineRun("rrf-selected-model-top50", RetrievalBaselineMode.RRF_MODEL_RERANK_50,
                        selectedRrf, null, 0.0d),
                new PipelineRun("rrf-selected-rewrite-w0.5-model-top50",
                        RetrievalBaselineMode.RRF_MODEL_RERANK_50, selectedRrf, rewriter, 0.5d));
        List<BaselineRagEvaluationResult> results = new ArrayList<>();
        Map<String, CachedDashScopeReranker.Telemetry> rerankUsage = new LinkedHashMap<>();
        for (PipelineRun run : runs) {
            CachedDashScopeReranker reranker = new CachedDashScopeReranker(
                    rerankApiUrl, rerankApiKey, rerankModel,
                    cacheRoot.resolve("scifact-optimized-" + run.name() + ".jsonl"));
            results.add(new BaselineRagRunner(
                    run.name(),
                    new ElasticsearchRetrievalBaselineBackend(
                            run.mode(),
                            cachedIndex,
                            embeddings,
                            documentId -> resolveDocument(documents.get(documentId)),
                            reranker,
                            run.rrf(),
                            RetrievalIntent.API_DEFAULT,
                            run.rewriter(),
                            run.rewriteWeight())).run(cases));
            rerankUsage.put(run.name(), reranker.telemetry());
            reranker.compact();
        }
        RagModeComparisonEvaluationResult comparison = RagModeComparisonEvaluationResult.from(
                "beir-scifact-train-optimized-pipeline-50", results);
        RagModeComparisonReportWriter.writeJson(
                comparison, outputRoot.resolve("scifact-optimized-pipeline-50.json"));
        RagModeComparisonReportWriter.writeMarkdown(
                comparison, outputRoot.resolve("scifact-optimized-pipeline-50.md"));
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("rerankModel", rerankModel);
        usage.put("rewriteModel", rewriteModel);
        usage.put("cases", cases.size());
        usage.put("rerank", rerankUsage);
        usage.put("rewrite", rewriter.telemetry());
        usage.put("routeCache", cachedIndex.telemetry());
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                outputRoot.resolve("scifact-optimized-pipeline-50-usage.json").toFile(), usage);
        rewriter.compact();
        assertThat(results).hasSize(3);
    }

    private record PipelineRun(
            String name,
            RetrievalBaselineMode mode,
            RrfConfiguration rrf,
            ControlledQueryRewriter rewriter,
            double rewriteWeight) {
    }

    private void runFrozenFinalEvaluation(
            SciFactDataset dataset,
            ElasticsearchKnowledgeSearchIndexClient indexClient,
            CachedBatchDashScopeEmbeddingClient embeddings,
            Map<Long, SciFactDataset.Document> documents,
            ObjectMapper mapper,
            String rerankApiUrl,
            String rerankApiKey,
            String rerankModel) throws Exception {
        List<RagSpikeEvalCase> cases = dataset.evaluationCases(300);
        embeddings.warm(cases.stream().map(RagSpikeEvalCase::query).toList());
        embeddings.compact();
        CachingKnowledgeSearchIndexClient cachedIndex = new CachingKnowledgeSearchIndexClient(indexClient);
        Path outputRoot = Path.of("target", "rag-eval", "scifact");
        Path cacheRoot = Path.of("target", "rag-eval", "cache");
        List<PipelineRun> runs = List.of(
                new PipelineRun("rrf-equal-model-top50-final", RetrievalBaselineMode.RRF_MODEL_RERANK_50,
                        RrfConfiguration.EQUAL_WEIGHT_BASELINE, null, 0.0d),
                new PipelineRun("rrf-selected-model-top20-final", RetrievalBaselineMode.RRF_MODEL_RERANK_20,
                        new RrfConfiguration(0.5d, 1.0d, 10), null, 0.0d),
                new PipelineRun("rrf-selected-model-top50-final", RetrievalBaselineMode.RRF_MODEL_RERANK_50,
                        new RrfConfiguration(0.5d, 1.0d, 10), null, 0.0d));
        List<BaselineRagEvaluationResult> results = new ArrayList<>();
        Map<String, CachedDashScopeReranker.Telemetry> usage = new LinkedHashMap<>();
        for (PipelineRun run : runs) {
            Path rerankCache = run.name().startsWith("rrf-equal")
                    ? cacheRoot.resolve("scifact-" + rerankModel + "-top50.jsonl")
                    : cacheRoot.resolve("scifact-final-selected-" + rerankModel + "-top"
                            + run.mode().modelRerankCandidateLimit() + ".jsonl");
            CachedDashScopeReranker reranker = new CachedDashScopeReranker(
                    rerankApiUrl, rerankApiKey, rerankModel, rerankCache);
            results.add(new BaselineRagRunner(
                    run.name(),
                    new ElasticsearchRetrievalBaselineBackend(
                            run.mode(),
                            cachedIndex,
                            embeddings,
                            documentId -> resolveDocument(documents.get(documentId)),
                            reranker,
                            run.rrf(),
                            RetrievalIntent.API_DEFAULT)).run(cases));
            usage.put(run.name(), reranker.telemetry());
            reranker.compact();
        }
        RagModeComparisonEvaluationResult comparison = RagModeComparisonEvaluationResult.from(
                "beir-scifact-frozen-test-300", results);
        RagModeComparisonReportWriter.writeJson(
                comparison, outputRoot.resolve("scifact-frozen-final-300.json"));
        RagModeComparisonReportWriter.writeMarkdown(
                comparison, outputRoot.resolve("scifact-frozen-final-300.md"));
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                outputRoot.resolve("scifact-frozen-final-300-usage.json").toFile(), Map.of(
                        "rerankModel", rerankModel,
                        "cases", cases.size(),
                        "usage", usage,
                        "routeCache", cachedIndex.telemetry(),
                        "frozenConfiguration", Map.of(
                                "lexicalWeight", 0.5d,
                                "vectorWeight", 1.0d,
                                "rrfRankConstant", 10,
                                "candidateLimit", 50,
                                "queryRewrite", false,
                                "rerankIntent", RetrievalIntent.API_DEFAULT.name())));
        assertThat(results).hasSize(3);
    }

    private void runRerankIntentEvaluation(
            SciFactDataset dataset,
            ElasticsearchKnowledgeSearchIndexClient indexClient,
            CachedBatchDashScopeEmbeddingClient embeddings,
            Map<Long, SciFactDataset.Document> documents,
            ObjectMapper mapper,
            String rerankApiUrl,
            String apiKey,
            String rerankModel) throws Exception {
        List<RagSpikeEvalCase> cases = dataset.trainingCases().subList(600, 650);
        embeddings.warm(cases.stream().map(RagSpikeEvalCase::query).toList());
        CachingKnowledgeSearchIndexClient cachedIndex = new CachingKnowledgeSearchIndexClient(indexClient);
        RrfConfiguration selectedRrf = new RrfConfiguration(0.5d, 1.0d, 10);
        List<BaselineRagEvaluationResult> results = new ArrayList<>();
        Map<String, CachedDashScopeReranker.Telemetry> usage = new LinkedHashMap<>();
        Path cacheRoot = Path.of("target", "rag-eval", "cache");
        for (RetrievalIntent intent : RetrievalIntent.values()) {
            CachedDashScopeReranker reranker = new CachedDashScopeReranker(
                    rerankApiUrl, apiKey, rerankModel,
                    cacheRoot.resolve("scifact-rerank-intent-"
                            + intent.name().toLowerCase(Locale.ROOT) + ".jsonl"));
            BaselineRagEvaluationResult result = new BaselineRagRunner(
                    "rrf-model-top50-" + intent.name().toLowerCase(Locale.ROOT),
                    new ElasticsearchRetrievalBaselineBackend(
                            RetrievalBaselineMode.RRF_MODEL_RERANK_50,
                            cachedIndex,
                            embeddings,
                            documentId -> resolveDocument(documents.get(documentId)),
                            reranker,
                            selectedRrf,
                            intent)).run(cases);
            results.add(result);
            usage.put(intent.name(), reranker.telemetry());
            reranker.compact();
        }
        Path outputRoot = Path.of("target", "rag-eval", "scifact");
        RagModeComparisonEvaluationResult comparison = RagModeComparisonEvaluationResult.from(
                "beir-scifact-train-rerank-intents-50", results);
        RagModeComparisonReportWriter.writeJson(
                comparison, outputRoot.resolve("scifact-rerank-intents-50.json"));
        RagModeComparisonReportWriter.writeMarkdown(
                comparison, outputRoot.resolve("scifact-rerank-intents-50.md"));
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                outputRoot.resolve("scifact-rerank-intents-50-usage.json").toFile(), usage);
        assertThat(results).hasSize(RetrievalIntent.values().length);
    }

    private void runRerankWindowEvaluation(
            SciFactDataset dataset,
            ElasticsearchKnowledgeSearchIndexClient indexClient,
            CachedBatchDashScopeEmbeddingClient embeddings,
            Map<Long, SciFactDataset.Document> documents,
            ObjectMapper mapper,
            String rerankApiUrl,
            String apiKey,
            String rerankModel) throws Exception {
        List<RagSpikeEvalCase> trainingCases = dataset.trainingCases();
        List<RagSpikeEvalCase> cases = trainingCases.subList(700, trainingCases.size());
        embeddings.warm(cases.stream().map(RagSpikeEvalCase::query).toList());
        embeddings.compact();
        CachingKnowledgeSearchIndexClient cachedIndex = new CachingKnowledgeSearchIndexClient(indexClient);
        RrfConfiguration selectedRrf = new RrfConfiguration(0.5d, 1.0d, 10);
        List<RetrievalBaselineMode> modes = List.of(
                RetrievalBaselineMode.RRF_MODEL_RERANK_20,
                RetrievalBaselineMode.RRF_MODEL_RERANK_30,
                RetrievalBaselineMode.RRF_MODEL_RERANK_40,
                RetrievalBaselineMode.RRF_MODEL_RERANK_50);
        List<BaselineRagEvaluationResult> results = new ArrayList<>();
        Map<String, CachedDashScopeReranker.Telemetry> usage = new LinkedHashMap<>();
        Path cacheRoot = Path.of("target", "rag-eval", "cache");
        for (RetrievalBaselineMode mode : modes) {
            int window = mode.modelRerankCandidateLimit();
            CachedDashScopeReranker reranker = new CachedDashScopeReranker(
                    rerankApiUrl, apiKey, rerankModel,
                    cacheRoot.resolve("scifact-selected-rerank-window-top" + window + "-train109.jsonl"));
            results.add(new BaselineRagRunner(
                    "rrf-selected-model-top" + window,
                    new ElasticsearchRetrievalBaselineBackend(
                            mode,
                            cachedIndex,
                            embeddings,
                            documentId -> resolveDocument(documents.get(documentId)),
                            reranker,
                            selectedRrf,
                            RetrievalIntent.API_DEFAULT)).run(cases));
            usage.put("top" + window, reranker.telemetry());
            reranker.compact();
        }
        Path outputRoot = Path.of("target", "rag-eval", "scifact");
        RagModeComparisonEvaluationResult comparison = RagModeComparisonEvaluationResult.from(
                "beir-scifact-train-rerank-window-109", results);
        RagModeComparisonReportWriter.writeJson(
                comparison, outputRoot.resolve("scifact-rerank-window-109.json"));
        RagModeComparisonReportWriter.writeMarkdown(
                comparison, outputRoot.resolve("scifact-rerank-window-109.md"));
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                outputRoot.resolve("scifact-rerank-window-109-usage.json").toFile(), Map.of(
                        "model", rerankModel,
                        "cases", cases.size(),
                        "usage", usage,
                        "routeCache", cachedIndex.telemetry()));
        assertThat(results).hasSize(4);
    }

    private CachedDashScopeReranker modelReranker(
            RetrievalBaselineMode mode,
            CachedDashScopeReranker reranker20,
            CachedDashScopeReranker reranker30,
            CachedDashScopeReranker reranker40,
            CachedDashScopeReranker reranker50) {
        return switch (mode) {
            case RRF_MODEL_RERANK_20 -> reranker20;
            case RRF_MODEL_RERANK_30 -> reranker30;
            case RRF_MODEL_RERANK_40 -> reranker40;
            case RRF_MODEL_RERANK_50 -> reranker50;
            default -> null;
        };
    }

    private void writeRerankUsage(
            ObjectMapper mapper,
            Path output,
            String model,
            CachedDashScopeReranker reranker20,
            CachedDashScopeReranker reranker30,
            CachedDashScopeReranker reranker40,
            CachedDashScopeReranker reranker50) throws Exception {
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of(
                "model", model,
                "top20", reranker20.telemetry(),
                "top30", reranker30.telemetry(),
                "top40", reranker40.telemetry(),
                        "top50", reranker50.telemetry()));
    }

    private void runChunkingEvaluation(
            RestClient restClient,
            ObjectMapper mapper,
            String endpoint,
            int dimensions,
            SciFactDataset dataset,
            CachedBatchDashScopeEmbeddingClient embeddings,
            String rerankApiUrl,
            String rerankApiKey,
            String rerankModel,
            int maxQueries) throws Exception {
        int queryCount = Math.min(300, maxQueries);
        List<RagSpikeEvalCase> cases = dataset.evaluationCases(queryCount);
        Map<Long, SciFactDataset.Document> documents = dataset.documents();
        List<ChunkedDocument> legacyChunks = legacyChunks(documents.values().stream().toList());
        List<ChunkedDocument> structuredChunks = structuredChunks(documents.values().stream().toList());
        List<ChunkingStrategy> strategies = List.of(
                new ChunkingStrategy("legacy-fixed500-no-overlap", "yanban-scifact-chunk-legacy500-v1",
                        legacyChunks),
                new ChunkingStrategy("structured800-overlap120", "yanban-scifact-chunk-structured800o120-v1",
                        structuredChunks));

        embeddings.warm(cases.stream().map(RagSpikeEvalCase::query).toList());
        List<BaselineRagEvaluationResult> results = new ArrayList<>();
        Map<String, Object> usage = new LinkedHashMap<>();
        Map<String, Object> chunkStatistics = new LinkedHashMap<>();
        RrfConfiguration selectedRrf = new RrfConfiguration(0.5d, 1.0d, 10);
        Path cacheRoot = Path.of("target", "rag-eval", "cache");

        for (ChunkingStrategy strategy : strategies) {
            ElasticsearchKnowledgeSearchIndexClient indexClient = ensureChunkedCorpus(
                    restClient, mapper, endpoint, dimensions, strategy, embeddings);
            CachingKnowledgeSearchIndexClient cachedIndex = new CachingKnowledgeSearchIndexClient(indexClient);
            results.add(new BaselineRagRunner(
                    strategy.name() + "-rrf",
                    new ElasticsearchRetrievalBaselineBackend(
                            RetrievalBaselineMode.RRF,
                            cachedIndex,
                            embeddings,
                            documentId -> resolveDocument(documents.get(documentId)),
                            null,
                            selectedRrf,
                            RetrievalIntent.API_DEFAULT)).run(cases));

            CachedDashScopeReranker reranker = new CachedDashScopeReranker(
                    rerankApiUrl,
                    rerankApiKey,
                    rerankModel,
                    cacheRoot.resolve("scifact-chunking-" + strategy.name() + "-top20.jsonl"));
            results.add(new BaselineRagRunner(
                    strategy.name() + "-rrf-model-top20",
                    new ElasticsearchRetrievalBaselineBackend(
                            RetrievalBaselineMode.RRF_MODEL_RERANK_20,
                            cachedIndex,
                            embeddings,
                            documentId -> resolveDocument(documents.get(documentId)),
                            reranker,
                            selectedRrf,
                            RetrievalIntent.API_DEFAULT)).run(cases));
            usage.put(strategy.name(), Map.of(
                    "reranker", reranker.telemetry(),
                    "routeCache", cachedIndex.telemetry()));
            chunkStatistics.put(strategy.name(), chunkStatistics(strategy.chunks(), documents.size()));
            reranker.compact();
        }
        embeddings.compact();

        Path outputRoot = Path.of("target", "rag-eval", "scifact");
        RagModeComparisonEvaluationResult comparison = RagModeComparisonEvaluationResult.from(
                "beir-scifact-chunking-test-" + queryCount, results);
        RagModeComparisonReportWriter.writeJson(
                comparison, outputRoot.resolve("scifact-chunking-" + queryCount + ".json"));
        RagModeComparisonReportWriter.writeMarkdown(
                comparison, outputRoot.resolve("scifact-chunking-" + queryCount + ".md"));
        Files.createDirectories(outputRoot);
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                outputRoot.resolve("scifact-chunking-" + queryCount + "-usage.json").toFile(), Map.of(
                        "queries", queryCount,
                        "embeddingCacheVectors", embeddings.cachedVectorCount(),
                        "rerankModel", rerankModel,
                        "chunkStatistics", chunkStatistics,
                        "usage", usage));
        assertThat(results).hasSize(4);
    }

    private ElasticsearchKnowledgeSearchIndexClient ensureChunkedCorpus(
            RestClient restClient,
            ObjectMapper mapper,
            String endpoint,
            int dimensions,
            ChunkingStrategy strategy,
            CachedBatchDashScopeEmbeddingClient embeddings) throws Exception {
        KnowledgeElasticsearchProperties properties = new KnowledgeElasticsearchProperties();
        properties.setEndpoint(endpoint);
        properties.setIndexName(strategy.indexName());
        properties.setVectorDimensions(dimensions);
        properties.setMigrateLegacyIndex(false);
        ElasticsearchKnowledgeIndexProvisioner provisioner = new ElasticsearchKnowledgeIndexProvisioner(
                restClient, mapper, properties);
        provisioner.ensureReady();

        long existingCount = count(restClient, mapper, strategy.indexName());
        if (existingCount != strategy.chunks().size()) {
            if (existingCount != 0) {
                Request delete = new Request(
                        "POST", "/" + strategy.indexName() + "/_delete_by_query?refresh=true");
                delete.setJsonEntity("{\"query\":{\"match_all\":{}}}");
                EntityUtils.consumeQuietly(restClient.performRequest(delete).getEntity());
            }
            embeddings.warm(strategy.chunks().stream().map(ChunkedDocument::text).toList());
            for (int offset = 0; offset < strategy.chunks().size(); offset += 100) {
                List<ChunkedDocument> batch = strategy.chunks().subList(
                        offset, Math.min(strategy.chunks().size(), offset + 100));
                StringBuilder payload = new StringBuilder();
                for (ChunkedDocument chunk : batch) {
                    String id = chunk.documentId() + "-" + chunk.chunkIndex();
                    payload.append(mapper.writeValueAsString(Map.of(
                                    "index", Map.of("_index", strategy.indexName(), "_id", id))))
                            .append('\n');
                    Map<String, Object> source = new LinkedHashMap<>();
                    source.put("chunkId", Math.addExact(Math.multiplyExact(chunk.documentId(), 1_000L),
                            chunk.chunkIndex()));
                    source.put("documentId", chunk.documentId());
                    source.put("userId", 0L);
                    source.put("isPublic", true);
                    source.put("sourceType", "SCIFACT");
                    source.put("versionStatus", "ACTIVE");
                    source.put("versionNo", 1);
                    source.put("canonicalKey", "scifact:" + chunk.documentId());
                    source.put("chunkIndex", chunk.chunkIndex());
                    source.put("text", chunk.text());
                    source.put("vector", embeddings.embed(chunk.text()));
                    payload.append(mapper.writeValueAsString(source)).append('\n');
                }
                Request bulk = new Request("POST", "/_bulk");
                bulk.setJsonEntity(payload.toString());
                JsonNode response = mapper.readTree(EntityUtils.toString(
                        restClient.performRequest(bulk).getEntity()));
                if (response.path("errors").asBoolean()) {
                    throw new IllegalStateException("SciFact chunked Elasticsearch bulk indexing failed");
                }
            }
            EntityUtils.consumeQuietly(restClient.performRequest(
                    new Request("POST", "/" + strategy.indexName() + "/_refresh")).getEntity());
        }
        if (count(restClient, mapper, strategy.indexName()) != strategy.chunks().size()) {
            throw new IllegalStateException("SciFact chunked corpus count mismatch");
        }
        return new ElasticsearchKnowledgeSearchIndexClient(
                restClient, mapper, properties, provisioner);
    }

    private List<ChunkedDocument> legacyChunks(List<SciFactDataset.Document> documents) {
        List<ChunkedDocument> chunks = new ArrayList<>();
        for (SciFactDataset.Document document : documents) {
            String text = document.embeddingText().replace("\r\n", "\n").trim();
            if (text.isEmpty()) {
                chunks.add(new ChunkedDocument(document.id(), 0, ""));
                continue;
            }
            int chunkIndex = 0;
            for (int start = 0; start < text.length(); start += 500) {
                chunks.add(new ChunkedDocument(document.id(), chunkIndex++,
                        text.substring(start, Math.min(text.length(), start + 500))));
            }
        }
        return List.copyOf(chunks);
    }

    private List<ChunkedDocument> structuredChunks(List<SciFactDataset.Document> documents) {
        KnowledgeChunkingProperties properties = new KnowledgeChunkingProperties();
        properties.setMaxCharacters(800);
        properties.setOverlapCharacters(120);
        KnowledgeTextChunker chunker = new KnowledgeTextChunker(properties);
        List<ChunkedDocument> chunks = new ArrayList<>();
        for (SciFactDataset.Document document : documents) {
            List<String> documentChunks = chunker.split(document.embeddingText());
            for (int index = 0; index < documentChunks.size(); index++) {
                chunks.add(new ChunkedDocument(document.id(), index, documentChunks.get(index)));
            }
        }
        return List.copyOf(chunks);
    }

    private Map<String, Object> chunkStatistics(List<ChunkedDocument> chunks, int documentCount) {
        List<Integer> lengths = chunks.stream().map(chunk -> chunk.text().length()).sorted().toList();
        int p95Index = Math.max(0, (int) Math.ceil(lengths.size() * 0.95d) - 1);
        return Map.of(
                "documents", documentCount,
                "chunks", chunks.size(),
                "chunksPerDocument", (double) chunks.size() / documentCount,
                "averageCharacters", lengths.stream().mapToInt(Integer::intValue).average().orElse(0.0d),
                "p95Characters", lengths.get(p95Index),
                "maxCharacters", lengths.get(lengths.size() - 1));
    }

    private record ChunkedDocument(long documentId, int chunkIndex, String text) {
    }

    private record ChunkingStrategy(String name, String indexName, List<ChunkedDocument> chunks) {
    }

    private void ensureCorpus(
            RestClient restClient,
            ObjectMapper mapper,
            String indexName,
            SciFactDataset dataset,
            CachedBatchDashScopeEmbeddingClient embeddings) throws Exception {
        long count = count(restClient, mapper, indexName);
        if (count == dataset.documents().size()) return;
        if (count != 0) {
            Request delete = new Request("POST", "/" + indexName + "/_delete_by_query?refresh=true");
            delete.setJsonEntity("{\"query\":{\"match_all\":{}}}");
            EntityUtils.consumeQuietly(restClient.performRequest(delete).getEntity());
        }
        List<SciFactDataset.Document> documents = new ArrayList<>(dataset.documents().values());
        embeddings.warm(documents.stream().map(SciFactDataset.Document::embeddingText).toList());
        for (int offset = 0; offset < documents.size(); offset += 100) {
            List<SciFactDataset.Document> batch = documents.subList(
                    offset, Math.min(documents.size(), offset + 100));
            StringBuilder payload = new StringBuilder();
            for (SciFactDataset.Document document : batch) {
                payload.append(mapper.writeValueAsString(Map.of(
                        "index", Map.of("_index", indexName, "_id", Long.toString(document.id())))))
                        .append('\n');
                Map<String, Object> source = new LinkedHashMap<>();
                source.put("chunkId", document.id());
                source.put("documentId", document.id());
                source.put("userId", 0L);
                source.put("isPublic", true);
                source.put("sourceType", "SCIFACT");
                source.put("versionStatus", "ACTIVE");
                source.put("versionNo", 1);
                source.put("canonicalKey", "scifact:" + document.id());
                source.put("chunkIndex", 0);
                source.put("text", document.embeddingText());
                source.put("vector", embeddings.embed(document.embeddingText()));
                payload.append(mapper.writeValueAsString(source)).append('\n');
            }
            Request bulk = new Request("POST", "/_bulk");
            bulk.setJsonEntity(payload.toString());
            JsonNode response = mapper.readTree(EntityUtils.toString(
                    restClient.performRequest(bulk).getEntity()));
            if (response.path("errors").asBoolean()) {
                throw new IllegalStateException("SciFact Elasticsearch bulk indexing failed");
            }
        }
        EntityUtils.consumeQuietly(restClient.performRequest(
                new Request("POST", "/" + indexName + "/_refresh")).getEntity());
        if (count(restClient, mapper, indexName) != dataset.documents().size()) {
            throw new IllegalStateException("SciFact Elasticsearch corpus count mismatch");
        }
    }

    private long count(RestClient restClient, ObjectMapper mapper, String indexName) throws Exception {
        return mapper.readTree(EntityUtils.toString(restClient.performRequest(
                new Request("GET", "/" + indexName + "/_count")).getEntity()))
                .path("count").asLong();
    }

    private KnowledgeSearchResult resolveDocument(SciFactDataset.Document document) {
        if (document == null) return null;
        return new KnowledgeSearchResult(
                document.id(), document.title(), 0, document.embeddingText(), 0.0d,
                true, "SCIFACT", "ACTIVE", null, 1, null,
                "scifact:" + document.id());
    }
}
