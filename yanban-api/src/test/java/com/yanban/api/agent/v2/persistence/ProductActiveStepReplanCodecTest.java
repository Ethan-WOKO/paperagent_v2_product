package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.persistence.ActiveStepReplanRequest;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductActiveStepReplanCodecTest {
    private final ProductActiveStepReplanCodec codec =
            new ProductActiveStepReplanCodec(new ObjectMapper());

    @Test
    void ordinaryStepWithoutValidationBindingsIsStillWrittenAsFormatTwo() {
        var encoded = codec.encodeRequest(
                ProductActiveStepReplanTestSupport.request("ordinary-codec"));

        assertEquals(2, encoded.formatVersion());
        assertTrue(encoded.json().contains(
                "\"validationRequirementIds\":[]"));
    }

    @Test
    void legacyFormatOneRemainsReadableButIsUpgradedOnWrite()
            throws Exception {
        var source = codec.encodeRequest(
                ProductActiveStepReplanTestSupport.request("legacy-codec"));
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode legacy = (ObjectNode) mapper.readTree(source.json());
        legacy.put("format", 1);
        legacy.withObject("replannedRevision").withArray("steps")
                .forEach(step -> ((ObjectNode) step)
                        .remove("validationRequirementIds"));
        String json = mapper.writeValueAsString(legacy);
        String hash = sha256(json);

        var decoded = codec.decodeRequest(1, hash, json);

        assertEquals(List.of(), decoded.replannedRevision().steps().get(0)
                .validationRequirementIds());
        assertEquals(2, codec.encodeRequest(decoded).formatVersion());
    }

    @Test
    void candidateStepMetadataSurvivesRequestRoundTrip() {
        ActiveStepReplanRequest base =
                ProductActiveStepReplanTestSupport.request("candidate-codec");
        PlanStep original = base.replannedRevision().steps().get(0);
        PlanStep candidate = new PlanStep(
                original.id(), original.intent(), original.expectedOutcome(),
                original.dependencies(), original.completionCriteria(),
                original.executionHints(), List.of("preserve unrelated behavior"),
                true, "replacement complete", List.of("validate-candidate"));
        PlanRevision revision = new PlanRevision(
                base.replannedRevision().id(),
                base.replannedRevision().taskFrameId(),
                base.replannedRevision().number(),
                base.replannedRevision().parentRevisionId(),
                base.replannedRevision().reason(),
                base.replannedRevision().createdAt(),
                List.of(candidate), base.replannedRevision().completedFacts());
        ActiveStepReplanRequest request = new ActiveStepReplanRequest(
                base.planId(), base.leaseToken(), base.fencingToken(),
                base.expectedRevisionId(), base.expectedRevisionNumber(),
                base.expectedCheckpointVersion(), base.expectedEventHeadSequence(),
                base.activeStepId(), base.supersessionEvent(),
                base.supersededCheckpoint(), base.replanEvent(), revision,
                base.replannedCheckpoint());

        var encoded = codec.encodeRequest(request);
        var decoded = codec.decodeRequest(
                encoded.formatVersion(), encoded.sha256(), encoded.json());
        PlanStep decodedStep = decoded.replannedRevision().steps().get(0);

        assertTrue(decodedStep.mayChangeCandidate());
        assertEquals("replacement complete",
                decodedStep.candidateValidationCompletionCondition());
        assertEquals(List.of("preserve unrelated behavior"),
                decodedStep.constraints());
        assertEquals(List.of("validate-candidate"),
                decodedStep.validationRequirementIds());
        assertEquals(2, encoded.formatVersion());
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                        value.getBytes(StandardCharsets.UTF_8)));
    }
}
