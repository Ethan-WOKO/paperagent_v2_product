package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PublishRequirement;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskRequirements;
import io.paperagent.v2.contracts.ValidationRequirement;
import io.paperagent.v2.contracts.ValidationSubject;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductPlanBootstrapCodecTest {
    private final ProductPlanBootstrapCodec codec =
            new ProductPlanBootstrapCodec(new ObjectMapper());

    @Test
    void emitsDeterministicJsonAndHashForEquivalentSetsAndMaps() {
        PersistedPlanBootstrap first = ProductPlanBootstrapTestFixtures.tuple(
                "plan-1", "task-1", Optional.empty(), false);
        PersistedPlanBootstrap second = ProductPlanBootstrapTestFixtures.tuple(
                "plan-1", "task-1", Optional.empty(), true);

        ProductPlanBootstrapCodec.EncodedPayload left = codec.encode(first);
        ProductPlanBootstrapCodec.EncodedPayload right = codec.encode(second);

        assertEquals(left.json(), right.json());
        assertEquals(left.sha256(), right.sha256());
        assertEquals(
                "11c60162e3efe9eb7d6738f4233c66fd82e771bd03dd88ba995979fc15cdff5f",
                left.sha256());
        assertEquals(64, left.sha256().length());
        assertEquals(left.sha256().toLowerCase(), left.sha256());
        assertFalse(left.json().contains("@class"));
    }

    @Test
    void roundTripsWorkspaceTupleExactly() {
        assertRoundTrip(ProductPlanBootstrapTestFixtures.workspace("plan-w", "task-w"));
    }

    @Test
    void roundTripsProjectTupleWithLimitsCapabilitiesNetworkAndSecretRefs() {
        assertRoundTrip(ProductPlanBootstrapTestFixtures.project("plan-p", "task-p"));
    }

    @Test
    void roundTripsNonEmptyStepConstraints() {
        var expected = ProductPlanBootstrapTestFixtures.workspaceWithStepConstraints();
        var encoded = codec.encode(expected);

        assertEquals(expected, codec.decode(
                encoded.formatVersion(), encoded.sha256(), encoded.json()));
        assertEquals(1, encoded.json().split("preserve unrelated content", -1).length - 1);
    }

    @Test
    void roundTripsTypedCandidateStepMetadata() {
        var expected = ProductPlanBootstrapTestFixtures.workspaceWithCandidateMetadata();
        var encoded = codec.encode(expected);

        assertEquals(expected, codec.decode(
                encoded.formatVersion(), encoded.sha256(), encoded.json()));
        assertEquals(true, expected.plan().latestRevision().steps().get(1)
                .mayChangeCandidate());
        assertEquals("finished", expected.plan().latestRevision().steps().get(1)
                .candidateValidationCompletionCondition());
    }

    @Test
    void roundTripsExplicitRequirementsAndStableStepBindingsInFormatTwo() {
        PersistedPlanBootstrap legacy =
                ProductPlanBootstrapTestFixtures.workspaceWithCandidateMetadata();
        TaskFrame oldFrame = legacy.taskFrame();
        TaskFrame frame = new TaskFrame(
                oldFrame.id(), oldFrame.objective(), oldFrame.targets(),
                oldFrame.deliverables(), oldFrame.constraints(),
                TaskRequirements.explicit(List.of(new ValidationRequirement(
                        "validate-candidate", ValidationSubject.CANDIDATE,
                        "finished")), PublishRequirement.REQUIRED),
                oldFrame.sourceProjectVersion(), oldFrame.executionProfile(),
                oldFrame.createdAt());
        PlanRevision oldRevision = legacy.plan().latestRevision();
        PlanStep oldStep = oldRevision.steps().get(1);
        PlanStep boundStep = new PlanStep(
                oldStep.id(), oldStep.intent(), oldStep.expectedOutcome(),
                oldStep.dependencies(), oldStep.completionCriteria(),
                oldStep.executionHints(), oldStep.constraints(),
                oldStep.mayChangeCandidate(),
                oldStep.candidateValidationCompletionCondition(),
                List.of("validate-candidate"));
        PlanRevision revision = new PlanRevision(
                oldRevision.id(), oldRevision.taskFrameId(), oldRevision.number(),
                oldRevision.parentRevisionId(), oldRevision.reason(),
                oldRevision.createdAt(),
                List.of(oldRevision.steps().get(0), boundStep),
                oldRevision.completedFacts());
        PersistedPlanBootstrap expected = new PersistedPlanBootstrap(
                frame, new Plan(legacy.plan().id(), legacy.plan().taskFrameId(),
                List.of(revision)), legacy.initialCheckpoint());

        var encoded = codec.encode(expected);

        assertEquals(2, encoded.formatVersion());
        assertEquals(expected, codec.decode(
                encoded.formatVersion(), encoded.sha256(), encoded.json()));
        assertEquals(List.of("validate-candidate"), codec.decode(
                encoded.formatVersion(), encoded.sha256(), encoded.json())
                .plan().latestRevision().steps().get(1)
                .validationRequirementIds());
    }

    @Test
    void legacyPayloadRemainsFormatOneAndDecodesAsLegacyUnspecified() {
        PersistedPlanBootstrap legacy = ProductPlanBootstrapTestFixtures.workspace(
                "plan-legacy", "task-legacy");
        var encoded = codec.encode(legacy);
        var decoded = codec.decode(
                encoded.formatVersion(), encoded.sha256(), encoded.json());

        assertEquals(1, encoded.formatVersion());
        assertEquals(io.paperagent.v2.contracts.RequirementDeclarationMode.LEGACY_UNSPECIFIED,
                decoded.taskFrame().requirements().declarationMode());
        assertEquals(encoded, codec.encode(decoded));
    }

    @Test
    void rejectsHashMismatchWithoutPayloadLeakOrCause() {
        var encoded = codec.encode(
                ProductPlanBootstrapTestFixtures.workspace("plan-secret", "task-secret"));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> codec.decode(
                        encoded.formatVersion(),
                        "0".repeat(64),
                        encoded.json()));

        assertSanitized(failure);
    }

    @Test
    void rejectsUnsupportedVersionWithoutPayloadLeakOrCause() {
        var encoded = codec.encode(
                ProductPlanBootstrapTestFixtures.workspace("plan-secret", "task-secret"));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> codec.decode(3, encoded.sha256(), encoded.json()));

        assertSanitized(failure);
    }

    @Test
    void rejectsUndecodablePayloadWithoutLeakingParserExcerpt() {
        String payload = "{\"private-user-content\":\"DO_NOT_EXPOSE\"";
        String hash = sha256(payload);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> codec.decode(1, hash, payload));

        assertSanitized(failure);
        assertFalse(failure.getMessage().contains("DO_NOT_EXPOSE"));
    }

    private void assertRoundTrip(PersistedPlanBootstrap expected) {
        var encoded = codec.encode(expected);
        assertEquals(expected, codec.decode(
                encoded.formatVersion(), encoded.sha256(), encoded.json()));
    }

    private static void assertSanitized(IllegalStateException failure) {
        assertEquals("Stored V2 Plan bootstrap payload is invalid", failure.getMessage());
        assertNull(failure.getCause());
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
