package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.persistence.PersistedStepCompletion;
import io.paperagent.v2.persistence.StepCompletionRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductStepCompletionCodecTest {
    private final ProductExecutionStartCodec execution =
            new ProductExecutionStartCodec(new ObjectMapper());
    private final ProductStepCompletionCodec codec =
            new ProductStepCompletionCodec(new ObjectMapper(), execution);

    @Test
    void fullRequestAndResultRoundTripCanonically() {
        var scenario = scenario();
        var request = ProductStepCompletionTestFixtures.request(
                scenario, "token-a", 7, "completion-a", List.of());
        var result = new PersistedStepCompletion(
                request.planId(), request.stepId(), "owner-a", 7,
                request.completionEvent(), request.completedRevision(),
                new VersionedCheckpoint(4, request.completedCheckpoint()));
        var encodedRequest = codec.encodeRequest(request);
        var encodedResult = codec.encodeResult(result);
        assertEquals(2, encodedRequest.formatVersion());
        assertEquals(2, encodedResult.formatVersion());
        assertEquals(encodedRequest, codec.encodeRequest(request));
        assertEquals(encodedResult, codec.encodeResult(result));
        assertEquals(request, codec.decodeRequest(
                encodedRequest.formatVersion(), encodedRequest.sha256(),
                encodedRequest.json()));
        assertEquals(result, codec.decodeResult(
                encodedResult.formatVersion(), encodedResult.sha256(),
                encodedResult.json()));
    }

    @Test
    void candidateStepMetadataSurvivesCompletionRoundTrip() {
        var scenario = scenario(
                ProductPlanBootstrapTestFixtures.workspaceWithCandidateMetadata());
        var request = ProductStepCompletionTestFixtures.request(
                scenario, "token-candidate", 7, "completion-candidate", List.of());
        request = withValidationBinding(request);
        var encoded = codec.encodeRequest(request);

        var decoded = codec.decodeRequest(
                encoded.formatVersion(), encoded.sha256(), encoded.json());
        var candidateStep = decoded.completedRevision().steps().get(1);
        assertEquals(true, candidateStep.mayChangeCandidate());
        assertEquals("finished",
                candidateStep.candidateValidationCompletionCondition());
        assertEquals(List.of("validate-candidate"),
                candidateStep.validationRequirementIds());
        assertEquals(2, encoded.formatVersion());
    }

    @Test
    void legacyFormatOneRemainsReadableButIsUpgradedOnWrite()
            throws Exception {
        var request = ProductStepCompletionTestFixtures.request(
                scenario(), "legacy-token", 7, "legacy-completion", List.of());
        var source = codec.encodeRequest(request);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode legacy = (ObjectNode) mapper.readTree(source.json());
        legacy.put("format", 1);
        legacy.withObject("completedRevision").withArray("steps")
                .forEach(step -> ((ObjectNode) step)
                        .remove("validationRequirementIds"));
        String json = mapper.writeValueAsString(legacy);
        String hash = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                        json.getBytes(StandardCharsets.UTF_8)));

        var decoded = codec.decodeRequest(1, hash, json);

        assertEquals(List.of(), decoded.completedRevision().steps().get(0)
                .validationRequirementIds());
        assertEquals(2, codec.encodeRequest(decoded).formatVersion());
    }

    @Test
    void corruptAndNoncanonicalDocumentsFailWithSanitizedMessage()
            throws Exception {
        var request = ProductStepCompletionTestFixtures.request(
                scenario(), "secret-token", 1, "completion-a", List.of());
        var encoded = codec.encodeRequest(request);
        IllegalStateException hash = assertThrows(
                IllegalStateException.class, () -> codec.decodeRequest(
                        1, "0".repeat(64), encoded.json()));
        assertEquals("Stored V2 step completion payload is invalid",
                hash.getMessage());
        String changed = encoded.json().replaceFirst(
                "\\{", "{\"unknown\":\"secret-token\",");
        String digest = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                        changed.getBytes(StandardCharsets.UTF_8)));
        IllegalStateException noncanonical = assertThrows(
                IllegalStateException.class,
                () -> codec.decodeRequest(1, digest, changed));
        assertEquals(hash.getMessage(), noncanonical.getMessage());
        org.junit.jupiter.api.Assertions.assertFalse(
                noncanonical.toString().contains("secret-token"));
    }

    private static ProductEffectIntentTestFixtures.Scenario scenario() {
        return scenario(ProductPlanBootstrapTestFixtures.workspace(
                "plan-a", "task-a"));
    }

    private static ProductEffectIntentTestFixtures.Scenario scenario(
            io.paperagent.v2.persistence.PersistedPlanBootstrap bootstrap) {
        var activation = ProductStepActivationTestFixtures.request(
                bootstrap, "token-a", 7, "activation-a");
        var persisted = new io.paperagent.v2.persistence.PersistedStepActivation(
                activation.planId(), activation.stepId(), "owner-a", 7,
                activation.activationEvent(),
                new VersionedCheckpoint(3, activation.activatedCheckpoint()));
        return new ProductEffectIntentTestFixtures.Scenario(
                bootstrap, activation, persisted);
    }

    private static StepCompletionRequest withValidationBinding(
            StepCompletionRequest request) {
        PlanRevision oldRevision = request.completedRevision();
        PlanStep oldStep = oldRevision.steps().get(1);
        PlanStep bound = new PlanStep(
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
                List.of(oldRevision.steps().get(0), bound),
                oldRevision.completedFacts());
        return new StepCompletionRequest(
                request.planId(), request.leaseToken(), request.fencingToken(),
                request.expectedRevisionId(), request.expectedRevisionNumber(),
                request.expectedCheckpointVersion(),
                request.expectedEventHeadSequence(), request.stepId(),
                request.completionFact(), request.completionEvent(), revision,
                request.completedCheckpoint());
    }
}
