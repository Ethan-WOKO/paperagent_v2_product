package com.yanban.api.agent.v2.chain.finalization;

import io.paperagent.v2.chain.ChainFinalization;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainTaskOutcomeWriter;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainTransitionType;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ProposalFields;
import io.paperagent.v2.chain.ReflectorPayload;
import io.paperagent.v2.chain.finalization.ChainCompletedOutcomePort;
import io.paperagent.v2.chain.finalization.ChainProjectPublishPort;
import io.paperagent.v2.chain.review.ChainTaskOutcomeRuntime;
import io.paperagent.v2.chain.model.StrictChainProviderOutputParser;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** The product's only completion path into the sole TaskOutcome runtime. */
@Component
public final class ProductChainCompletedOutcomeAdapter
        implements ChainCompletedOutcomePort {
    private final ChainFinalizationRepository finalization;
    private final ChainWorkflowRepository workflow;
    private final ChainModelRepository models;
    private final ChainTaskOutcomeRuntime outcomes;

    public ProductChainCompletedOutcomeAdapter(
            ChainFinalizationRepository finalization,
            ChainWorkflowRepository workflow,
            ChainModelRepository models,
            ChainTaskOutcomeWriter writer) {
        this.finalization = Objects.requireNonNull(
                finalization, "finalization");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.models = Objects.requireNonNull(models, "models");
        this.outcomes = new ChainTaskOutcomeRuntime(
                Objects.requireNonNull(writer, "writer"),
                new CompletedSourceVerifier());
    }

    @Override
    public CompletionSubmission complete(CompletionCommand command) {
        Objects.requireNonNull(command, "command");
        TerminalAuthorities terminal = terminalAuthorities(
                command.finalizationTransitionId(),
                command.readiness().taskId());
        ChainPersistenceRecords.FinalizationReadinessRecord readiness =
                terminal.readiness();
        ChainPersistenceRecords.FinalizationCheckRecord check =
                terminal.check();
        require(readiness.equals(command.readiness())
                        && check.equals(command.check()),
                "completion command changed finalization authority");
        ChainProjectPublishPort.Published published = exactPublished(
                terminal, command.published());
        FinalFacts facts = finalFacts(readiness);
        String eventId = "task-outcome." + sha256(
                readiness.taskId() + "\0"
                        + command.finalizationTransitionId());
        ChainTaskOutcomeRuntime.OutcomeDraft draft =
                new ChainTaskOutcomeRuntime.OutcomeDraft(
                        readiness.taskId(), eventId,
                        command.sourceCommandId(), readiness.instructionId(),
                        readiness.taskFrameId(), readiness.finalPlanId(),
                        readiness.finalPlanRevisionId(), readiness.coverage(),
                        readiness.acceptedSet(), readiness.artifactId(),
                        readiness.candidateKey(), readiness.readinessId(),
                        check.finalizationCheckId(), readiness.validationId(),
                        readiness.validationRequestDigest(),
                        readiness.validationReceiptDigest(),
                        readiness.publishRequirement(),
                        readiness.publishRequirementDigest(),
                        published == null ? null : published.operationId(),
                        published == null ? null
                                : published.publishedProjectVersion(),
                        published == null ? null
                                : published.publishedRevisionId(),
                        published == null ? null
                                : published.publishReceiptId(),
                        facts.incompleteItems(), facts.limitations(),
                        facts.risks(), check.createdAt());
        ChainTaskOutcomeRuntime.CommitResult committed = outcomes.commit(
                new ChainTaskOutcomeRuntime.Completed(
                        draft, command.finalizationTransitionId()));
        ChainPersistenceRecords.TaskOutcomeRecord formal = finalization
                .findTaskOutcome(readiness.taskId())
                .filter(committed.outcome()::equals)
                .orElseThrow(() -> new IllegalStateException(
                        "committed TaskOutcome is not the formal product fact"));
        return new CompletionSubmission(formal, committed.replayed());
    }

    private TerminalAuthorities terminalAuthorities(
            String transitionId, String taskId) {
        ChainPersistenceRecords.TransitionRecord transition = workflow
                .findTransition(transitionId)
                .filter(item -> item.transitionType()
                        == ChainTransitionType.FINALIZATION)
                .filter(item -> item.taskId().equals(taskId))
                .orElseThrow(() -> new IllegalStateException(
                        "completed outcome lacks FINALIZATION authority"));
        List<ChainPersistenceRecords.TransitionStageRecord> stages = workflow
                .findTransitionStages(transition.transitionId()).stream()
                .sorted(Comparator.comparingInt(ChainPersistenceRecords
                        .TransitionStageRecord::stageOrdinal)).toList();
        requirePrefix(stages, ChainTransitionStage.OPEN,
                ChainTransitionStage.READINESS_VERIFIED,
                ChainTransitionStage.FINALIZATION_CHECK_COMMITTED,
                ChainTransitionStage.PUBLISH_COMMITTED_OR_NOT_REQUIRED);
        ChainPersistenceRecords.FinalizationReadinessRecord readiness =
                finalization.findReadinessById(
                                stages.get(1).predecessorAuthorityRef())
                        .filter(item -> item.taskId().equals(taskId))
                        .orElseThrow(() -> new IllegalStateException(
                                "completed outcome readiness is missing"));
        String checkId = stages.get(2).successorAuthorityRef();
        List<ChainPersistenceRecords.FinalizationCheckRecord> exactChecks =
                finalization.findFinalizationChecks(readiness.readinessId())
                        .stream()
                        .filter(item -> item.finalizationCheckId().equals(
                                checkId))
                        .filter(item -> item.taskId().equals(taskId))
                        .filter(item -> item.transitionId().equals(
                                transition.transitionId()))
                        .toList();
        require(exactChecks.size() == 1,
                "completed outcome check is missing or ambiguous");
        ChainPersistenceRecords.FinalizationCheckRecord check =
                exactChecks.get(0);
        require(check.resultStatus() == ChainFinalization.Outcome.PASSED
                        && check.readinessId().equals(readiness.readinessId())
                        && check.taskFrameId().equals(readiness.taskFrameId())
                        && check.finalPlanRevisionId().equals(
                        readiness.finalPlanRevisionId())
                        && check.acceptedSetSha256().equals(
                        readiness.acceptedSet().sha256())
                        && check.candidateKey().equals(readiness.candidateKey())
                        && check.workspaceId().equals(readiness.workspaceId())
                        && check.validationId().equals(readiness.validationId())
                        && Objects.equals(check.validationRequestDigest(),
                        readiness.validationRequestDigest())
                        && Objects.equals(check.validationReceiptDigest(),
                        readiness.validationReceiptDigest())
                        && check.publishRequirementDigest().equals(
                        readiness.publishRequirementDigest())
                        && check.instructionId().equals(readiness.instructionId())
                        && check.projectVersion().equals(
                        readiness.projectVersion()),
                "completed outcome check changed readiness authority");
        return new TerminalAuthorities(transition, stages, readiness, check);
    }

    private static ChainProjectPublishPort.Published exactPublished(
            TerminalAuthorities terminal,
            ChainProjectPublishPort.Published published) {
        String publishRef = terminal.stages().get(3).successorAuthorityRef();
        ChainPersistenceRecords.FinalizationReadinessRecord readiness =
                terminal.readiness();
        if (readiness.publishRequirement()
                == io.paperagent.v2.chain.ChainPublishRequirement.NOT_REQUIRED) {
            require(publishRef == null && published == null,
                    "NOT_REQUIRED completion carries publish authority");
            return null;
        }
        require(publishRef != null && published != null
                        && publishRef.equals(published.publishReceiptId())
                        && readiness.projectVersion().equals(
                        published.baseProjectVersion())
                        && readiness.candidateKey().equals(
                        published.candidateKey())
                        && readiness.validationId().equals(
                        published.validationId()),
                "REQUIRED completion publish result changed authority");
        return published;
    }

    /** Reuses the sole TaskOutcome runtime for non-completed typed boundaries. */
    public ChainTaskOutcomeRuntime.CommitResult commit(
            ChainTaskOutcomeRuntime.OutcomeCommand command,
            ChainTaskOutcomeRuntime.FormalSourceVerifier verifier) {
        return outcomes.commit(command, verifier);
    }

    private FinalFacts finalFacts(
            ChainPersistenceRecords.FinalizationReadinessRecord readiness) {
        ChainPersistenceRecords.ReviewDecisionRecord review = workflow
                .findReviewDecisions(readiness.taskId()).stream()
                .filter(item -> item.taskId().equals(readiness.taskId()))
                .filter(item -> item.reviewDecisionId().equals(
                        readiness.reviewDecisionId()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "readiness ReviewDecision is missing"));
        ChainPersistenceRecords.ModelProposalRecord proposal = models
                .findProposal(review.proposalId())
                .filter(item -> item.taskId().equals(readiness.taskId()))
                .filter(item -> item.proposalKind()
                        == review.decisionKind())
                .orElseThrow(() -> new IllegalStateException(
                        "readiness proposal authority is missing"));
        var states = models.findProposalStateEvents(proposal.proposalId())
                .stream().sorted(Comparator.comparingLong(
                        ChainPersistenceRecords.ProposalStateEventRecord
                                ::stateSequence)).toList();
        require(!states.isEmpty(), "readiness proposal has no formal state");
        var state = states.get(states.size() - 1);
        require(state.stateKind()
                        == ChainProposalState.REPLACED_BY_OFFICIAL_RESULT
                        && "REVIEW_DECISION".equals(
                        state.officialAuthorityType())
                        && review.reviewDecisionId().equals(
                        state.officialAuthorityRef()),
                "readiness proposal is not bound to its ReviewDecision");
        String envelope = "{\"schemaVersion\":\"1\",\"kind\":\""
                + proposal.proposalKind().wireName() + "\",\"payload\":"
                + proposal.payload().json() + "}";
        var output = new StrictChainProviderOutputParser().parse(
                envelope, ChainRole.REFLECTOR,
                ChainWorkState.FINALIZING, null);
        ReflectorPayload reflector = (ReflectorPayload) output.payload();
        ProposalFields.FinalizationAssessment assessment;
        if (reflector
                instanceof ReflectorPayload.ReadyToFinalize value) {
            assessment = value.finalization();
        } else if (reflector instanceof ReflectorPayload
                .AcceptStepAndReadyToFinalize value) {
            assessment = value.finalization();
        } else {
            throw new IllegalStateException(
                    "readiness proposal is not a READY finalization payload");
        }
        require(canonicalArray(reflector.review().directFactRefs())
                        .equals(review.factRefs()),
                "readiness ReviewDecision changed proposal fact refs");
        List<String> incomplete = assessment.requirementCoverage().stream()
                .filter(item -> item.status()
                        != ProposalFields.RequirementStatus.SATISFIED)
                .filter(item -> item.status()
                        != ProposalFields.RequirementStatus.NOT_APPLICABLE)
                .map(ProposalFields.RequirementCoverage::requirement)
                .toList();
        return new FinalFacts(canonicalArray(incomplete),
                canonicalArray(reflector.review().knownLimitations()),
                canonicalArray(assessment.residualRisks()));
    }

    private final class CompletedSourceVerifier
            implements ChainTaskOutcomeRuntime.FormalSourceVerifier {
        @Override
        public void verifyCompleted(ChainTaskOutcomeRuntime.Completed value) {
            ChainTaskOutcomeRuntime.OutcomeDraft draft = value.draft();
            TerminalAuthorities terminal = terminalAuthorities(
                    value.finalizationTransitionId(), draft.taskId());
            var readiness = terminal.readiness();
            var check = terminal.check();
            require(readiness.readinessId().equals(
                            draft.finalizationReadinessId())
                            && check.finalizationCheckId().equals(
                            draft.finalizationCheckId())
                            && readiness.taskFrameId().equals(
                            draft.taskFrameId())
                            && readiness.finalPlanRevisionId().equals(
                            draft.finalPlanRevisionId())
                            && readiness.acceptedSet().equals(
                            draft.acceptedSet())
                            && readiness.validationId().equals(
                            draft.validationId())
                            && Objects.equals(
                            readiness.validationRequestDigest(),
                            draft.validationRequestDigest())
                            && Objects.equals(
                            readiness.validationReceiptDigest(),
                            draft.validationReceiptDigest())
                            && readiness.publishRequirement()
                            == draft.publishRequirement()
                            && readiness.publishRequirementDigest().equals(
                            draft.publishRequirementDigest())
                            && Objects.equals(terminal.stages().get(3)
                            .successorAuthorityRef(), draft.publishReceiptId()),
                    "completed outcome changed exact terminal authority");
        }

        @Override
        public void verifyFailed(ChainTaskOutcomeRuntime.Failed value) {
            throw unsupported();
        }

        @Override
        public void verifyCancelled(ChainTaskOutcomeRuntime.Cancelled value) {
            throw unsupported();
        }

        @Override
        public void verifySuperseded(
                ChainTaskOutcomeRuntime.Superseded value) {
            throw unsupported();
        }
    }

    private static void requirePrefix(
            List<ChainPersistenceRecords.TransitionStageRecord> stages,
            ChainTransitionStage... expected) {
        require(stages.size() >= expected.length,
                "FINALIZATION prefix is incomplete");
        for (int index = 0; index < expected.length; index++) {
            require(stages.get(index).stageOrdinal() == index
                            && stages.get(index).stageCode()
                            == expected[index],
                    "FINALIZATION prefix is inconsistent");
        }
    }

    private static ChainPersistenceRecords.CanonicalJson canonical(
            String json) {
        return new ChainPersistenceRecords.CanonicalJson(
                1, sha256(json), json);
    }

    private static ChainPersistenceRecords.CanonicalJson canonicalArray(
            List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) json.append(',');
            json.append('"');
            for (int cursor = 0; cursor < values.get(index).length(); cursor++) {
                char character = values.get(index).charAt(cursor);
                switch (character) {
                    case '"' -> json.append("\\\"");
                    case '\\' -> json.append("\\\\");
                    case '\b' -> json.append("\\b");
                    case '\f' -> json.append("\\f");
                    case '\n' -> json.append("\\n");
                    case '\r' -> json.append("\\r");
                    case '\t' -> json.append("\\t");
                    default -> {
                        if (character < 0x20) {
                            json.append(String.format("\\u%04x",
                                    (int) character));
                        } else {
                            json.append(character);
                        }
                    }
                }
            }
            json.append('"');
        }
        return canonical(json.append(']').toString());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException(
                "completion adapter accepts COMPLETED only");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record FinalFacts(
            ChainPersistenceRecords.CanonicalJson incompleteItems,
            ChainPersistenceRecords.CanonicalJson limitations,
            ChainPersistenceRecords.CanonicalJson risks) {
    }

    private record TerminalAuthorities(
            ChainPersistenceRecords.TransitionRecord transition,
            List<ChainPersistenceRecords.TransitionStageRecord> stages,
            ChainPersistenceRecords.FinalizationReadinessRecord readiness,
            ChainPersistenceRecords.FinalizationCheckRecord check) {
        private TerminalAuthorities {
            stages = List.copyOf(stages);
        }
    }
}
