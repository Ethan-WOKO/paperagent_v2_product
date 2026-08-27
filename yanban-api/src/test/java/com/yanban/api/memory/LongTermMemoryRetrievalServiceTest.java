package com.yanban.api.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.AgentLongTermMemoryContext;
import com.yanban.core.agent.AgentLongTermMemory;
import com.yanban.core.agent.AgentLongTermMemoryRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LongTermMemoryRetrievalServiceTest {

    private static final long USER_ID = 42L;

    @Mock
    private AgentLongTermMemoryRepository memories;

    private LongTermMemoryRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new LongTermMemoryRetrievalService(memories, new ObjectMapper());
    }

    @Test
    void retrievesRelevantMemoryByKeywordAndTag() {
        when(memories.findGovernedUserCandidates(eq(USER_ID), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(
                        memory("PREFERENCE", "User prefers GraphRAG answers with explicit ablation caveats.", "[\"GraphRAG\",\"style\"]", "0.86"),
                        memory("PREFERENCE", "User studies compiler optimization.", "[\"systems\"]", "0.90")
                ));

        AgentLongTermMemoryContext context = service.retrieve(USER_ID, "Please help with GraphRAG evaluation.");

        assertThat(context.hasContent()).isTrue();
        assertThat(context.hitCount()).isEqualTo(1);
        assertThat(context.content()).contains("GraphRAG", "ablation caveats");
        assertThat(context.content()).doesNotContain("compiler optimization");
        assertThat(context.note()).contains("hits=1", "candidates=2", "GraphRAG");
    }

    @Test
    void alwaysInjectsConfirmedGlobalLanguagePreferenceButNotUnrelatedOrdinaryMemory() {
        when(memories.findGovernedUserCandidates(eq(USER_ID), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(
                        memory("PREFERENCE", "\u9ed8\u8ba4\u4f7f\u7528\u4e2d\u6587\u56de\u7b54\u3002", "[\"answer-language\"]", "0.95"),
                        memory("FACT", "User once studied an unrelated compiler course.", "[\"compiler\"]", "0.90"),
                        memory("FACT", "User is evaluating GraphRAG retrieval.", "[\"GraphRAG\"]", "0.90")
                ));

        AgentLongTermMemoryContext context = service.retrieve(USER_ID, "Please assess GraphRAG recall.");

        assertThat(context.hitCount()).isEqualTo(2);
        assertThat(context.content())
                .contains("\u9ed8\u8ba4\u4f7f\u7528\u4e2d\u6587\u56de\u7b54", "GraphRAG retrieval")
                .doesNotContain("compiler course");
    }

    @Test
    void retrievesChineseMemoryWhenRelatedPhrasesUseDifferentSuffixes() {
        when(memories.findGovernedUserCandidates(eq(USER_ID), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(
                        memory("RESEARCH_PROFILE", "我的研究领域是极化FDA-MIMO雷达，目前在学习Java和Agent。", "[]", "0.90"),
                        memory("PREFERENCE", "我喜欢简洁的写作风格。", "[]", "0.90")
                ));

        AgentLongTermMemoryContext context = service.retrieve(USER_ID, "我的研究方向是什么？");

        assertThat(context.hitCount()).isEqualTo(1);
        assertThat(context.content()).contains("极化FDA-MIMO雷达")
                .doesNotContain("简洁的写作风格");
    }

    @Test
    void returnsEmptyWhenNoMemoryMatchesQuery() {
        when(memories.findGovernedUserCandidates(eq(USER_ID), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(memory("FACT", "User studies reinforcement learning.", "[\"RL\"]", "0.80")));

        AgentLongTermMemoryContext context = service.retrieve(USER_ID, "Discuss literature review structure.");

        assertThat(context.hasContent()).isFalse();
        assertThat(context.hitCount()).isZero();
        assertThat(context.note()).contains("No relevant long-term memory");
    }

    @Test
    void filtersDeletedSupersededAndLowConfidenceMemories() {
        AgentLongTermMemory deleted = memory("FACT", "Deleted GraphRAG memory should not appear.", "[\"GraphRAG\"]", "0.90");
        deleted.markDeleted();
        AgentLongTermMemory superseded = memory("FACT", "Superseded GraphRAG memory should not appear.", "[\"GraphRAG\"]", "0.90");
        superseded.markSuperseded(99L);
        AgentLongTermMemory lowConfidence = memory("FACT", "Low confidence GraphRAG memory should not appear.", "[\"GraphRAG\"]", "0.10");
        AgentLongTermMemory active = memory("FACT", "Active GraphRAG memory should appear.", "[\"GraphRAG\"]", "0.70");

        when(memories.findGovernedUserCandidates(eq(USER_ID), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(deleted, superseded, lowConfidence, active));

        AgentLongTermMemoryContext context = service.retrieve(USER_ID, "GraphRAG");

        assertThat(context.hasContent()).isTrue();
        assertThat(context.hitCount()).isEqualTo(1);
        assertThat(context.content()).contains("Active GraphRAG memory");
        assertThat(context.content()).doesNotContain("Deleted", "Superseded", "Low confidence");
    }

    @Test
    void failsClosedForCrossUserProjectScopeAndUnconfirmedSources() {
        AgentLongTermMemory crossUser = memory(99L, null, AgentLongTermMemory.SCOPE_USER,
                AgentLongTermMemory.SOURCE_USER_CONFIRMED, "Cross-user GraphRAG memory.", "0.90");
        AgentLongTermMemory project = memory(USER_ID, 7L, "PROJECT",
                AgentLongTermMemory.SOURCE_USER_CONFIRMED, "Unversioned project GraphRAG fact.", "0.90");
        AgentLongTermMemory inferred = memory(USER_ID, null, AgentLongTermMemory.SCOPE_USER,
                "MODEL_INFERRED", "Model-inferred GraphRAG guess.", "0.90");
        AgentLongTermMemory confirmed = memory(USER_ID, null, AgentLongTermMemory.SCOPE_USER,
                AgentLongTermMemory.SOURCE_USER_CONFIRMED, "Confirmed GraphRAG preference.", "0.90");
        when(memories.findGovernedUserCandidates(eq(USER_ID), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(crossUser, project, inferred, confirmed));

        AgentLongTermMemoryContext context = service.retrieve(USER_ID, "GraphRAG");

        assertThat(context.content()).contains("Confirmed GraphRAG preference")
                .doesNotContain("Cross-user", "Unversioned project", "Model-inferred");
        assertThat(context.candidateCount()).isEqualTo(1);
    }

    @Test
    void distilledMemoryRequiresExplicitUserConfirmationBeforeRetrieval() {
        AgentLongTermMemory unconfirmed = rawMemory(
                USER_ID, null, AgentLongTermMemory.SCOPE_USER,
                AgentLongTermMemory.SOURCE_LLM_DISTILLED,
                "Unconfirmed distilled GraphRAG preference.", "0.90");
        AgentLongTermMemory confirmed = govern(rawMemory(
                USER_ID, null, AgentLongTermMemory.SCOPE_USER,
                AgentLongTermMemory.SOURCE_LLM_DISTILLED,
                "Confirmed distilled GraphRAG preference.", "0.90"), null);
        when(memories.findGovernedUserCandidates(eq(USER_ID), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(unconfirmed, confirmed));

        AgentLongTermMemoryContext context = service.retrieve(USER_ID, "GraphRAG");

        assertThat(context.hitCount()).isEqualTo(1);
        assertThat(context.content()).contains("Confirmed distilled GraphRAG preference")
                .doesNotContain("Unconfirmed distilled GraphRAG preference");
    }

    @Test
    void deduplicatesContentAndRejectsSensitiveOrAbsolutePathText() {
        when(memories.findGovernedUserCandidates(eq(USER_ID), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(
                        memory("PREFERENCE", "GraphRAG answers need caveats.", "[\"GraphRAG\"]", "0.90"),
                        memory("PREFERENCE", "  graphrag answers need   caveats. ", "[\"GraphRAG\"]", "0.89"),
                        memory("FACT", "GraphRAG source is C:\\\\Users\\\\alice\\\\secret.txt", "[\"GraphRAG\"]", "0.95"),
                        memory("FACT", "GraphRAG api_key=do-not-expose", "[\"GraphRAG\"]", "0.95")));

        AgentLongTermMemoryContext context = service.retrieve(USER_ID, "GraphRAG");

        assertThat(context.hitCount()).isEqualTo(1);
        assertThat(context.omittedCount()).isEqualTo(1);
        assertThat(context.content()).contains("GraphRAG answers need caveats")
                .doesNotContain("alice", "do-not-expose");
    }

    @Test
    void deduplicatesBeforeHitLimitSoDistinctLowerRankedMemoryIsRetainedDeterministically() {
        when(memories.findGovernedUserCandidates(eq(USER_ID), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(
                        memory("PREFERENCE", "GraphRAG answers need explicit caveats.", "[\"GraphRAG\"]", "0.95"),
                        memory("PREFERENCE", " graphrag answers need explicit   caveats. ", "[\"GraphRAG\"]", "0.94"),
                        memory("PREFERENCE", "GRAPHRAG ANSWERS NEED EXPLICIT CAVEATS.", "[\"GraphRAG\"]", "0.93"),
                        memory("PREFERENCE", "GraphRAG answers need explicit caveats.", "[\"GraphRAG\"]", "0.92"),
                        memory("PREFERENCE", "GraphRAG answers need explicit caveats.", "[\"GraphRAG\"]", "0.91"),
                        memory("FACT", "GraphRAG evaluation uses a held-out benchmark.", "[\"GraphRAG\"]", "0.80")));

        AgentLongTermMemoryContext context = service.retrieve(USER_ID, "GraphRAG");

        assertThat(context.hitCount()).isEqualTo(2);
        assertThat(context.omittedCount()).isEqualTo(4);
        assertThat(context.content()).contains("GraphRAG answers need explicit caveats",
                "GraphRAG evaluation uses a held-out benchmark");
        assertThat(context.content().indexOf("GraphRAG answers need explicit caveats"))
                .isLessThan(context.content().indexOf("GraphRAG evaluation uses a held-out benchmark"));
    }

    @Test
    void rejectsExpandedCredentialAndLocalPathFormsButKeepsOrdinaryWebUrls() {
        when(memories.findGovernedUserCandidates(eq(USER_ID), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(
                        memory("FACT", "GraphRAG file is /workspace/private/result.txt", "[\"GraphRAG\"]", "0.90"),
                        memory("FACT", "GraphRAG file is /mnt/data/result.txt", "[\"GraphRAG\"]", "0.90"),
                        memory("FACT", "GraphRAG file is /root/result.txt", "[\"GraphRAG\"]", "0.90"),
                        memory("FACT", "GraphRAG file is /data/result.txt", "[\"GraphRAG\"]", "0.90"),
                        memory("FACT", "GraphRAG file is file:///srv/private/result.txt", "[\"GraphRAG\"]", "0.90"),
                        memory("FACT", "GraphRAG api key is abcdefghijklmnop", "[\"GraphRAG\"]", "0.90"),
                        memory("FACT", "GraphRAG Authorization Bearer abcdefghijklmnop", "[\"GraphRAG\"]", "0.90"),
                        memory("FACT", "GraphRAG token is sk-abcdefghijklmnop", "[\"GraphRAG\"]", "0.90"),
                        memory("FACT", "GraphRAG paper is available at https://example.org/papers/graphrag", "[\"GraphRAG\"]", "0.80")));

        AgentLongTermMemoryContext context = service.retrieve(USER_ID, "GraphRAG");

        assertThat(context.hitCount()).isEqualTo(1);
        assertThat(context.candidateCount()).isEqualTo(1);
        assertThat(context.content()).contains("https://example.org/papers/graphrag")
                .doesNotContain("/workspace", "/mnt", "/root", "/data", "file://", "abcdefghijklmnop", "sk-");
    }

    @Test
    void rejectsUnsafeTagsInvalidTagStructuresAndUnknownMemoryTypesBeforeScoringOrFormatting() {
        when(memories.findGovernedUserCandidates(eq(USER_ID), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(
                        memory("PREFERENCE", "GraphRAG preference with credential tag.",
                                "[\"api key is abcdefghijklmnop\"]", "0.95"),
                        memory("PREFERENCE", "GraphRAG preference with path tag.",
                                "[\"C:\\\\Users\\\\alice\\\\secret.txt\"]", "0.95"),
                        memory("PREFERENCE", "GraphRAG preference with oversized tag.",
                                "[\"" + "x".repeat(65) + "\"]", "0.95"),
                        memory("PREFERENCE", "GraphRAG preference with malformed tags.",
                                "{\"tag\":\"GraphRAG\"}", "0.95"),
                        memory("<script>UNKNOWN</script>", "GraphRAG preference with unknown type.",
                                "[\"GraphRAG\"]", "0.95"),
                        memory("PREFERENCE", "GraphRAG preference with safe academic metadata.",
                                "[\"GraphRAG\",\"https://example.org/topic\"]", "0.80")));

        AgentLongTermMemoryContext context = service.retrieve(USER_ID, "GraphRAG");

        assertThat(context.hitCount()).isEqualTo(1);
        assertThat(context.candidateCount()).isEqualTo(1);
        assertThat(context.content()).contains("safe academic metadata", "GraphRAG", "https://example.org/topic")
                .doesNotContain("credential tag", "path tag", "oversized tag", "malformed tags", "unknown type",
                        "abcdefghijklmnop", "alice", "<script>");
    }

    @Test
    void rejectsTooManyOrNonTextualTagsAndDeduplicatesSafeTagsInStableOrder() {
        String tooManyTags = "[" + java.util.stream.IntStream.range(0, 13)
                .mapToObj(index -> "\"tag" + index + "\"")
                .collect(java.util.stream.Collectors.joining(",")) + "]";
        when(memories.findGovernedUserCandidates(eq(USER_ID), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(
                        memory("FACT", "GraphRAG record with too many tags.", tooManyTags, "0.95"),
                        memory("FACT", "GraphRAG record with non-text tag.", "[\"GraphRAG\",7]", "0.95"),
                        memory("FACT", "GraphRAG record with stable tags.",
                                "[\"GraphRAG\",\"evaluation\",\"GraphRAG\"]", "0.80")));

        AgentLongTermMemoryContext context = service.retrieve(USER_ID, "GraphRAG");

        assertThat(context.hitCount()).isEqualTo(1);
        assertThat(context.content()).contains("tags=GraphRAG/evaluation")
                .doesNotContain("too many tags", "non-text tag", "GraphRAG/evaluation/GraphRAG");
    }

    @Test
    void reportsBudgetOmissions() {
        List<AgentLongTermMemory> rows = List.of(
                memory("FACT", repeated("GraphRAG memory A "), "[\"GraphRAG\"]", "0.90"),
                memory("FACT", repeated("GraphRAG memory B "), "[\"GraphRAG\"]", "0.89"),
                memory("FACT", repeated("GraphRAG memory C "), "[\"GraphRAG\"]", "0.88"),
                memory("FACT", repeated("GraphRAG memory D "), "[\"GraphRAG\"]", "0.87"),
                memory("FACT", repeated("GraphRAG memory E "), "[\"GraphRAG\"]", "0.86")
        );
        when(memories.findGovernedUserCandidates(eq(USER_ID), any(Instant.class), any(Pageable.class)))
                .thenReturn(rows);

        AgentLongTermMemoryContext context = service.retrieve(USER_ID, "GraphRAG");

        assertThat(context.hasContent()).isTrue();
        assertThat(context.hitCount()).isLessThan(5);
        assertThat(context.omittedCount()).isGreaterThan(0);
        assertThat(context.note()).contains("omitted=");
    }

    @Test
    void rejectsLegacyRejectedExpiredInvalidatedAndIncompleteGovernanceRows() {
        AgentLongTermMemory legacy = rawMemory(USER_ID, null, AgentLongTermMemory.SCOPE_USER,
                AgentLongTermMemory.SOURCE_USER_CONFIRMED, "Legacy GraphRAG memory.", "0.90");
        AgentLongTermMemory rejected = memory("FACT", "Rejected GraphRAG memory.", "[\"GraphRAG\"]", "0.90");
        ReflectionTestUtils.setField(rejected, "confirmationStatus", AgentLongTermMemory.CONFIRMATION_REJECTED);
        AgentLongTermMemory expired = memory("FACT", "Expired GraphRAG memory.", "[\"GraphRAG\"]", "0.90");
        ReflectionTestUtils.setField(expired, "expiresAt", Instant.now().minusSeconds(1));
        AgentLongTermMemory invalidated = memory("FACT", "Invalidated GraphRAG memory.", "[\"GraphRAG\"]", "0.90");
        ReflectionTestUtils.setField(invalidated, "invalidatedAt", Instant.now().minusSeconds(1));
        AgentLongTermMemory missingProvenance = memory("FACT", "Unprovenanced GraphRAG memory.", "[\"GraphRAG\"]", "0.90");
        ReflectionTestUtils.setField(missingProvenance, "provenanceRef", null);
        AgentLongTermMemory futureConfirmation = memory("FACT", "Future-confirmed GraphRAG memory.", "[\"GraphRAG\"]", "0.90");
        ReflectionTestUtils.setField(futureConfirmation, "confirmedAt", Instant.now().plusSeconds(300));
        AgentLongTermMemory valid = memory("FACT", "Governed GraphRAG memory.", "[\"GraphRAG\"]", "0.80");
        when(memories.findGovernedUserCandidates(eq(USER_ID), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(legacy, rejected, expired, invalidated, missingProvenance, futureConfirmation, valid));

        AgentLongTermMemoryContext context = service.retrieve(USER_ID, "GraphRAG");

        assertThat(context.hitCount()).isEqualTo(1);
        assertThat(context.content()).contains("Governed GraphRAG memory")
                .doesNotContain("Legacy", "Rejected", "Expired", "Invalidated", "Unprovenanced", "Future-confirmed");
    }

    @Test
    void acceptsOnlyWhitelistedIndependentConfirmationAndProvenanceValues() {
        AgentLongTermMemory unknownConfirmation = memory("FACT", "Unknown-confirmed GraphRAG memory.",
                "[\"GraphRAG\"]", "0.90");
        ReflectionTestUtils.setField(unknownConfirmation, "confirmedSource", "UNKNOWN");
        AgentLongTermMemory modelConfirmation = memory("FACT", "Model-confirmed GraphRAG memory.",
                "[\"GraphRAG\"]", "0.90");
        ReflectionTestUtils.setField(modelConfirmation, "confirmedSource", "MODEL_INFERRED");
        AgentLongTermMemory modelProvenance = memory("FACT", "Model-provenance GraphRAG memory.",
                "[\"GraphRAG\"]", "0.90");
        ReflectionTestUtils.setField(modelProvenance, "provenanceType", "MODEL_OUTPUT");
        AgentLongTermMemory auditProvenance = memory("FACT", "Audit-provenance GraphRAG memory.",
                "[\"GraphRAG\"]", "0.90");
        ReflectionTestUtils.setField(auditProvenance, "provenanceType", "AUDIT_SUMMARY");
        AgentLongTermMemory recovered = memory("FACT", "Recovered GraphRAG memory.", "[\"GraphRAG\"]", "0.90");
        ReflectionTestUtils.setField(recovered, "confirmedSource", "RECOVERED");
        AgentLongTermMemory untrusted = memory("FACT", "Untrusted GraphRAG memory.", "[\"GraphRAG\"]", "0.90");
        ReflectionTestUtils.setField(untrusted, "provenanceType", "UNTRUSTED");
        AgentLongTermMemory allowed = memory("FACT", "Whitelisted GraphRAG memory.", "[\"GraphRAG\"]", "0.80");
        when(memories.findGovernedUserCandidates(eq(USER_ID), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(unknownConfirmation, modelConfirmation, modelProvenance,
                        auditProvenance, recovered, untrusted, allowed));

        AgentLongTermMemoryContext context = service.retrieve(USER_ID, "GraphRAG");

        assertThat(context.hitCount()).isEqualTo(1);
        assertThat(context.content()).contains("Whitelisted GraphRAG memory")
                .doesNotContain("Unknown-confirmed", "Model-confirmed", "Model-provenance",
                        "Audit-provenance", "Recovered", "Untrusted");
    }

    @Test
    void acceptsServerGeneratedUserSettingsProvenance() {
        AgentLongTermMemory settingsMemory = memory("PREFERENCE",
                "GraphRAG answers should include explicit caveats.", "[\"GraphRAG\"]", "0.90");
        ReflectionTestUtils.setField(settingsMemory, "provenanceType",
                AgentLongTermMemory.PROVENANCE_USER_SETTINGS_ACTION);
        ReflectionTestUtils.setField(settingsMemory, "provenanceRef", "memory-settings:7:confirm");
        when(memories.findGovernedUserCandidates(eq(USER_ID), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(settingsMemory));

        AgentLongTermMemoryContext context = service.retrieve(USER_ID, "GraphRAG");

        assertThat(context.hitCount()).isEqualTo(1);
        assertThat(context.content()).contains("explicit caveats");
    }

    @Test
    void scansPastAFullUnsafePageWithoutStarvingALaterGovernedMemory() {
        List<AgentLongTermMemory> unsafePage = IntStream.range(0, 40)
                .mapToObj(index -> {
                    AgentLongTermMemory memory = memory("FACT", "Unsafe GraphRAG memory " + index,
                            "[\"GraphRAG\"]", "0.90");
                    ReflectionTestUtils.setField(memory, "provenanceType", "MODEL_OUTPUT");
                    return memory;
                })
                .toList();
        AgentLongTermMemory valid = memory("FACT", "Later governed GraphRAG memory.",
                "[\"GraphRAG\"]", "0.85");
        when(memories.findGovernedUserCandidates(eq(USER_ID), any(Instant.class), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable page = invocation.getArgument(2);
                    return page.getPageNumber() == 0 ? unsafePage : List.of(valid);
                });

        AgentLongTermMemoryContext context = service.retrieve(USER_ID, "GraphRAG");

        assertThat(context.hitCount()).isEqualTo(1);
        assertThat(context.content()).contains("Later governed GraphRAG memory")
                .doesNotContain("Unsafe GraphRAG memory");
        ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
        verify(memories, times(2)).findGovernedUserCandidates(eq(USER_ID), any(Instant.class), pages.capture());
        assertThat(pages.getAllValues()).extracting(Pageable::getPageNumber).containsExactly(0, 1);
        assertThat(pages.getAllValues()).extracting(Pageable::getPageSize).containsOnly(40);
    }

    @Test
    void stopsGovernanceScanningAtTheHardRowBudget() {
        List<AgentLongTermMemory> unsafePage = IntStream.range(0, 40)
                .mapToObj(index -> {
                    AgentLongTermMemory memory = memory("FACT", "Rejected-window GraphRAG memory " + index,
                            "[\"GraphRAG\"]", "0.90");
                    ReflectionTestUtils.setField(memory, "provenanceType", "AUDIT_SUMMARY");
                    return memory;
                })
                .toList();
        when(memories.findGovernedUserCandidates(eq(USER_ID), any(Instant.class), any(Pageable.class)))
                .thenReturn(unsafePage);

        AgentLongTermMemoryContext context = service.retrieve(USER_ID, "GraphRAG");

        assertThat(context.hasContent()).isFalse();
        ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
        verify(memories, times(10)).findGovernedUserCandidates(eq(USER_ID), any(Instant.class), pages.capture());
        assertThat(pages.getAllValues()).extracting(Pageable::getPageNumber)
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertThat(pages.getAllValues()).extracting(Pageable::getPageSize).containsOnly(40);
    }

    @Test
    void projectRetrievalRequiresExactTrustedIdentityAndCurrentProjectVersion() {
        String currentVersion = "a".repeat(64);
        AgentLongTermMemory valid = governedProjectMemory(USER_ID, 7L, currentVersion,
                "Current project GraphRAG decision.");
        AgentLongTermMemory oldVersion = governedProjectMemory(USER_ID, 7L, "b".repeat(64),
                "Old project GraphRAG decision.");
        AgentLongTermMemory otherProject = governedProjectMemory(USER_ID, 8L, currentVersion,
                "Other project GraphRAG decision.");
        AgentLongTermMemory otherUser = governedProjectMemory(99L, 7L, currentVersion,
                "Other user GraphRAG decision.");
        when(memories.findGovernedProjectCandidates(eq(USER_ID), eq(7L), eq(currentVersion),
                any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(valid, oldVersion, otherProject, otherUser));

        assertThat(service.retrieveProject(USER_ID, 7L, null, "GraphRAG").hasContent()).isFalse();
        assertThat(service.retrieveProject(USER_ID, 7L, "client-history-version", "GraphRAG").hasContent()).isFalse();

        AgentLongTermMemoryContext context = service.retrieveProject(USER_ID, 7L, currentVersion, "GraphRAG");

        assertThat(context.hitCount()).isEqualTo(1);
        assertThat(context.content()).contains("Current project GraphRAG decision")
                .doesNotContain("Old project", "Other project", "Other user");
    }

    @Test
    void snapshotsAllGovernedUserAndCurrentProjectMemoriesWithoutQueryRelevanceFiltering() {
        String currentVersion = "a".repeat(64);
        AgentLongTermMemory language = identified(memory(
                "PREFERENCE", "Prefer concise Chinese answers.", "[]", "0.90"),
                11L, Instant.parse("2026-08-17T10:00:00Z"));
        AgentLongTermMemory unrelated = identified(memory(
                "FACT", "The user studies radar signal processing.", "[]", "0.80"),
                12L, Instant.parse("2026-08-17T09:00:00Z"));
        AgentLongTermMemory project = identified(governedProjectMemory(
                USER_ID, 7L, currentVersion, "This Project targets Java 17."),
                13L, Instant.parse("2026-08-17T11:00:00Z"));
        AgentLongTermMemory oldProject = identified(governedProjectMemory(
                USER_ID, 7L, "b".repeat(64), "An old revision used Java 8."),
                14L, Instant.parse("2026-08-17T12:00:00Z"));
        when(memories.findGovernedUserCandidates(eq(USER_ID), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(language, unrelated));
        when(memories.findGovernedProjectCandidates(eq(USER_ID), eq(7L), eq(currentVersion),
                any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(project, oldProject));

        LongTermMemorySnapshot snapshot = service.retrieveAllGoverned(USER_ID, 7L, currentVersion);

        assertThat(snapshot.entries()).extracting(LongTermMemorySnapshot.Entry::id)
                .containsExactly("11", "12", "13");
        assertThat(snapshot.entries()).extracting(LongTermMemorySnapshot.Entry::content)
                .containsExactly(
                        "Prefer concise Chinese answers.",
                        "The user studies radar signal processing.",
                        "This Project targets Java 17.");
        assertThat(snapshot.entries()).extracting(LongTermMemorySnapshot.Entry::scope)
                .containsExactly("USER", "USER", "PROJECT");
        assertThat(snapshot.entries()).extracting(LongTermMemorySnapshot.Entry::updatedAt)
                .containsExactly(
                        "2026-08-17T10:00:00Z",
                        "2026-08-17T09:00:00Z",
                        "2026-08-17T11:00:00Z");
    }

    private AgentLongTermMemory memory(String type, String content, String tagsJson, String confidence) {
        return govern(new AgentLongTermMemory(
                USER_ID,
                null,
                AgentLongTermMemory.SCOPE_USER,
                type,
                content,
                tagsJson,
                AgentLongTermMemory.SOURCE_USER_CONFIRMED,
                null,
                new BigDecimal(confidence),
                null
        ), null);
    }

    private AgentLongTermMemory memory(Long userId, Long projectId, String scope, String sourceType,
                                       String content, String confidence) {
        return govern(rawMemory(userId, projectId, scope, sourceType, content, confidence), null);
    }

    private AgentLongTermMemory rawMemory(Long userId, Long projectId, String scope, String sourceType,
                                          String content, String confidence) {
        return new AgentLongTermMemory(userId, projectId, scope, "FACT", content, "[\"GraphRAG\"]",
                sourceType, null, new BigDecimal(confidence), null);
    }

    private AgentLongTermMemory governedProjectMemory(Long userId, Long projectId, String projectVersion,
                                                       String content) {
        return govern(rawMemory(userId, projectId, AgentLongTermMemory.SCOPE_PROJECT,
                AgentLongTermMemory.SOURCE_USER_CONFIRMED, content, "0.90"), projectVersion);
    }

    private AgentLongTermMemory govern(AgentLongTermMemory memory, String projectVersion) {
        ReflectionTestUtils.setField(memory, "confirmationStatus", AgentLongTermMemory.CONFIRMATION_CONFIRMED);
        ReflectionTestUtils.setField(memory, "confirmedAt", Instant.now().minusSeconds(30));
        ReflectionTestUtils.setField(memory, "confirmedSource", AgentLongTermMemory.CONFIRMED_SOURCE_USER_ACTION);
        ReflectionTestUtils.setField(memory, "provenanceType", AgentLongTermMemory.PROVENANCE_USER_MESSAGE);
        ReflectionTestUtils.setField(memory, "provenanceRef", "session:1:message:1");
        ReflectionTestUtils.setField(memory, "projectVersion", projectVersion);
        return memory;
    }

    private AgentLongTermMemory identified(AgentLongTermMemory memory, Long id, Instant updatedAt) {
        ReflectionTestUtils.setField(memory, "id", id);
        ReflectionTestUtils.setField(memory, "updatedAt", updatedAt);
        return memory;
    }

    private String repeated(String value) {
        return value.repeat(40);
    }
}
