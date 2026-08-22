package com.yanban.paper.literature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.config.PaperLiteratureProperties;
import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.service.PaperModelClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class LiteratureRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(LiteratureRecommendationService.class);
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9\\p{IsHan}]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOI_PATTERN = Pattern.compile("(?i)doi\\s*=\\s*[\\{\\\"]([^\\}\\\"]+)[\\}\\\"]");
    private static final Pattern TITLE_PATTERN = Pattern.compile("(?is)title\\s*=\\s*[\\{\\\"]([^\\}\\\"]+)[\\}\\\"]");
    private static final int MAX_QUERIES = 6;
    private static final int MAX_LLM_CANDIDATES = 30;
    private static final int DEFAULT_MAX_QUERIES = 4;
    private static final int MAX_RECOMMENDATION_ANALYSIS_LIMIT = 30;

    private final List<LiteratureSource> sources;
    private final StandaloneLiteratureCardSearchService localCardSearchService;
    private final LiteratureCardCatalogService cardCatalogService;
    private final LiteratureCardAnalysisService cardAnalysisService;
    private final ObjectProvider<PaperModelClient> modelClientProvider;
    private final ObjectMapper objectMapper;
    private final PaperLiteratureProperties properties;

    public LiteratureRecommendationService(List<LiteratureSource> sources,
                                           StandaloneLiteratureCardSearchService localCardSearchService,
                                           LiteratureCardCatalogService cardCatalogService,
                                           LiteratureCardAnalysisService cardAnalysisService,
                                           ObjectProvider<PaperModelClient> modelClientProvider,
                                           ObjectMapper objectMapper,
                                           PaperLiteratureProperties properties) {
        this.sources = sources == null ? List.of() : List.copyOf(sources);
        this.localCardSearchService = localCardSearchService;
        this.cardCatalogService = cardCatalogService;
        this.cardAnalysisService = cardAnalysisService;
        this.modelClientProvider = modelClientProvider;
        this.objectMapper = objectMapper;
        this.properties = properties == null ? new PaperLiteratureProperties() : properties;
    }

    public RecommendationResult recommend(RecommendationRequest request) {
        return recommendInternal(request, LiteratureCardAnalysisService.ProgressListener.NOOP,
                RecommendationProgressListener.NOOP);
    }

    public RecommendationResult recommend(RecommendationRequest request,
                                          LiteratureCardAnalysisService.ProgressListener progressListener) {
        return recommendInternal(request, progressListener, RecommendationProgressListener.NOOP);
    }

    public RecommendationResult recommendWithProgress(RecommendationRequest request,
                                                       RecommendationProgressListener progressListener) {
        return recommendInternal(request, LiteratureCardAnalysisService.ProgressListener.NOOP,
                progressListener == null ? RecommendationProgressListener.NOOP : progressListener);
    }

    private RecommendationResult recommendInternal(RecommendationRequest request,
                                                    LiteratureCardAnalysisService.ProgressListener progressListener,
                                                    RecommendationProgressListener recommendationProgress) {
        long totalStart = System.nanoTime();
        RecommendationRequest normalizedRequest = normalize(request);
        log.info("LiteratureRecommendation start query={} goal={} yearFrom={} topK={} candidateK={} maxQueries={} analysisLimit={} includeBibtex={}",
                abbreviate(normalizedRequest.query(), 120),
                abbreviate(normalizedRequest.goal(), 80),
                normalizedRequest.yearFrom(),
                normalizedRequest.topK(),
                normalizedRequest.candidateK(),
                normalizedRequest.maxQueries(),
                normalizedRequest.analysisLimit(),
                normalizedRequest.includeBibtex());
        if (!StringUtils.hasText(normalizedRequest.query())) {
            log.info("LiteratureRecommendation complete status=empty_query elapsedMs={}", elapsedMs(totalStart));
            return RecommendationResult.empty("empty_query");
        }

        recommendationProgress.onProgress("planning", "正在生成检索词并规划文献来源…");
        long planStart = System.nanoTime();
        SearchPlan searchPlan = buildSearchPlan(normalizedRequest);
        List<String> queries = searchPlan.queries();
        log.info("LiteratureRecommendation searchPlan elapsedMs={} llmPlanned={} queryCount={} queries={}",
                elapsedMs(planStart),
                searchPlan.llmPlanned(),
                queries.size(),
                queries);
        recommendationProgress.onProgress("retrieval", "检索计划已生成，正在从本地文献库、OpenAlex 和 arXiv 检索…");
        long retrieveStart = System.nanoTime();
        RetrievalPool pool = retrieve(queries, normalizedRequest, recommendationProgress);
        log.info("LiteratureRecommendation retrieve elapsedMs={} rawCandidates={} uniqueCandidates={} sourceAttempts={} failures={}",
                elapsedMs(retrieveStart),
                pool.rawCandidateCount(),
                pool.uniqueCandidates().size(),
                pool.sourceAttempts(),
                pool.sourceFailures().size());
        recommendationProgress.onProgress("ranking", "已获得 " + pool.uniqueCandidates().size() + " 篇去重候选，正在排序和分析…");
        long rankStart = System.nanoTime();
        List<LiteratureSearchResult> ranked = rank(pool.uniqueCandidates().values(), normalizedRequest, searchPlan);
        log.info("LiteratureRecommendation rank elapsedMs={} rankedCount={}", elapsedMs(rankStart), ranked.size());
        if (cardAnalysisService != null) {
            long analysisStart = System.nanoTime();
            if (progressListener == null || progressListener == LiteratureCardAnalysisService.ProgressListener.NOOP) {
                cardAnalysisService.analyzeTopCandidates(ranked, normalizedRequest.analysisLimit());
            } else {
                cardAnalysisService.analyzeTopCandidates(ranked, normalizedRequest.analysisLimit(), progressListener);
            }
            log.info("LiteratureRecommendation cardAnalysis elapsedMs={} rankedCount={} analysisLimit={}",
                    elapsedMs(analysisStart),
                    ranked.size(),
                    normalizedRequest.analysisLimit());
        }
        long analysisRerankStart = System.nanoTime();
        ranked = rerankWithCardAnalysis(ranked, normalizedRequest);
        log.info("LiteratureRecommendation analysisRerank elapsedMs={} rankedCount={}", elapsedMs(analysisRerankStart), ranked.size());
        recommendationProgress.onProgress("reranking", "候选分析完成，正在进行最终相关性重排…");
        long llmRerankStart = System.nanoTime();
        LlmRerankResult llmRerank = llmRerank(normalizedRequest, ranked);
        log.info("LiteratureRecommendation llmRerank elapsedMs={} selectedByLlm={} intent={} preferences={}",
                elapsedMs(llmRerankStart),
                llmRerank.selectedIds().size(),
                llmRerank.intent(),
                llmRerank.rankingPreferences());
        long selectStart = System.nanoTime();
        List<LiteratureSearchResult> selected = selectFinal(ranked, llmRerank.selectedIds(), normalizedRequest.topK());
        Set<String> existing = existingIdentityKeys(normalizedRequest.existingBibtex());

        List<RecommendationItem> items = selected.stream()
                .map(result -> toItem(result, existing, normalizedRequest, pool.duplicateSourcesByKey(), pool.duplicateMergeCountsByKey()))
                .toList();
        log.info("LiteratureRecommendation select elapsedMs={} selectedCount={} existingBibtexKeys={}",
                elapsedMs(selectStart),
                selected.size(),
                existing.size());
        log.info("LiteratureRecommendation complete elapsedMs={} rawCandidates={} uniqueCandidates={} rankedCount={} selectedCount={} llmRerankUsed={}",
                elapsedMs(totalStart),
                pool.rawCandidateCount(),
                pool.uniqueCandidates().size(),
                ranked.size(),
                items.size(),
                !llmRerank.selectedIds().isEmpty());
        recommendationProgress.onProgress("completed", "文献检索完成：从 " + pool.rawCandidateCount()
                + " 条结果中去重并选出 " + items.size() + " 篇。");
        return new RecommendationResult(
                normalizedRequest.query(),
                normalizedRequest.goal(),
                queries,
                pool.rawCandidateCount(),
                pool.uniqueCandidates().size(),
                pool.sourceAttempts(),
                pool.sourceFailures(),
                pool.retrievalDiagnostics(),
                ranked.size(),
                items.size(),
                !llmRerank.selectedIds().isEmpty(),
                items,
                ranked.stream().limit(Math.min(30, ranked.size())).map(this::diagnosticItem).toList()
        );
    }

    private RecommendationRequest normalize(RecommendationRequest request) {
        RecommendationRequest source = request == null
                ? new RecommendationRequest("", null, null, null, null, null, null, null, null, null)
                : request;
        int topK = source.topK() == null ? 8 : Math.max(1, Math.min(30, source.topK()));
        int candidateK = source.candidateK() == null ? Math.max(8, Math.min(30, topK * 2)) : Math.max(5, Math.min(50, source.candidateK()));
        int maxQueries = source.maxQueries() == null ? DEFAULT_MAX_QUERIES : Math.max(1, Math.min(MAX_QUERIES, source.maxQueries()));
        int defaultAnalysisLimit = Math.max(0, Math.min(MAX_RECOMMENDATION_ANALYSIS_LIMIT, properties.getMaxAnalysisPerRecommendation()));
        int analysisLimit = source.analysisLimit() == null
                ? defaultAnalysisLimit
                : Math.max(0, Math.min(MAX_RECOMMENDATION_ANALYSIS_LIMIT, source.analysisLimit()));
        return new RecommendationRequest(
                normalizeText(source.query()),
                normalizeText(source.goal()),
                normalizeText(source.claims()),
                source.yearFrom(),
                topK,
                candidateK,
                maxQueries,
                source.includeBibtex() == null || source.includeBibtex(),
                source.existingBibtex(),
                analysisLimit
        );
    }

    private SearchPlan buildSearchPlan(RecommendationRequest request) {
        SearchPlan llmPlan = llmSearchPlan(request);
        if (!llmPlan.queries().isEmpty()) {
            return llmPlan;
        }
        SearchPlan fallback = fallbackSearchPlan(request);
        SanitizeResult sanitizedFallback = sanitizeQueries(fallback.queries(), request, "fallback");
        return new SearchPlan(
                sanitizedFallback.plan().queries(),
                fallback.mustIncludeTerms(),
                fallback.excludeTerms(),
                fallback.llmPlanned()
        );
    }

    private SearchPlan llmSearchPlan(RecommendationRequest request) {
        PaperModelClient modelClient = modelClientProvider == null ? null : modelClientProvider.getIfAvailable();
        if (modelClient == null) {
            log.info("LiteratureRecommendation llmSearchPlan skipped reason=no_model_client");
            return SearchPlan.empty();
        }
        try {
            long start = System.nanoTime();
            String prompt = """
                    Generate a concise academic literature search plan.
                    Return strict JSON only:
                    {
                      "queries":[{"query":"...","language":"en|zh","purpose":"..."}],
                      "mustIncludeTerms":["..."],
                      "excludeTerms":["..."]
                    }
                    Requirements:
                    - Create %d to %d queries total.
                    - Prefer high-quality English academic queries for OpenAlex.
                    - Include at most one Chinese query for local or Chinese sources when useful.
                    - Do not include broad natural-language instructions like "recommend papers".
                    - Use domain-specific terms and exclude neighboring domains that may be confused.
                    - Every query must contain at least one domain entity, technical phrase, disease name, task name, method name, or field-specific concept from the user request.
                    - Never return a query that is only a year, for example "2023".
                    - Never return a query that only contains generic words such as latest, recent, review, survey, progress, advances, research, paper, papers, or year.
                    - If the user asks for latest work, express recency through Year from and keep topic terms in every query.
                    - Before returning JSON, remove any query without a concrete academic topic.

                    User query: %s
                    Goal: %s
                    Claims or evidence needs: %s
                    Year from: %s
                    """.formatted(
                    Math.min(2, request.maxQueries()),
                    request.maxQueries(),
                    request.query(),
                    nullToEmpty(request.goal()),
                    nullToEmpty(request.claims()),
                    request.yearFrom() == null ? "" : request.yearFrom());
            String text = modelClient.complete("You are an academic search-query planner. Return JSON only.", prompt, 0.1, 2048);
            SearchPlan rawPlan = parseSearchPlan(text, request);
            SanitizeResult initialSanitized = sanitizeSearchPlan(rawPlan, request, "llm_initial");
            SearchPlan plan = initialSanitized.plan();
            if (shouldRepairSearchPlan(initialSanitized, request)) {
                SearchPlan repaired = repairSearchPlan(modelClient, request, text);
                if (!repaired.queries().isEmpty()) {
                    log.info("LiteratureRecommendation searchPlanRepairUsed queryCount={}", repaired.queries().size());
                    plan = repaired;
                }
            } else if (initialSanitized.droppedCount() > 0) {
                log.info("LiteratureRecommendation searchPlanRepairSkipped reason=enough_sanitized_queries queryCount={} droppedCount={}",
                        plan.queries().size(),
                        initialSanitized.droppedCount());
            }
            log.info("LiteratureRecommendation llmSearchPlan complete elapsedMs={} queryCount={} responseChars={}",
                    elapsedMs(start),
                    plan.queries().size(),
                    text == null ? 0 : text.length());
            return plan;
        } catch (Exception ex) {
            log.warn("LiteratureRecommendation llmSearchPlan failed error={}", defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
            return SearchPlan.empty();
        }
    }

    private SearchPlan repairSearchPlan(PaperModelClient modelClient, RecommendationRequest request, String previousResponse) {
        try {
            String prompt = """
                    Your previous literature search plan contained invalid generic queries.
                    Regenerate strict JSON only:
                    {
                      "queries":[{"query":"...","language":"en|zh","purpose":"..."}],
                      "mustIncludeTerms":["..."],
                      "excludeTerms":["..."]
                    }
                    Rules:
                    - Create 2 to %d queries.
                    - Every query must include a concrete academic topic from the user request.
                    - Do not output pure years such as "2023".
                    - Do not output generic-only queries such as "latest research", "recent papers", or "review 2024".
                    - Keep recency as a filter, not as a standalone query.

                    User query: %s
                    Goal: %s
                    Claims or evidence needs: %s
                    Year from: %s
                    Previous invalid response:
                    %s
                    """.formatted(
                    request.maxQueries(),
                    request.query(),
                    nullToEmpty(request.goal()),
                    nullToEmpty(request.claims()),
                    request.yearFrom() == null ? "" : request.yearFrom(),
                    nullToEmpty(previousResponse));
            String text = modelClient.complete("Repair the academic search-query plan. Return JSON only.", prompt, 0.1, 1024);
            return sanitizeSearchPlan(parseSearchPlan(text, request), request, "llm_repair").plan();
        } catch (Exception ex) {
            log.warn("LiteratureRecommendation searchPlanRepair failed error={}", defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
            return SearchPlan.empty();
        }
    }

    private SearchPlan parseSearchPlan(String text, RecommendationRequest request) throws Exception {
        int start = text == null ? -1 : text.indexOf('{');
        int end = text == null ? -1 : text.lastIndexOf('}');
        if (start < 0 || end <= start) return SearchPlan.empty();
        JsonNode root = objectMapper.readTree(text.substring(start, end + 1));
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        JsonNode queryNodes = root.path("queries");
        if (queryNodes.isArray()) {
            for (JsonNode item : queryNodes) {
                addQuery(queries, academicQuery(item.path("query").asText("")));
                if (queries.size() >= request.maxQueries()) break;
            }
        }
        if (queries.isEmpty()) {
            return SearchPlan.empty();
        }
        return new SearchPlan(
                queries.stream().limit(request.maxQueries()).toList(),
                stringArray(root.path("mustIncludeTerms")),
                stringArray(root.path("excludeTerms")),
                true
        );
    }

    private boolean shouldRepairSearchPlan(SanitizeResult sanitized, RecommendationRequest request) {
        int queryCount = sanitized.plan().queries().size();
        if (queryCount == 0) return true;
        int minimumUsefulQueries = request.maxQueries() <= 1 ? 1 : 2;
        return sanitized.droppedCount() > 0 && queryCount < minimumUsefulQueries;
    }

    private SearchPlan fallbackSearchPlan(RecommendationRequest request) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        addQuery(queries, academicQuery(request.query()));
        String allText = normalizeText(request.query() + " " + nullToEmpty(request.goal()) + " " + nullToEmpty(request.claims()));
        addQuery(queries, translatedAcademicQuery(allText));
        for (String claim : splitClaims(request.claims())) {
            addQuery(queries, academicQuery(claim));
        }
        String combined = academicQuery(request.query() + " " + nullToEmpty(request.goal()));
        if (StringUtils.hasText(combined) && !combined.equals(request.query())) {
            addQuery(queries, combined);
        }
        SanitizeResult sanitized = sanitizeQueries(queries.stream().limit(request.maxQueries()).toList(), request, "fallback");
        return new SearchPlan(
                sanitized.plan().queries(),
                keyPhrases(allText),
                List.of(),
                false
        );
    }

    private SanitizeResult sanitizeSearchPlan(SearchPlan plan, RecommendationRequest request, String stage) {
        if (plan == null || plan.queries().isEmpty()) {
            return new SanitizeResult(SearchPlan.empty(), 0);
        }
        SanitizeResult sanitized = sanitizeQueries(plan.queries(), request, stage);
        return new SanitizeResult(new SearchPlan(
                sanitized.plan().queries(),
                plan.mustIncludeTerms(),
                plan.excludeTerms(),
                plan.llmPlanned()
        ), sanitized.droppedCount());
    }

    private SanitizeResult sanitizeQueries(Iterable<String> queries, RecommendationRequest request, String stage) {
        LinkedHashSet<String> kept = new LinkedHashSet<>();
        int dropped = 0;
        for (String query : queries) {
            String normalized = normalizeText(query);
            String reason = unusableQueryReason(normalized, request);
            if (reason != null) {
                dropped++;
                log.info("LiteratureRecommendation searchPlanQueryDropped stage={} query={} reason={}",
                        stage,
                        abbreviate(normalized, 120),
                        reason);
                continue;
            }
            kept.add(normalized);
            if (kept.size() >= request.maxQueries()) break;
        }
        return new SanitizeResult(new SearchPlan(
                List.copyOf(kept),
                List.of(),
                List.of(),
                false
        ), dropped);
    }

    private String unusableQueryReason(String query, RecommendationRequest request) {
        if (!StringUtils.hasText(query)) return "empty";
        String normalized = query.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\p{IsHan}]+", " ").trim();
        if (normalized.matches("\\d{4}")) return "year_only";
        List<String> tokens = List.of(normalized.split("\\s+")).stream()
                .filter(StringUtils::hasText)
                .toList();
        Set<String> generic = genericQueryTerms();
        List<String> nonGeneric = tokens.stream()
                .filter(token -> !generic.contains(token))
                .filter(token -> !token.matches("\\d{4}"))
                .toList();
        boolean hasHan = normalized.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
        if (nonGeneric.isEmpty()) return "generic_only";
        if (!hasHan && nonGeneric.size() < 2 && nonGeneric.stream().noneMatch(token -> token.length() >= 4)) {
            return "too_short";
        }
        if (!hasTopicOverlap(nonGeneric, request) && !hasHan) {
            return "no_request_topic_overlap";
        }
        return null;
    }

    private boolean hasTopicOverlap(List<String> nonGenericTokens, RecommendationRequest request) {
        Set<String> requestTerms = queryTerms(request.query() + " " + nullToEmpty(request.goal()) + " " + nullToEmpty(request.claims())
                + " " + translatedAcademicQuery(request.query() + " " + nullToEmpty(request.goal()) + " " + nullToEmpty(request.claims())));
        if (requestTerms.isEmpty()) return true;
        for (String token : nonGenericTokens) {
            String lower = token.toLowerCase(Locale.ROOT);
            if (requestTerms.contains(lower)) return true;
            for (String requestTerm : requestTerms) {
                if (lower.length() >= 4 && requestTerm.length() >= 4
                        && (lower.contains(requestTerm) || requestTerm.contains(lower))) {
                    return true;
                }
            }
        }
        return false;
    }

    private RetrievalPool retrieve(List<String> queries, RecommendationRequest request,
                                   RecommendationProgressListener progressListener) {
        Map<String, LiteratureCandidate> unique = new LinkedHashMap<>();
        Map<String, String> keyAliases = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> duplicateSources = new LinkedHashMap<>();
        Map<String, Integer> duplicateMergeCounts = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        List<RetrievalDiagnosticItem> diagnostics = new ArrayList<>();
        int rawCount = 0;
        int sourceAttempts = 0;
        int localLimit = Math.max(request.topK(), Math.min(12, request.candidateK()));
        int totalAttempts = queries.size() * (sources.size() + 1);
        int completedAttempts = 0;

        for (String query : queries) {
            List<LiteratureCandidate> localCandidates;
            boolean localFailed = false;
            String localMessage = "";
            long localStart = System.nanoTime();
            try {
                localCandidates = safeCandidates(localCardSearchService.search(query, localLimit, request.yearFrom()));
            } catch (Exception ex) {
                localFailed = true;
                localMessage = defaultString(ex.getMessage(), ex.getClass().getSimpleName());
                failures.add("local_card: " + localMessage);
                localCandidates = List.of();
            }
            rawCount += localCandidates.size();
            int localAccepted = 0;
            for (LiteratureCandidate candidate : localCandidates) {
                if (!validCandidate(candidate, request.yearFrom())) continue;
                localAccepted++;
                putUniqueCandidate(unique, keyAliases, duplicateSources, duplicateMergeCounts, candidate);
            }
            log.info("LiteratureRecommendation retrieval source=local_card elapsedMs={} query={} returned={} accepted={} failed={} uniqueSoFar={} message={}",
                    elapsedMs(localStart),
                    abbreviate(query, 120),
                    localCandidates.size(),
                    localAccepted,
                    localFailed,
                    unique.size(),
                    abbreviate(localMessage, 120));
            diagnostics.add(new RetrievalDiagnosticItem(query, "local_card", localCandidates.size(), localAccepted, localFailed, localMessage));
            completedAttempts++;
            progressListener.onProgress("retrieval", "文献检索进度 " + completedAttempts + "/" + totalAttempts
                    + "：本地文献库已返回 " + localCandidates.size() + " 条。");
            for (LiteratureSource source : sources) {
                sourceAttempts++;
                List<LiteratureCandidate> candidates;
                boolean failed = false;
                String message = "";
                int limit = sourceLimit(source.name(), request.candidateK());
                long sourceStart = System.nanoTime();
                try {
                    candidates = source.search(query, limit);
                } catch (Exception ex) {
                    failed = true;
                    message = defaultString(ex.getMessage(), ex.getClass().getSimpleName());
                    failures.add(source.name() + ": " + message);
                    candidates = List.of();
                }
                List<LiteratureCandidate> safeCandidates = safeCandidates(candidates);
                rawCount += safeCandidates.size();
                int accepted = 0;
                for (LiteratureCandidate candidate : safeCandidates) {
                    if (!validCandidate(candidate, request.yearFrom())) continue;
                    accepted++;
                    putUniqueCandidate(unique, keyAliases, duplicateSources, duplicateMergeCounts, candidate);
                }
                log.info("LiteratureRecommendation retrieval source={} elapsedMs={} query={} limit={} returned={} accepted={} failed={} uniqueSoFar={} message={}",
                        source.name(),
                        elapsedMs(sourceStart),
                        abbreviate(query, 120),
                        limit,
                        safeCandidates.size(),
                        accepted,
                        failed,
                        unique.size(),
                        abbreviate(message, 120));
                diagnostics.add(new RetrievalDiagnosticItem(query, source.name(), safeCandidates.size(), accepted, failed, message));
                completedAttempts++;
                progressListener.onProgress("retrieval", "文献检索进度 " + completedAttempts + "/" + totalAttempts
                        + "：" + source.name() + " 已返回 " + safeCandidates.size() + " 条。");
            }
        }
        Map<String, List<String>> duplicateSourcesByKey = new LinkedHashMap<>();
        duplicateSources.forEach((key, values) -> duplicateSourcesByKey.put(key, List.copyOf(values)));
        return new RetrievalPool(unique, rawCount, sourceAttempts, failures, diagnostics, duplicateSourcesByKey, Map.copyOf(duplicateMergeCounts));
    }

    @FunctionalInterface
    public interface RecommendationProgressListener {
        RecommendationProgressListener NOOP = (stage, message) -> { };

        void onProgress(String stage, String message);
    }

    private void putUniqueCandidate(Map<String, LiteratureCandidate> unique,
                                    Map<String, String> keyAliases,
                                    Map<String, LinkedHashSet<String>> duplicateSources,
                                    Map<String, Integer> duplicateMergeCounts,
                                    LiteratureCandidate candidate) {
        List<String> keys = identityKeys(candidate);
        String existingPrimaryKey = keys.stream()
                .map(keyAliases::get)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
        if (StringUtils.hasText(existingPrimaryKey)) {
            duplicateSources.computeIfAbsent(existingPrimaryKey, ignored -> new LinkedHashSet<>()).add(sourceLabel(candidate));
            duplicateMergeCounts.merge(existingPrimaryKey, 1, Integer::sum);
            keys.forEach(key -> keyAliases.putIfAbsent(key, existingPrimaryKey));
            return;
        }
        String primaryKey = keys.get(0);
        keys.forEach(key -> keyAliases.put(key, primaryKey));
        duplicateSources.computeIfAbsent(primaryKey, ignored -> new LinkedHashSet<>()).add(sourceLabel(candidate));
        unique.put(primaryKey, candidate);
    }

    private int sourceLimit(String sourceName, int candidateK) {
        String normalized = nullToEmpty(sourceName).toLowerCase(Locale.ROOT);
        if (normalized.contains("openalex")) {
            int maxOpenAlex = Math.max(1, Math.min(50, properties.getOpenAlexMaxResultsPerQuery()));
            return Math.max(1, Math.min(maxOpenAlex, candidateK));
        }
        if (normalized.contains("arxiv")) {
            return Math.max(5, Math.min(20, (int) Math.ceil(candidateK * 0.6)));
        }
        return candidateK;
    }

    private List<LiteratureSearchResult> rank(Iterable<LiteratureCandidate> candidates, RecommendationRequest request, SearchPlan searchPlan) {
        List<LiteratureSearchResult> ranked = new ArrayList<>();
        int index = 0;
        for (LiteratureCandidate candidate : candidates) {
            long upsertStart = System.nanoTime();
            LiteratureCard card = cardCatalogService.upsertCard(candidate);
            long upsertMs = elapsedMs(upsertStart);
            double score = ruleScore(candidate, request, searchPlan);
            ranked.add(new LiteratureSearchResult(card, score, narrativeRole(candidate, request), "recommendation", false, candidate.sourceQuery()));
            if (upsertMs >= 500) {
                log.info("LiteratureRecommendation cardUpsertSlow elapsedMs={} index={} source={} title={}",
                        upsertMs,
                        index,
                        candidate.source(),
                        abbreviate(candidate.title(), 120));
            }
            index++;
        }
        return ranked.stream()
                .sorted(Comparator.comparingDouble(LiteratureSearchResult::relevanceScore).reversed())
                .toList();
    }

    private List<LiteratureSearchResult> rerankWithCardAnalysis(List<LiteratureSearchResult> ranked, RecommendationRequest request) {
        Set<String> requestTerms = queryTerms(request.query() + " " + nullToEmpty(request.goal()) + " " + nullToEmpty(request.claims()));
        return ranked.stream()
                .map(result -> new LiteratureSearchResult(
                        result.card(),
                        Math.max(0, Math.min(1.0, result.relevanceScore() + analysisAdjustment(result.card(), requestTerms))),
                        result.narrativeRole(),
                        result.ladderNode(),
                        result.selected(),
                        result.sourceQuery()))
                .sorted(Comparator.comparingDouble(LiteratureSearchResult::relevanceScore).reversed())
                .toList();
    }

    private LlmRerankResult llmRerank(RecommendationRequest request, List<LiteratureSearchResult> ranked) {
        PaperModelClient modelClient = modelClientProvider == null ? null : modelClientProvider.getIfAvailable();
        if (modelClient == null || ranked.isEmpty()) {
            log.info("LiteratureRecommendation llmRerank skipped reason={} rankedCount={}",
                    modelClient == null ? "no_model_client" : "empty_ranked",
                    ranked.size());
            return LlmRerankResult.empty();
        }
        int candidateLimit = llmRerankCandidateLimit(request);
        List<LiteratureSearchResult> candidates = ranked.stream().limit(candidateLimit).toList();
        Set<Long> allowedIds = new LinkedHashSet<>();
        candidates.forEach(item -> allowedIds.add(item.card().getId()));
        try {
            long start = System.nanoTime();
            String prompt = """
                    Rerank candidate academic papers for the user's literature request.
                    Return strict JSON only:
                    {
                      "intent":"classic|survey|latest|method|benchmark|citation_support|mixed",
                      "rankingPreferences":["..."],
                      "selected":[{"cardId":123,"reason":"..."}],
                      "rejected":[{"cardId":456,"reason":"..."}]
                    }
                    Select and ORDER at most %d papers.
                    You must only use cardId values from Candidate cards JSON.

                    Ranking guidance:
                    - First infer the user's intent from query, goal, and claims.
                    - For classic/foundational requests, prioritize representative, high-impact, broad-coverage works over narrow implementation details.
                    - For survey requests, prioritize survey/review papers, then foundational papers.
                    - For latest requests, prioritize recent papers only when they are directly relevant.
                    - For method requests, prioritize concrete method papers.
                    - For benchmark/evaluation requests, prioritize benchmark and metric papers.
                    - For citation support, prioritize papers that directly support the claim.
                    - Penalize neighboring but different domains, even if they share words.
                    - Prefer papers covering multiple core concepts in the request over papers matching only one local phrase.

                    User query: %s
                    Goal: %s
                    Claims or citation needs: %s
                    Candidate cards JSON:
                    %s
                    """.formatted(
                    request.topK(),
                    request.query(),
                    nullToEmpty(request.goal()),
                    nullToEmpty(request.claims()),
                    candidateCardsJson(candidates));
            String text = modelClient.complete("You are a strict academic literature reranker. Return JSON only.", prompt, 0.1, 4096);
            LlmRerankResult result = parseLlmRerankResult(text, allowedIds, request.topK());
            log.info("LiteratureRecommendation llmRerankModel complete elapsedMs={} candidateCount={} candidateLimit={} selectedCount={} responseChars={}",
                    elapsedMs(start),
                    candidates.size(),
                    candidateLimit,
                    result.selectedIds().size(),
                    text == null ? 0 : text.length());
            return result;
        } catch (Exception ex) {
            log.warn("LiteratureRecommendation llmRerankModel failed candidateCount={} error={}",
                    candidates.size(),
                    defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
            return LlmRerankResult.empty();
        }
    }

    private int llmRerankCandidateLimit(RecommendationRequest request) {
        if (request.analysisLimit() != null && request.analysisLimit() >= MAX_RECOMMENDATION_ANALYSIS_LIMIT) {
            return MAX_LLM_CANDIDATES;
        }
        return Math.min(MAX_LLM_CANDIDATES, Math.max(15, Math.min(25, request.topK() * 2)));
    }

    private List<LiteratureSearchResult> selectFinal(List<LiteratureSearchResult> ranked, Set<Long> llmSelected, int topK) {
        List<LiteratureSearchResult> selected = new ArrayList<>();
        Set<Long> selectedIds = new LinkedHashSet<>();
        if (!llmSelected.isEmpty()) {
            Map<Long, LiteratureSearchResult> rankedById = new LinkedHashMap<>();
            for (LiteratureSearchResult result : ranked) {
                rankedById.putIfAbsent(result.card().getId(), result);
            }
            for (Long selectedId : llmSelected) {
                if (selected.size() >= topK) break;
                LiteratureSearchResult result = rankedById.get(selectedId);
                if (result != null && selectedIds.add(selectedId)) {
                    selected.add(markSelected(result));
                }
            }
        }
        for (LiteratureSearchResult result : ranked) {
            if (selected.size() >= topK) break;
            if (result.relevanceScore() < 0.15 && selected.size() >= Math.max(3, topK / 2)) continue;
            if (selectedIds.add(result.card().getId())) {
                selected.add(markSelected(result));
            }
        }
        return selected;
    }

    private LiteratureSearchResult markSelected(LiteratureSearchResult result) {
        return new LiteratureSearchResult(result.card(), result.relevanceScore(), result.narrativeRole(), result.ladderNode(), true, result.sourceQuery());
    }

    private RecommendationItem toItem(LiteratureSearchResult result,
                                      Set<String> existing,
                                      RecommendationRequest request,
                                      Map<String, List<String>> duplicateSourcesByKey,
                                      Map<String, Integer> duplicateMergeCountsByKey) {
        LiteratureCard card = result.card();
        String deduplicationKey = cardIdentityKey(card);
        boolean alreadyPresent = existing.contains(deduplicationKey) || existing.contains(titleKey(card.getTitle()));
        List<String> duplicateSources = duplicateSourcesByKey.getOrDefault(deduplicationKey, List.of());
        int duplicateMergeCount = duplicateMergeCountsByKey.getOrDefault(deduplicationKey, 0);
        List<String> metadataRiskNotes = metadataRiskNotes(card);
        return new RecommendationItem(
                card.getId(),
                card.getTitle(),
                parseList(card.getAuthors()),
                card.getPublicationYear(),
                card.getVenue(),
                card.getDoi(),
                card.getArxivId(),
                card.getOpenAlexId(),
                card.getUrl(),
                card.getPdfUrl(),
                card.getCitationCount(),
                result.relevanceScore(),
                result.narrativeRole(),
                result.sourceQuery(),
                alreadyPresent,
                recommendationReason(result, request, alreadyPresent),
                request.includeBibtex() ? bibtex(card) : null,
                matchTarget(request, result),
                rankingBasis(result, request, card),
                citationStatus(card, request.includeBibtex(), metadataRiskNotes),
                metadataRiskLevel(metadataRiskNotes),
                metadataRiskNotes,
                deduplicationKey,
                duplicateMergeCount > 0 ? "MERGED_DUPLICATES" : "UNIQUE",
                duplicateSources,
                duplicateMergeCount
        );
    }

    private RecommendationDiagnosticItem diagnosticItem(LiteratureSearchResult result) {
        LiteratureCard card = result.card();
        return new RecommendationDiagnosticItem(card.getId(), card.getTitle(), result.relevanceScore(), result.sourceQuery(), result.narrativeRole());
    }

    private String recommendationReason(LiteratureSearchResult result, RecommendationRequest request, boolean alreadyPresent) {
        StringBuilder reason = new StringBuilder();
        reason.append("Matches the request");
        if (StringUtils.hasText(request.goal())) {
            reason.append(" and goal");
        }
        if (StringUtils.hasText(result.narrativeRole())) {
            reason.append("; role=").append(result.narrativeRole());
        }
        if (alreadyPresent) {
            reason.append("; already present in uploaded bibliography");
        }
        reason.append("; score=").append(String.format(Locale.ROOT, "%.3f", result.relevanceScore()));
        return reason.toString();
    }

    private String matchTarget(RecommendationRequest request, LiteratureSearchResult result) {
        if (StringUtils.hasText(result.sourceQuery())) {
            return result.sourceQuery();
        }
        if (StringUtils.hasText(request.query())) {
            return request.query();
        }
        return "topic_search";
    }

    private List<String> rankingBasis(LiteratureSearchResult result, RecommendationRequest request, LiteratureCard card) {
        List<String> basis = new ArrayList<>();
        basis.add("score=" + String.format(Locale.ROOT, "%.3f", result.relevanceScore()));
        if (StringUtils.hasText(result.narrativeRole())) {
            basis.add("role=" + result.narrativeRole());
        }
        if (StringUtils.hasText(result.sourceQuery())) {
            basis.add("matched_query=" + result.sourceQuery());
        }
        if (card.getCitationCount() != null && card.getCitationCount() > 0) {
            basis.add("citation_count=" + card.getCitationCount());
        }
        if (StringUtils.hasText(card.getDoi()) || StringUtils.hasText(card.getArxivId()) || StringUtils.hasText(card.getUrl())) {
            basis.add("stable_identifier_or_url_available");
        }
        if (StringUtils.hasText(request.goal())) {
            basis.add("goal_considered");
        }
        return basis;
    }

    private String citationStatus(LiteratureCard card, boolean includeBibtex, List<String> riskNotes) {
        if (!includeBibtex) {
            return "BIBTEX_NOT_REQUESTED_VERIFY_METADATA";
        }
        if (riskNotes.isEmpty()) {
            return "BIBTEX_READY_VERIFY_BEFORE_SUBMISSION";
        }
        return "BIBTEX_NEEDS_METADATA_REVIEW";
    }

    private String metadataRiskLevel(List<String> riskNotes) {
        if (riskNotes.isEmpty()) return "LOW";
        boolean high = riskNotes.stream().anyMatch(note ->
                note.contains("stable identifier") || note.contains("authors") || note.contains("publication year"));
        return high ? "HIGH" : "MEDIUM";
    }

    private List<String> metadataRiskNotes(LiteratureCard card) {
        List<String> notes = new ArrayList<>();
        if (card == null) {
            return List.of("missing literature metadata");
        }
        if (!StringUtils.hasText(card.getTitle())) notes.add("missing title");
        if (parseList(card.getAuthors()).isEmpty()) notes.add("missing authors");
        if (card.getPublicationYear() == null) notes.add("missing publication year");
        if (!StringUtils.hasText(card.getVenue())) notes.add("missing venue/source");
        if (!StringUtils.hasText(card.getDoi())
                && !StringUtils.hasText(card.getArxivId())
                && !StringUtils.hasText(card.getOpenAlexId())
                && !StringUtils.hasText(card.getS2Id())
                && !StringUtils.hasText(card.getUrl())) {
            notes.add("missing stable identifier or URL");
        }
        if (!StringUtils.hasText(card.getAbstractText())) notes.add("missing abstract");
        return List.copyOf(notes);
    }

    private double ruleScore(LiteratureCandidate candidate, RecommendationRequest request, SearchPlan searchPlan) {
        String requestText = request.query() + " " + nullToEmpty(request.goal()) + " " + nullToEmpty(request.claims());
        Set<String> queryTerms = queryTerms(academicQuery(requestText));
        LinkedHashSet<String> phraseSet = new LinkedHashSet<>(keyPhrases(requestText));
        phraseSet.addAll(safeList(searchPlan.mustIncludeTerms()).stream().map(String::toLowerCase).toList());
        List<String> phrases = List.copyOf(phraseSet);
        String title = nullToEmpty(candidate.title()).toLowerCase(Locale.ROOT);
        String abstractText = nullToEmpty(candidate.abstractText()).toLowerCase(Locale.ROOT);
        String fields = String.join(" ", safeList(candidate.fieldsOfStudy())).toLowerCase(Locale.ROOT);

        double titleOverlap = termOverlap(title, queryTerms);
        double abstractOverlap = termOverlap(abstractText, queryTerms);
        double fieldOverlap = termOverlap(fields, queryTerms);
        double phraseScore = phraseCoverage(title + " " + abstractText, phrases);
        double score = titleOverlap * 0.42 + abstractOverlap * 0.20 + fieldOverlap * 0.06 + phraseScore * 0.24;
        if ("local_card".equals(candidate.source())) score += 0.03;
        if (StringUtils.hasText(candidate.doi()) || StringUtils.hasText(candidate.arxivId())) score += 0.03;
        if (StringUtils.hasText(candidate.abstractText())) score += 0.02;
        if (candidate.citationCount() != null && candidate.citationCount() > 0) {
            score += Math.min(0.06, Math.log10(candidate.citationCount() + 1) / 70.0);
        }
        int currentYear = Year.now().getValue();
        if (candidate.year() != null) {
            if (candidate.year() >= currentYear - 5) score += 0.04;
            else if (candidate.year() >= currentYear - 10) score += 0.02;
        }
        score += phraseCoverage(title + " " + abstractText, safeList(searchPlan.mustIncludeTerms())) * 0.08;
        score -= phraseCoverage(title + " " + abstractText, safeList(searchPlan.excludeTerms())) * 0.18;
        score -= domainMismatchPenalty(requestText, title + " " + abstractText);
        return Math.max(0, Math.min(1.0, score));
    }

    private double analysisAdjustment(LiteratureCard card, Set<String> requestTerms) {
        if (card == null || !StringUtils.hasText(card.getAnalysisJson())) return 0;
        String text = card.getAnalysisJson().toLowerCase(Locale.ROOT);
        double overlap = termOverlap(text, requestTerms);
        double adjustment = overlap * 0.12;
        if (text.contains("\"strength\":\"HIGH\"") || text.contains("\"strength\": \"HIGH\"")) adjustment += 0.03;
        if (text.contains("notUseFor") && overlap < 0.12) adjustment -= 0.06;
        return adjustment;
    }

    private String candidateCardsJson(List<LiteratureSearchResult> candidates) throws Exception {
        List<Map<String, Object>> values = new ArrayList<>();
        for (LiteratureSearchResult result : candidates) {
            LiteratureCard card = result.card();
            values.add(new LinkedHashMap<>(Map.of(
                    "cardId", card.getId(),
                    "ruleScore", result.relevanceScore(),
                    "sourceQuery", defaultString(result.sourceQuery(), ""),
                    "title", defaultString(card.getTitle(), ""),
                    "year", card.getPublicationYear() == null ? "" : card.getPublicationYear(),
                    "venue", defaultString(card.getVenue(), ""),
                    "doi", defaultString(card.getDoi(), ""),
                    "citationCount", card.getCitationCount() == null ? 0 : card.getCitationCount(),
                    "analysis", parseObject(card.getAnalysisJson())
            )));
        }
        return objectMapper.writeValueAsString(values);
    }

    private LlmRerankResult parseLlmRerankResult(String text, Set<Long> allowedIds, int limit) throws Exception {
        int start = text == null ? -1 : text.indexOf('{');
        int end = text == null ? -1 : text.lastIndexOf('}');
        if (start < 0 || end <= start) return LlmRerankResult.empty();
        JsonNode root = objectMapper.readTree(text.substring(start, end + 1));
        JsonNode selected = root.path("selected");
        Set<Long> ids = new LinkedHashSet<>();
        if (selected.isArray()) {
            for (JsonNode item : selected) {
                if (ids.size() >= limit) break;
                Long id = item.path("cardId").canConvertToLong() ? item.path("cardId").asLong() : null;
                if (id != null && allowedIds.contains(id)) {
                    ids.add(id);
                }
            }
        }
        return new LlmRerankResult(
                ids,
                root.path("intent").asText(""),
                stringArray(root.path("rankingPreferences"))
        );
    }

    private Object parseObject(String json) {
        if (!StringUtils.hasText(json)) return Map.of();
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ex) {
            return Map.of("raw", json);
        }
    }

    private List<String> stringArray(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = normalizeText(item.asText(""));
            if (StringUtils.hasText(value)) values.add(value);
        }
        return values;
    }

    private boolean validCandidate(LiteratureCandidate candidate, Integer yearFrom) {
        if (candidate == null || !StringUtils.hasText(candidate.title())) return false;
        return yearFrom == null || candidate.year() == null || candidate.year() >= yearFrom;
    }

    private String narrativeRole(LiteratureCandidate candidate, RecommendationRequest request) {
        String text = (candidate.title() + " " + nullToEmpty(candidate.abstractText()) + " " + nullToEmpty(request.goal())).toLowerCase(Locale.ROOT);
        if (text.contains("survey") || text.contains("review")) return "survey";
        if (text.contains("benchmark") || text.contains("comparison")) return "baseline";
        if (text.contains("method") || text.contains("model") || text.contains("optimization")) return "method";
        return "evidence";
    }

    private Set<String> existingIdentityKeys(String bibtex) {
        Set<String> keys = new LinkedHashSet<>();
        if (!StringUtils.hasText(bibtex)) return keys;
        Matcher doiMatcher = DOI_PATTERN.matcher(bibtex);
        while (doiMatcher.find()) {
            keys.add("doi:" + normalizeDoi(doiMatcher.group(1)));
        }
        Matcher titleMatcher = TITLE_PATTERN.matcher(bibtex);
        while (titleMatcher.find()) {
            keys.add(titleKey(titleMatcher.group(1)));
        }
        return keys;
    }

    private String identityKey(LiteratureCandidate candidate) {
        return identityKeys(candidate).get(0);
    }

    private List<String> identityKeys(LiteratureCandidate candidate) {
        List<String> keys = new ArrayList<>();
        if (StringUtils.hasText(candidate.doi())) keys.add("doi:" + normalizeDoi(candidate.doi()));
        if (StringUtils.hasText(candidate.arxivId())) keys.add("arxiv:" + candidate.arxivId());
        if (StringUtils.hasText(candidate.openAlexId())) keys.add("openalex:" + candidate.openAlexId());
        if (StringUtils.hasText(candidate.s2Id())) keys.add("s2:" + candidate.s2Id());
        keys.add(titleKey(candidate.title()));
        return keys;
    }

    private String sourceLabel(LiteratureCandidate candidate) {
        if (candidate == null || !StringUtils.hasText(candidate.source())) {
            return "unknown";
        }
        return candidate.source();
    }

    private String cardIdentityKey(LiteratureCard card) {
        if (card == null) return "";
        if (StringUtils.hasText(card.getDoi())) return "doi:" + normalizeDoi(card.getDoi());
        if (StringUtils.hasText(card.getArxivId())) return "arxiv:" + card.getArxivId();
        if (StringUtils.hasText(card.getOpenAlexId())) return "openalex:" + card.getOpenAlexId();
        if (StringUtils.hasText(card.getS2Id())) return "s2:" + card.getS2Id();
        return titleKey(card.getTitle());
    }

    private String titleKey(String title) {
        return "title:" + titleHash(title);
    }

    private String titleHash(String title) {
        try {
            String normalized = title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\p{IsHan}]", "");
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash title", ex);
        }
    }

    private double termOverlap(String haystack, Set<String> terms) {
        if (!StringUtils.hasText(haystack) || terms.isEmpty()) return 0;
        long hits = terms.stream().filter(term -> haystack.contains(term.toLowerCase(Locale.ROOT))).count();
        return (double) hits / terms.size();
    }

    private Set<String> queryTerms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        for (String token : TOKEN_SPLIT.split(nullToEmpty(text).toLowerCase(Locale.ROOT))) {
            if (token.length() >= 3 && !stopWords().contains(token)) {
                terms.add(token);
            }
            if (terms.size() >= 40) break;
        }
        return terms;
    }

    private Set<String> stopWords() {
        return Set.of(
                "the", "and", "for", "with", "that", "this", "from", "using", "based", "paper", "papers",
                "study", "studies", "recent", "latest", "find", "recommend", "recommended", "recommendation",
                "literature", "citation", "citations", "support", "survey", "method", "methods", "model", "models",
                "基础", "文献", "推荐", "相关", "综述", "方法", "模型", "研究"
        );
    }

    private Set<String> genericQueryTerms() {
        return Set.of(
                "latest", "recent", "review", "survey", "progress", "advances", "advance", "research",
                "paper", "papers", "study", "studies", "year", "new", "novel", "current", "trend",
                "trends", "overview", "state", "art", "sota", "literature", "recommend", "recommendation",
                "最新", "近期", "进展", "综述", "研究", "论文", "文献", "推荐", "年度"
        );
    }

    private String academicQuery(String text) {
        String normalized = normalizeText(text);
        if (!StringUtils.hasText(normalized)) return "";
        LinkedHashSet<String> pieces = new LinkedHashSet<>();
        String translated = translatedAcademicQuery(normalized);
        if (StringUtils.hasText(translated)) {
            pieces.add(translated);
        }
        String english = normalized
                .replaceAll("(?i)\\b(find|recommend|suggest|need|provide|papers?|literature|citations?|support|about|for)\\b", " ")
                .replaceAll("[^A-Za-z0-9+\\- ]+", " ");
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String token : TOKEN_SPLIT.split(english.toLowerCase(Locale.ROOT))) {
            if (token.length() >= 3 && !stopWords().contains(token)) {
                terms.add(token);
            }
            if (terms.size() >= 14) break;
        }
        if (!terms.isEmpty()) {
            pieces.add(String.join(" ", terms));
        }
        return normalizeText(String.join(" ", pieces));
    }

    private String translatedAcademicQuery(String text) {
        if (!StringUtils.hasText(text)) return "";
        String lower = text.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> phrases = new LinkedHashSet<>();
        Map<String, String> dictionary = Map.ofEntries(
                Map.entry("mimo雷达", "MIMO radar"),
                Map.entry("mimo 雷达", "MIMO radar"),
                Map.entry("合成孔径雷达", "synthetic aperture radar"),
                Map.entry("自动目标识别", "automatic target recognition"),
                Map.entry("目标识别", "target recognition"),
                Map.entry("车载", "automotive"),
                Map.entry("毫米波雷达", "mmWave radar"),
                Map.entry("毫米波", "mmWave"),
                Map.entry("通感一体化", "integrated sensing and communication"),
                Map.entry("通信感知一体化", "integrated sensing and communication"),
                Map.entry("联合雷达通信", "joint radar communication"),
                Map.entry("波形设计", "waveform design"),
                Map.entry("定位", "localization"),
                Map.entry("自由度", "degrees of freedom"),
                Map.entry("分辨率", "resolution"),
                Map.entry("目标检测", "object detection"),
                Map.entry("雷达相机", "radar camera"),
                Map.entry("传感器融合", "sensor fusion"),
                Map.entry("人体活动识别", "human activity recognition"),
                Map.entry("手势识别", "gesture recognition"),
                Map.entry("生命体征", "vital sign"),
                Map.entry("医学影像分割", "medical image segmentation"),
                Map.entry("降水临近预报", "precipitation nowcasting"),
                Map.entry("天气雷达", "weather radar"),
                Map.entry("材料性质预测", "materials property prediction"),
                Map.entry("晶体图神经网络", "crystal graph neural network"),
                Map.entry("蛋白结构预测", "protein structure prediction"),
                Map.entry("蛋白语言模型", "protein language model"),
                Map.entry("忠实性", "faithfulness"),
                Map.entry("上下文召回", "context recall")
        );
        for (Map.Entry<String, String> entry : dictionary.entrySet()) {
            if (lower.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                phrases.add(entry.getValue());
            }
        }
        return String.join(" ", phrases);
    }

    private List<String> keyPhrases(String text) {
        String combined = (translatedAcademicQuery(text) + " " + nullToEmpty(text)).toLowerCase(Locale.ROOT);
        List<String> candidates = List.of(
                "mimo radar", "synthetic aperture radar", "automatic target recognition", "automotive radar",
                "mmwave radar", "radar camera", "sensor fusion", "integrated sensing and communication",
                "joint radar communication", "waveform design", "human activity recognition", "gesture sensing",
                "vital sign", "medical image segmentation", "precipitation nowcasting", "weather radar",
                "crystal graph", "materials property", "protein structure", "protein language model",
                "retrieval augmented generation", "context precision", "faithfulness"
        );
        LinkedHashSet<String> phrases = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (combined.contains(candidate)) {
                phrases.add(candidate);
            }
        }
        return List.copyOf(phrases);
    }

    private double phraseCoverage(String haystack, List<String> phrases) {
        if (!StringUtils.hasText(haystack) || phrases.isEmpty()) return 0;
        String normalized = haystack.toLowerCase(Locale.ROOT);
        long hits = phrases.stream().filter(normalized::contains).count();
        return (double) hits / phrases.size();
    }

    private double domainMismatchPenalty(String requestText, String candidateText) {
        String request = (translatedAcademicQuery(requestText) + " " + nullToEmpty(requestText)).toLowerCase(Locale.ROOT);
        String candidate = candidateText.toLowerCase(Locale.ROOT);
        Map<String, List<String>> domains = Map.of(
                "mimo", List.of("mimo radar", "degrees of freedom", "waveform design"),
                "sar", List.of("synthetic aperture radar", "sar", "automatic target recognition"),
                "automotive", List.of("automotive radar", "vehicle detection", "radar camera", "sensor fusion"),
                "isac", List.of("integrated sensing and communication", "joint radar communication", "dual-functional"),
                "human", List.of("human activity recognition", "gesture", "vital sign", "fmcw"),
                "medical", List.of("medical image segmentation", "biomedical image", "u-net"),
                "weather", List.of("precipitation nowcasting", "weather radar"),
                "materials", List.of("materials property", "crystal graph"),
                "protein", List.of("protein structure", "protein language"),
                "rag", List.of("retrieval augmented generation", "ragas", "faithfulness")
        );
        String requestDomain = domainOf(request, domains);
        String candidateDomain = domainOf(candidate, domains);
        if (!StringUtils.hasText(requestDomain) || !StringUtils.hasText(candidateDomain) || requestDomain.equals(candidateDomain)) {
            return 0;
        }
        if (requestDomain.startsWith("radar") && candidateDomain.startsWith("radar")) {
            return 0.04;
        }
        return 0.16;
    }

    private String domainOf(String text, Map<String, List<String>> domains) {
        for (Map.Entry<String, List<String>> entry : domains.entrySet()) {
            for (String phrase : entry.getValue()) {
                if (text.contains(phrase)) {
                    return entry.getKey();
                }
            }
        }
        return "";
    }

    private List<String> splitClaims(String claims) {
        if (!StringUtils.hasText(claims)) return List.of();
        List<String> values = new ArrayList<>();
        for (String part : claims.split("\\r?\\n|;|\\|")) {
            String value = normalizeText(part);
            if (StringUtils.hasText(value)) values.add(value);
        }
        return values;
    }

    private List<String> parseList(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ex) {
            return List.of(json);
        }
    }

    private String bibtex(LiteratureCard card) {
        StringBuilder bib = new StringBuilder();
        bib.append("@article{").append(bibKey(card)).append(",\n")
                .append("  title={").append(escapeBib(card.getTitle())).append("},\n");
        List<String> authors = parseList(card.getAuthors());
        if (!authors.isEmpty()) bib.append("  author={").append(escapeBib(String.join(" and ", authors))).append("},\n");
        if (card.getPublicationYear() != null) bib.append("  year={").append(card.getPublicationYear()).append("},\n");
        if (StringUtils.hasText(card.getVenue())) bib.append("  journal={").append(escapeBib(card.getVenue())).append("},\n");
        if (StringUtils.hasText(card.getDoi())) bib.append("  doi={").append(escapeBib(card.getDoi())).append("},\n");
        if (StringUtils.hasText(card.getUrl())) bib.append("  url={").append(escapeBib(card.getUrl())).append("},\n");
        bib.append("  note={Recommended by Yanban Agent; verify before submission}\n")
                .append("}\n");
        return bib.toString();
    }

    private String bibKey(LiteratureCard card) {
        List<String> authors = parseList(card.getAuthors());
        String author = authors.isEmpty() ? "paper" : authors.get(0).replaceAll("[^A-Za-z]", "");
        if (!StringUtils.hasText(author)) author = "paper";
        String year = card.getPublicationYear() == null ? "nd" : String.valueOf(card.getPublicationYear());
        String title = "ref";
        for (String token : TOKEN_SPLIT.split(nullToEmpty(card.getTitle()).toLowerCase(Locale.ROOT))) {
            if (token.length() >= 4) {
                title = token;
                break;
            }
        }
        return (author + year + title).replaceAll("[^A-Za-z0-9]", "");
    }

    private String normalizeDoi(String doi) {
        return doi == null ? "" : doi.trim().toLowerCase(Locale.ROOT).replace("https://doi.org/", "").replace("http://doi.org/", "");
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private void addQuery(Set<String> queries, String query) {
        String normalized = normalizeText(query);
        if (StringUtils.hasText(normalized)) {
            queries.add(normalized);
        }
    }

    private String escapeBib(String value) {
        return nullToEmpty(value).replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}");
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private List<LiteratureCandidate> safeCandidates(List<LiteratureCandidate> values) {
        return values == null ? List.of() : values;
    }

    private record RetrievalPool(Map<String, LiteratureCandidate> uniqueCandidates,
                                 int rawCandidateCount,
                                 int sourceAttempts,
                                 List<String> sourceFailures,
                                 List<RetrievalDiagnosticItem> retrievalDiagnostics,
                                 Map<String, List<String>> duplicateSourcesByKey,
                                 Map<String, Integer> duplicateMergeCountsByKey) {
    }

    private record SearchPlan(List<String> queries,
                              List<String> mustIncludeTerms,
                              List<String> excludeTerms,
                              boolean llmPlanned) {
        static SearchPlan empty() {
            return new SearchPlan(List.of(), List.of(), List.of(), false);
        }
    }

    private record LlmRerankResult(Set<Long> selectedIds,
                                   String intent,
                                   List<String> rankingPreferences) {
        static LlmRerankResult empty() {
            return new LlmRerankResult(Set.of(), "", List.of());
        }
    }

    private record SanitizeResult(SearchPlan plan, int droppedCount) {
    }

    public record RecommendationRequest(String query,
                                        String goal,
                                        String claims,
                                        Integer yearFrom,
                                        Integer topK,
                                        Integer candidateK,
                                        Integer maxQueries,
                                        Boolean includeBibtex,
                                        String existingBibtex,
                                        Integer analysisLimit) {
    }

    public record RecommendationResult(String query,
                                       String goal,
                                       List<String> queries,
                                       int rawCandidateCount,
                                       int uniqueCandidateCount,
                                       int sourceAttempts,
                                       List<String> sourceFailures,
                                       List<RetrievalDiagnosticItem> retrievalDiagnostics,
                                       int rankedCandidateCount,
                                       int selectedCandidateCount,
                                       boolean llmRerankUsed,
                                       List<RecommendationItem> items,
                                       List<RecommendationDiagnosticItem> rankedPreview) {
        public static RecommendationResult empty(String reason) {
            return new RecommendationResult("", "", List.of(), 0, 0, 0, List.of(reason), List.of(), 0, 0, false, List.of(), List.of());
        }
    }

    public record RetrievalDiagnosticItem(String query,
                                          String source,
                                          int candidateCount,
                                          int acceptedCount,
                                          boolean failed,
                                          String message) {
    }

    public record RecommendationItem(Long cardId,
                                     String title,
                                     List<String> authors,
                                     Integer year,
                                     String venue,
                                     String doi,
                                     String arxivId,
                                     String openAlexId,
                                     String url,
                                     String pdfUrl,
                                     Integer citationCount,
                                     double score,
                                     String role,
                                     String sourceQuery,
                                     boolean alreadyPresent,
                                     String reason,
                                     String bibtex,
                                     String matchTarget,
                                     List<String> rankingBasis,
                                     String citationStatus,
                                     String metadataRiskLevel,
                                     List<String> metadataRiskNotes,
                                     String deduplicationKey,
                                     String duplicateStatus,
                                     List<String> duplicateSources,
                                     int duplicateMergeCount) {
        public RecommendationItem(Long cardId,
                                  String title,
                                  List<String> authors,
                                  Integer year,
                                  String venue,
                                  String doi,
                                  String arxivId,
                                  String openAlexId,
                                  String url,
                                  String pdfUrl,
                                  Integer citationCount,
                                  double score,
                                  String role,
                                  String sourceQuery,
                                  boolean alreadyPresent,
                                  String reason,
                                  String bibtex) {
            this(cardId, title, authors, year, venue, doi, arxivId, openAlexId, url, pdfUrl, citationCount,
                    score, role, sourceQuery, alreadyPresent, reason, bibtex, sourceQuery, List.of(), "UNKNOWN",
                    "UNKNOWN", List.of(), "", "UNKNOWN", List.of(), 0);
        }
    }

    public record RecommendationDiagnosticItem(Long cardId,
                                               String title,
                                               double score,
                                               String sourceQuery,
                                               String role) {
    }
}
