package io.paperagent.v2.runtime.synthesis;

import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.FinalSynthesis;
import io.paperagent.v2.contracts.FinalSynthesisId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Creates one replayable delivery strictly from a terminal recovered authority cut. */
public final class DefaultFinalSynthesisComposer {
    private final FinalSynthesisStore syntheses;
    private final FinalSynthesisReceiptSource receipts;
    private final LiteratureIntentOwnershipSource intents;
    private final FinalSynthesisNarrator narrator;

    public DefaultFinalSynthesisComposer(
            FinalSynthesisStore syntheses,
            FinalSynthesisReceiptSource receipts,
            LiteratureIntentOwnershipSource intents,
            FinalSynthesisNarrator narrator) {
        this.syntheses = required(syntheses);
        this.receipts = required(receipts);
        this.intents = required(intents);
        this.narrator = required(narrator);
    }

    public FinalSynthesisCompositionResult compose(
            FinalSynthesisCompositionRequest request) {
        required(request);
        var cut = request.terminalCut();
        var planId = cut.plan().id();

        var prior = syntheses.find(planId);
        if (prior.isPresent()) {
            FinalSynthesis value = prior.orElseThrow();
            if (!value.planRevisionId().equals(cut.plan().latestRevision().id())
                    || !value.taskFrameId().equals(cut.taskFrame().id())
                    || !value.sourceProjectVersion().equals(
                    cut.taskFrame().sourceProjectVersion())
                    || !value.workspaceDiff().equals(request.workspaceDiff())
                    || !value.receiptIds().equals(
                    cut.checkpoint().receiptReferences())) {
                throw failure("replay");
            }
            return new FinalSynthesisCompositionResult(
                    value, FinalSynthesisDisposition.REPLAYED);
        }
        PlanRevision revision = cut.plan().latestRevision();
        var checkpoint = cut.checkpoint();
        if (!cut.taskFrame().id().equals(cut.plan().taskFrameId())
                || !checkpoint.taskFrameId().equals(cut.taskFrame().id())
                || !checkpoint.planId().equals(planId)
                || !checkpoint.revisionId().equals(revision.id())
                || checkpoint.revisionNumber() != revision.number()
                || checkpoint.planState()
                != io.paperagent.v2.contracts.PlanExecutionState.SUCCEEDED) {
            throw failure("terminal");
        }

        List<ReceiptId> authoritativeIds = new ArrayList<>();
        Map<ReceiptId, io.paperagent.v2.contracts.PlanStepId> receiptOwners =
                new LinkedHashMap<>();
        for (var step : revision.steps()) {
            CompletionFact fact = revision.completedFacts().get(step.id());
            if (fact == null) {
                throw failure("completionFacts");
            }
            for (ReceiptId receiptId : fact.receiptReferences()) {
                if (receiptOwners.put(receiptId, step.id()) != null) {
                    throw failure("receiptSet");
                }
                authoritativeIds.add(receiptId);
            }
        }
        if (authoritativeIds.isEmpty()
                || !authoritativeIds.equals(checkpoint.receiptReferences())) {
            throw failure("receiptSet");
        }

        List<FinalSynthesisReceiptProjection> projections = new ArrayList<>();
        for (ReceiptId receiptId : authoritativeIds) {
            var found = receipts.find(receiptId);
            if (found.isEmpty()) {
                throw failure("receipt");
            }
            ExecutionReceipt receipt = found.orElseThrow();
            if (!receipt.id().equals(receiptId)
                    || receipt.status() != ReceiptStatus.SUCCESS) {
                throw failure("receipt");
            }
            if (!intents.owns(
                    receipt.toolCallId(), planId,
                    receiptOwners.get(receiptId), "literature.search")) {
                throw failure("receiptOwnership");
            }
            String summary = receipt.standardOutput().inlineText()
                    .map(DefaultFinalSynthesisComposer::bounded)
                    .orElse("output-referenced");
            projections.add(new FinalSynthesisReceiptProjection(
                    receipt.id(), receipt.toolCallId(),
                    receipt.status().name(), summary));
        }

        String narrative;
        try {
            narrative = narrator.narrate(new FinalSynthesisNarrationRequest(
                    cut.taskFrame().id(), planId, revision.id(), projections));
        } catch (RuntimeException exception) {
            throw failure("provider");
        }
        if (narrative == null || narrative.isBlank() || narrative.length() > 4_096) {
            throw failure("provider");
        }
        FinalSynthesis proposed = new FinalSynthesis(
                new FinalSynthesisId("synthesis-" + hash(
                        planId.value() + "\0" + revision.id().value())),
                cut.taskFrame().id(), planId, revision.id(),
                cut.taskFrame().sourceProjectVersion(),
                request.workspaceDiff(), authoritativeIds,
                narrative.strip(), request.observedAt());
        var appended = syntheses.append(proposed);
        if (appended.isEmpty()) {
            // A concurrent equivalent writer may already have committed.
            var winner = syntheses.find(planId);
            if (winner.isPresent()
                    && equivalent(proposed, winner.orElseThrow())) {
                return new FinalSynthesisCompositionResult(
                        winner.orElseThrow(),
                        FinalSynthesisDisposition.REPLAYED);
            }
            throw failure("persist");
        }
        return appended.orElseThrow();
    }

    private static boolean equivalent(FinalSynthesis left, FinalSynthesis right) {
        return left.id().equals(right.id())
                && left.taskFrameId().equals(right.taskFrameId())
                && left.planId().equals(right.planId())
                && left.planRevisionId().equals(right.planRevisionId())
                && left.sourceProjectVersion().equals(right.sourceProjectVersion())
                && left.workspaceDiff().equals(right.workspaceDiff())
                && left.receiptIds().equals(right.receiptIds())
                && left.narrative().equals(right.narrative());
    }

    private static String bounded(String value) {
        String sanitized = value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ");
        return sanitized.length() <= 512 ? sanitized : sanitized.substring(0, 512);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static <T> T required(T value) {
        if (value == null) {
            throw new IllegalArgumentException("final synthesis collaborator is required");
        }
        return value;
    }

    private static FinalSynthesisCompositionException failure(String stage) {
        return new FinalSynthesisCompositionException(stage);
    }
}
