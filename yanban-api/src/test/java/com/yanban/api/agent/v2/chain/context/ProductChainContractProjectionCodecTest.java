package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.SecretRef;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.TaskRequirements;
import io.paperagent.v2.contracts.PublishRequirement;
import io.paperagent.v2.contracts.ValidationRequirement;
import io.paperagent.v2.contracts.ValidationSubject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductChainContractProjectionCodecTest {
    @Test
    void taskFrameSetLikeFieldsAndDigestAreCanonical() throws Exception {
        var first = ProductChainContractProjectionCodec.taskFrame(frame(
                List.of("host-b", "host-a"),
                Set.of(Capability.READ_PROJECT, Capability.ACCESS_NETWORK,
                        Capability.USE_SECRET_REFERENCE),
                Set.of(new SecretRef("secret/z"), new SecretRef("secret/a"))));
        var second = ProductChainContractProjectionCodec.taskFrame(frame(
                List.of("host-a", "host-b"),
                Set.of(Capability.ACCESS_NETWORK, Capability.READ_PROJECT,
                        Capability.USE_SECRET_REFERENCE),
                Set.of(new SecretRef("secret/a"), new SecretRef("secret/z"))));

        assertEquals(first.canonicalJson(), second.canonicalJson());
        assertEquals(first.sha256(), second.sha256());
        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(
                first.canonicalJson().getBytes(StandardCharsets.UTF_8))),
                first.sha256());
        assertTrue(first.canonicalJson().contains(
                "\"capabilities\":[\"ACCESS_NETWORK\",\"READ_PROJECT\","
                        + "\"USE_SECRET_REFERENCE\"]"));
        assertTrue(first.canonicalJson().contains(
                "\"deliveryRequirement\":\"FINAL_DELIVERY_REQUIRED\""));
        assertTrue(first.canonicalJson().contains(
                "\"requirementId\":\"validation.result\""));
        assertTrue(first.canonicalJson().contains(
                "\"publishRequirement\":\"NOT_REQUIRED\""));
        assertTrue(first.canonicalJson().contains(
                "\"schemaVersion\":\"product-chain-contract-v2\""));
    }

    @Test
    void planStepDependenciesSortWithoutReorderingCriteria() {
        PlanStep step = new PlanStep(
                new PlanStepId("step.main"),
                "Execute the bounded task.",
                "A verified result exists.",
                Set.of(new PlanStepId("step.z"), new PlanStepId("step.a")),
                List.of("first criterion", "second criterion"),
                new BoundedExecutionHints(3, Duration.ofSeconds(5, 7)),
                List.of("preserve unrelated content"),
                true,
                "second criterion",
                List.of("validation.result"));

        var projection = ProductChainContractProjectionCodec.planStep(step);

        assertTrue(projection.canonicalJson().contains(
                "\"dependencies\":[\"step.a\",\"step.z\"]"));
        assertTrue(projection.canonicalJson().contains(
                "\"completionCriteria\":[\"first criterion\",\"second criterion\"]"));
        assertTrue(projection.canonicalJson().contains(
                "\"maxDuration\":{\"nanos\":7,\"seconds\":5}"));
        assertTrue(projection.canonicalJson().contains(
                "\"validationRequirementIds\":[\"validation.result\"]"));
    }

    @Test
    void planRevisionHasStableCompletedFactStructureAndDigest() {
        PlanStep firstStep = step("step.a");
        PlanStep secondStep = step("step.z");
        CompletionFact firstFact = fact("step.a", "receipt.a");
        CompletionFact secondFact = fact("step.z", "receipt.z");
        LinkedHashMap<PlanStepId, CompletionFact> forward =
                new LinkedHashMap<>();
        forward.put(firstStep.id(), firstFact);
        forward.put(secondStep.id(), secondFact);
        LinkedHashMap<PlanStepId, CompletionFact> reverse =
                new LinkedHashMap<>();
        reverse.put(secondStep.id(), secondFact);
        reverse.put(firstStep.id(), firstFact);

        var first = ProductChainContractProjectionCodec.planRevision(
                revision(List.of(firstStep, secondStep), forward));
        var second = ProductChainContractProjectionCodec.planRevision(
                revision(List.of(firstStep, secondStep), reverse));

        assertEquals(first.canonicalJson(), second.canonicalJson());
        assertEquals(first.sha256(), second.sha256());
        assertTrue(first.canonicalJson().contains(
                "\"completedFacts\":{\"step.a\":"));
        assertTrue(first.canonicalJson().contains(
                "\"step.z\":{\"completedAt\":"));
        assertTrue(first.canonicalJson().contains(
                "\"receiptReferences\":[\"receipt.a\"]"));
    }

    private static PlanRevision revision(
            List<PlanStep> steps,
            Map<PlanStepId, CompletionFact> facts) {
        return new PlanRevision(
                new PlanRevisionId("revision-1"),
                new TaskFrameId("task.contract"),
                1,
                Optional.empty(),
                "Initial frozen plan.",
                Instant.parse("2026-08-08T00:00:00Z"),
                steps,
                facts);
    }

    private static PlanStep step(String id) {
        return new PlanStep(
                new PlanStepId(id),
                "Execute " + id,
                "Complete " + id,
                Set.of(),
                List.of("criterion " + id),
                new BoundedExecutionHints(2, Duration.ofSeconds(10)));
    }

    private static CompletionFact fact(String stepId, String receiptId) {
        return new CompletionFact(
                new PlanStepId(stepId),
                "outcome-" + stepId,
                Instant.parse("2026-08-08T00:01:00Z"),
                List.of(new ReceiptId(receiptId)));
    }

    private static TaskFrame frame(
            List<String> allowlist,
            Set<Capability> capabilities,
            Set<SecretRef> secrets) {
        return new TaskFrame(
                new TaskFrameId("task.contract"),
                "Use frozen authorities.",
                List.of("project"),
                List.of("verified result"),
                List.of("sandbox only"),
                TaskRequirements.explicit(List.of(new ValidationRequirement(
                                "validation.result", ValidationSubject.ACTION_RECEIPT,
                                "receipt proves completion")),
                        PublishRequirement.NOT_REQUIRED),
                Optional.of(new ProjectVersionRef("project-1", "version-1")),
                new ExecutionProfile(
                        ExecutionTier.SANDBOX_STANDARD,
                        Set.copyOf(capabilities),
                        NetworkPolicy.ALLOWLIST_ONLY,
                        allowlist,
                        new ResourceLimits(
                                Duration.ofMinutes(2), Duration.ofMinutes(1),
                                1024, 512, 2),
                        secrets),
                Instant.parse("2026-08-08T00:00:00Z"));
    }

    private static String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }
}
