package com.yanban.api.agent.v2.chain.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.chain.persistence.ProductChainWorkflowRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.persistence.EffectIntentRepository;
import io.paperagent.v2.persistence.EffectOutcomeRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds a catalog solely from retained Chain and effect authorities. */
final class ProductMemoryEvidenceAuthority {
    private final ProductChainWorkflowRepositoryAdapter workflow;
    private final ProductMemoryEvidenceActionReader actions;
    private final ProductMemoryEvidenceRefParser refs;
    private final ProductMemoryEvidenceEntryFactory entries =
            new ProductMemoryEvidenceEntryFactory();

    ProductMemoryEvidenceAuthority(
            ChainFoundationRepository foundations,
            ProductChainWorkflowRepositoryAdapter workflow,
            EffectIntentRepository intents,
            EffectOutcomeRepository outcomes,
            ChainFinalizationRepository finalization,
            ObjectMapper json) {
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.actions = new ProductMemoryEvidenceActionReader(
                foundations, workflow, intents, outcomes, finalization);
        this.refs = new ProductMemoryEvidenceRefParser(json);
    }

    ProductMemoryEvidenceFacts load(
            ChainPersistenceRecords.ContextRevisionRecord building) {
        boolean directAnswer = ProductDirectAnswerContextAuthority
                .isDirectAnswer(building);
        if (directAnswer) {
            ProductDirectAnswerContextAuthority.require(building, workflow);
        }
        ProductMemoryEvidenceActionReader.Cut actionFacts =
                actions.load(building);
        var candidates = formal(building.taskId(),
                workflow.findCandidateStepResults(building.taskId()),
                actionFacts.eventSequences());
        verifyCurrentCandidates(building, candidates);
        var accepted = formal(building.taskId(),
                workflow.findAcceptedResults(building.taskId()),
                actionFacts.eventSequences());
        Map<String, ChainPersistenceRecords.CandidateStepResultRecord> byId =
                uniqueCandidates(candidates);
        Set<String> acceptedIds = directAnswer ? Set.of() : acceptedIds(
                building, actionFacts.taskOutcome(), accepted, byId);
        List<ProductMemoryEvidenceFacts.EvidenceEntry> catalog =
                new ArrayList<>();
        for (var action : actionFacts.actions()) {
            if (action.result() != null) {
                catalog.add(entries.receipt(action));
            }
        }
        for (var candidate : candidates) {
            boolean acceptedDelivery = acceptedIds.stream().anyMatch(id ->
                    accepted.stream().anyMatch(value ->
                            value.acceptedResultId().equals(id)
                                    && value.candidateResultId().equals(
                                    candidate.candidateResultId())));
            List<String> receiptRefs = refs.refs(candidate.receiptRefs());
            List<String> evidenceRefs = refs.refs(candidate.evidenceRefs());
            if (!receiptRefs.isEmpty() || !evidenceRefs.isEmpty()
                    || acceptedDelivery) {
                catalog.add(entries.candidate(candidate, receiptRefs,
                        evidenceRefs, acceptedDelivery,
                        actionFacts.eventSequences().get(candidate.eventId())));
            }
        }
        catalog.sort(Comparator.comparingLong(
                        ProductMemoryEvidenceFacts.EvidenceEntry
                                ::taskEventSequence)
                .thenComparing(ProductMemoryEvidenceFacts.EvidenceEntry
                        ::authorityRef));
        verifyUniqueEntries(catalog);
        return new ProductMemoryEvidenceFacts(
                building, actionFacts.taskEventCut(), catalog);
    }

    private static void verifyCurrentCandidates(
            ChainPersistenceRecords.ContextRevisionRecord building,
            List<ChainPersistenceRecords.CandidateStepResultRecord> values) {
        if (building.activationEventId() == null) return;
        for (var value : values) {
            if (!building.activationEventId().equals(
                    value.activationEventId())) continue;
            if (!building.instructionId().equals(value.instructionId())
                    || !Objects.equals(building.taskFrameId(),
                    value.taskFrameId())
                    || !Objects.equals(building.planId(), value.planId())
                    || !Objects.equals(building.planRevisionId(),
                    value.planRevisionId())
                    || !Objects.equals(building.planRevisionNumber(),
                    value.planRevisionNumber())
                    || !Objects.equals(building.stepId(), value.stepId())) {
                throw blocked("current activation Candidate evidence conflicts");
            }
        }
    }

    private Set<String> acceptedIds(
            ChainPersistenceRecords.ContextRevisionRecord building,
            ChainPersistenceRecords.TaskOutcomeRecord outcome,
            List<ChainPersistenceRecords.AcceptedResultRecord> accepted,
            Map<String, ChainPersistenceRecords.CandidateStepResultRecord> byId) {
        Set<String> ids = new HashSet<>();
        for (var value : accepted) {
            var candidate = byId.get(value.candidateResultId());
            if (candidate == null || !candidate.contentId().equals(
                    value.contentId()) || !ids.add(value.acceptedResultId())) {
                throw blocked("AcceptedResult evidence graph is inconsistent");
            }
        }
        if (building.role() != ChainRole.ANSWER) return Set.of();
        if (outcome == null) throw blocked("Answer evidence requires TaskOutcome");
        List<String> frozen = refs.refs(outcome.acceptedSet());
        if (!ids.containsAll(frozen)) {
            throw blocked("TaskOutcome accepted evidence set mismatches");
        }
        return Set.copyOf(frozen);
    }

    private static <T extends Record & ChainPersistenceRecords.TaskAuthorityFact>
            List<T> formal(String taskId, List<T> values,
            Map<String, Long> sequences) {
        for (T value : values) {
            if (!value.taskId().equals(taskId)
                    || !sequences.containsKey(value.eventId())) {
                throw blocked("evidence authority lacks formal task event");
            }
        }
        return List.copyOf(values);
    }

    private static Map<String, ChainPersistenceRecords.CandidateStepResultRecord>
            uniqueCandidates(
            List<ChainPersistenceRecords.CandidateStepResultRecord> values) {
        Map<String, ChainPersistenceRecords.CandidateStepResultRecord> result =
                new HashMap<>();
        for (var value : values) {
            if (result.put(value.candidateResultId(), value) != null) {
                throw blocked("Candidate evidence identity is duplicated");
            }
        }
        return Map.copyOf(result);
    }

    private static void verifyUniqueEntries(
            List<ProductMemoryEvidenceFacts.EvidenceEntry> entries) {
        Set<String> refs = new HashSet<>();
        for (var entry : entries) {
            if (!refs.add(entry.authorityRef())) {
                throw blocked("formal evidence authority ref is duplicated");
            }
        }
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(
                ChainContextModule.MEMORY_RETRIEVAL_AND_KNOWLEDGE_EVIDENCE,
                reason);
    }
}
