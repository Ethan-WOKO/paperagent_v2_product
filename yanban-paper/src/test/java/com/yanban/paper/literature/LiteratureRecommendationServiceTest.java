package com.yanban.paper.literature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.config.PaperLiteratureProperties;
import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.service.PaperModelClient;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

class LiteratureRecommendationServiceTest {

    private LiteratureSource source;
    private StandaloneLiteratureCardSearchService localSearch;
    private LiteratureCardCatalogService catalog;
    private LiteratureCardAnalysisService cardAnalysis;
    private ObjectProvider<PaperModelClient> modelClientProvider;
    private ObjectMapper objectMapper;
    private LiteratureRecommendationService service;

    @BeforeEach
    void setUp() {
        source = mock(LiteratureSource.class);
        localSearch = mock(StandaloneLiteratureCardSearchService.class);
        catalog = mock(LiteratureCardCatalogService.class);
        cardAnalysis = mock(LiteratureCardAnalysisService.class);
        modelClientProvider = mock(ObjectProvider.class);
        objectMapper = new ObjectMapper();

        when(source.name()).thenReturn("openalex");
        when(modelClientProvider.getIfAvailable()).thenReturn(null);
        when(catalog.upsertCard(any())).thenAnswer(invocation -> toCard(invocation.getArgument(0)));

        service = new LiteratureRecommendationService(
                List.of(source),
                localSearch,
                catalog,
                cardAnalysis,
                modelClientProvider,
                objectMapper,
                new PaperLiteratureProperties()
        );
    }

    @Test
    void recommendDeduplicatesSourcesAndMarksExistingBibtex() {
        when(localSearch.search(any(), anyInt(), any())).thenReturn(List.of(localCandidate()));
        when(source.search(any(), anyInt())).thenReturn(List.of(externalDuplicate(), strongerExternal()));

        LiteratureRecommendationService.RecommendationResult result = service.recommend(
                new LiteratureRecommendationService.RecommendationRequest(
                        "hybrid RAG",
                        "find citation support",
                        null,
                        2020,
                        5,
                        10,
                        1,
                        true,
                        """
                                @article{old,
                                  title={Hybrid Retrieval for RAG},
                                  doi={10.1000/rag}
                                }
                                """,
                        7
                )
        );

        assertThat(result.rawCandidateCount()).isEqualTo(3);
        assertThat(result.uniqueCandidateCount()).isEqualTo(2);
        assertThat(result.items()).hasSize(2);
        assertThat(result.items()).extracting(LiteratureRecommendationService.RecommendationItem::title)
                .containsExactlyInAnyOrder("Hybrid Retrieval for RAG", "Neural Hybrid Retrieval for Retrieval Augmented Generation");
        assertThat(result.items()).anySatisfy(item -> {
            assertThat(item.title()).isEqualTo("Hybrid Retrieval for RAG");
            assertThat(item.alreadyPresent()).isTrue();
            assertThat(item.bibtex()).contains("Recommended by Yanban Agent");
            assertThat(item.citationStatus()).isEqualTo("BIBTEX_READY_VERIFY_BEFORE_SUBMISSION");
            assertThat(item.metadataRiskLevel()).isEqualTo("LOW");
            assertThat(item.metadataRiskNotes()).isEmpty();
            assertThat(item.deduplicationKey()).isEqualTo("doi:10.1000/rag");
            assertThat(item.duplicateStatus()).isEqualTo("MERGED_DUPLICATES");
            assertThat(item.duplicateSources()).containsExactly("local_card", "openalex");
            assertThat(item.duplicateMergeCount()).isEqualTo(1);
            assertThat(item.matchTarget()).isEqualTo("hybrid RAG");
            assertThat(item.rankingBasis()).anyMatch(value -> value.startsWith("score="));
        });
        verify(catalog, times(2)).upsertCard(any());
        verify(cardAnalysis).analyzeTopCandidates(any(), eq(7));
    }

    @Test
    void recommendWithProgressReportsPlanningRetrievalRankingAndCompletion() {
        when(localSearch.search(any(), anyInt(), any())).thenReturn(List.of(localCandidate()));
        when(source.search(any(), anyInt())).thenReturn(List.of(strongerExternal()));
        List<String> stages = new ArrayList<>();
        List<String> messages = new ArrayList<>();

        LiteratureRecommendationService.RecommendationResult result = service.recommendWithProgress(
                new LiteratureRecommendationService.RecommendationRequest(
                        "hybrid RAG", null, null, null, 2, 5, 1, false, null, 0),
                (stage, message) -> {
                    stages.add(stage);
                    messages.add(message);
                });

        assertThat(result.items()).isNotEmpty();
        assertThat(stages).contains("planning", "retrieval", "ranking", "reranking", "completed");
        assertThat(messages).anyMatch(message -> message.contains("1/2"));
        assertThat(messages).anyMatch(message -> message.contains("2/2"));
    }

    @Test
    void recommendMarksMergedDuplicatesWhenSameSourceReturnsSamePaperAcrossQueries() {
        PaperModelClient modelClient = mock(PaperModelClient.class);
        when(modelClientProvider.getIfAvailable()).thenReturn(modelClient);
        when(modelClient.complete(any(), any(), any(), anyInt()))
                .thenReturn("""
                        {"queries":[{"query":"hybrid rag retrieval"},{"query":"neural rag retrieval"}],"mustIncludeTerms":["rag"],"excludeTerms":[]}
                        """)
                .thenReturn("{}");
        when(localSearch.search(any(), anyInt(), any())).thenReturn(List.of());
        when(source.search(any(), anyInt())).thenReturn(List.of(strongerExternal()));

        LiteratureRecommendationService.RecommendationResult result = service.recommend(
                new LiteratureRecommendationService.RecommendationRequest(
                        "hybrid RAG retrieval",
                        null,
                        null,
                        null,
                        5,
                        10,
                        2,
                        false,
                        null,
                        null
                )
        );

        assertThat(result.rawCandidateCount()).isEqualTo(2);
        assertThat(result.uniqueCandidateCount()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        LiteratureRecommendationService.RecommendationItem item = result.items().get(0);
        assertThat(item.duplicateSources()).containsExactly("openalex");
        assertThat(item.duplicateMergeCount()).isEqualTo(1);
        assertThat(item.duplicateStatus()).isEqualTo("MERGED_DUPLICATES");
    }

    @Test
    void recommendMarksMetadataRiskWhenCitationFieldsAreIncomplete() {
        when(localSearch.search(any(), anyInt(), any())).thenReturn(List.of());
        when(source.search(any(), anyInt())).thenReturn(List.of(incompleteExternal()));

        LiteratureRecommendationService.RecommendationResult result = service.recommend(
                new LiteratureRecommendationService.RecommendationRequest(
                        "hybrid RAG",
                        null,
                        null,
                        null,
                        3,
                        10,
                        1,
                        false,
                        null,
                        null
                )
        );

        assertThat(result.items()).hasSize(1);
        LiteratureRecommendationService.RecommendationItem item = result.items().get(0);
        assertThat(item.bibtex()).isNull();
        assertThat(item.citationStatus()).isEqualTo("BIBTEX_NOT_REQUESTED_VERIFY_METADATA");
        assertThat(item.metadataRiskLevel()).isEqualTo("HIGH");
        assertThat(item.metadataRiskNotes()).contains("missing authors", "missing publication year", "missing stable identifier or URL");
    }

    @Test
    void recommendRepairsAndDropsYearOnlyLlmQueries() {
        PaperModelClient modelClient = mock(PaperModelClient.class);
        when(modelClientProvider.getIfAvailable()).thenReturn(modelClient);
        when(modelClient.complete(any(), any(), any(), anyInt()))
                .thenReturn("""
                        {"queries":[{"query":"embodied intelligence review 2023"},{"query":"2023"}],"mustIncludeTerms":["embodied intelligence"],"excludeTerms":[]}
                        """)
                .thenReturn("""
                        {"queries":[{"query":"embodied intelligence robot learning survey"},{"query":"embodied AI vision language action models"}],"mustIncludeTerms":["embodied intelligence"],"excludeTerms":[]}
                        """)
                .thenReturn("{}");
        when(localSearch.search(any(), anyInt(), any())).thenReturn(List.of());
        when(source.search(any(), anyInt())).thenReturn(List.of(strongerExternal()));

        LiteratureRecommendationService.RecommendationResult result = service.recommend(
                new LiteratureRecommendationService.RecommendationRequest(
                        "具身智能 Embodied Intelligence 最新研究",
                        "最新文献综述",
                        null,
                        2023,
                        5,
                        10,
                        4,
                        false,
                        null,
                        null
                )
        );

        assertThat(result.queries()).containsExactly(
                "embodied intelligence robot learning",
                "embodied vision language action"
        );
        verify(source, times(2)).search(any(), anyInt());
        verify(source, times(0)).search(eq("2023"), anyInt());
        verify(cardAnalysis).analyzeTopCandidates(any(), eq(15));
    }

    @Test
    void recommendSkipsRepairWhenSanitizedLlmPlanStillHasEnoughQueries() {
        PaperModelClient modelClient = mock(PaperModelClient.class);
        when(modelClientProvider.getIfAvailable()).thenReturn(modelClient);
        when(modelClient.complete(any(), any(), any(), anyInt()))
                .thenReturn("""
                        {"queries":[{"query":"embodied intelligence robotics interaction"},{"query":"embodied agent task planning reasoning"},{"query":"2024"}],"mustIncludeTerms":["embodied intelligence"],"excludeTerms":[]}
                        """)
                .thenReturn("{}");
        when(localSearch.search(any(), anyInt(), any())).thenReturn(List.of());
        when(source.search(any(), anyInt())).thenReturn(List.of(strongerExternal()));

        LiteratureRecommendationService.RecommendationResult result = service.recommend(
                new LiteratureRecommendationService.RecommendationRequest(
                        "embodied intelligence embodied agent robotics task planning",
                        "latest survey",
                        null,
                        2023,
                        5,
                        10,
                        4,
                        false,
                        null,
                        null
                )
        );

        assertThat(result.queries()).containsExactly(
                "embodied intelligence robotics interaction",
                "embodied agent task planning reasoning"
        );
        verify(modelClient, times(2)).complete(any(), any(), any(), anyInt());
        verify(source, times(2)).search(any(), anyInt());
        verify(source, times(0)).search(eq("2024"), anyInt());
    }

    @Test
    void recommendClampsAnalysisLimitForSynchronousRecommendation() {
        when(localSearch.search(any(), anyInt(), any())).thenReturn(List.of(localCandidate()));
        when(source.search(any(), anyInt())).thenReturn(List.of());

        service.recommend(new LiteratureRecommendationService.RecommendationRequest(
                "hybrid RAG",
                null,
                null,
                null,
                5,
                10,
                1,
                false,
                null,
                99
        ));

        verify(cardAnalysis).analyzeTopCandidates(any(), eq(30));
    }

    @Test
    void recommendCapsOpenAlexResultsPerQuery() {
        when(localSearch.search(any(), anyInt(), any())).thenReturn(List.of());
        when(source.search(any(), anyInt())).thenReturn(List.of(strongerExternal()));

        service.recommend(new LiteratureRecommendationService.RecommendationRequest(
                "hybrid RAG",
                null,
                null,
                null,
                5,
                50,
                1,
                false,
                null,
                null
        ));

        verify(source).search(any(), eq(20));
    }

    private LiteratureCard toCard(LiteratureCandidate candidate) throws Exception {
        LiteratureCard card = new LiteratureCard("hash-" + candidate.title(), candidate.title());
        ReflectionTestUtils.setField(card, "id", Math.abs(candidate.title().hashCode()) + 1L);
        card.setDoi(candidate.doi());
        card.setArxivId(candidate.arxivId());
        card.setOpenAlexId(candidate.openAlexId());
        card.setS2Id(candidate.s2Id());
        card.setAuthors(objectMapper.writeValueAsString(candidate.authors()));
        card.setPublicationYear(candidate.year());
        card.setVenue(candidate.venue());
        card.setAbstractText(candidate.abstractText());
        card.setUrl(candidate.url());
        card.setPdfUrl(candidate.pdfUrl());
        card.setCitationCount(candidate.citationCount());
        card.setFieldsOfStudyJson(objectMapper.writeValueAsString(candidate.fieldsOfStudy()));
        card.setAnalysisJson("""
                {"claim":"retrieval augmented generation with hybrid retrieval","evidenceUse":[{"supports":"hybrid RAG","strength":"HIGH"}]}
                """);
        return card;
    }

    private LiteratureCandidate localCandidate() {
        return new LiteratureCandidate(
                "local_card",
                "10.1000/rag",
                null,
                null,
                null,
                "Hybrid Retrieval for RAG",
                List.of("A. Author"),
                2024,
                "Demo Journal",
                "Hybrid retrieval improves retrieval augmented generation systems.",
                "https://example.test/rag",
                null,
                42,
                List.of(),
                List.of("retrieval", "rag"),
                "hybrid RAG"
        );
    }

    private LiteratureCandidate externalDuplicate() {
        return new LiteratureCandidate(
                "openalex",
                "https://doi.org/10.1000/rag",
                null,
                null,
                null,
                "Hybrid Retrieval for RAG",
                List.of("B. Author"),
                2024,
                "Demo Journal",
                "Duplicate metadata for hybrid retrieval.",
                "https://example.test/rag",
                null,
                7,
                List.of(),
                List.of("retrieval"),
                "hybrid RAG"
        );
    }

    private LiteratureCandidate strongerExternal() {
        return new LiteratureCandidate(
                "openalex",
                "10.1000/neural-rag",
                null,
                null,
                null,
                "Neural Hybrid Retrieval for Retrieval Augmented Generation",
                List.of("C. Author"),
                2025,
                "Top Conference",
                "Neural hybrid retrieval for RAG combines sparse and dense retrieval with reranking.",
                "https://example.test/neural-rag",
                null,
                80,
                List.of(),
                List.of("retrieval", "rag", "reranking"),
                "hybrid RAG"
        );
    }

    private LiteratureCandidate incompleteExternal() {
        return new LiteratureCandidate(
                "openalex",
                null,
                null,
                null,
                null,
                "Hybrid RAG Metadata Sparse Candidate",
                List.of(),
                null,
                null,
                "Hybrid retrieval for RAG with sparse metadata.",
                null,
                null,
                1,
                List.of(),
                List.of("retrieval", "rag"),
                "hybrid RAG"
        );
    }
}
