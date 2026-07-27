package com.yanban.agent.v2.adapter.bootstrap;

import com.yanban.agent.v2.adapter.taskframe.DeterministicProductTaskFrameAdapter;
import com.yanban.agent.v2.adapter.taskframe.ProductTaskFrameIntakeRequest;
import com.yanban.agent.v2.adapter.taskframe.ProductTaskFramePreparation;
import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure product-to-V2 bootstrap request adaptation without ambient state.
 */
public final class ProductPersistentPlanBootstrapRequestAdapter {
    private static final String PLAN_PREFIX = "product-plan.";
    private static final String REVISION_PREFIX = "product-revision.";
    private static final String PLAN_DOMAIN = "plan\0";
    private static final String REVISION_DOMAIN = "revision-1\0";

    private final DeterministicProductTaskFrameAdapter taskFrames;

    public ProductPersistentPlanBootstrapRequestAdapter() {
        this(new DeterministicProductTaskFrameAdapter());
    }

    ProductPersistentPlanBootstrapRequestAdapter(
            DeterministicProductTaskFrameAdapter taskFrames) {
        this.taskFrames = Objects.requireNonNull(taskFrames, "taskFrames");
    }

    public PersistentPlanBootstrapRequest adapt(
            AgentRunIdentity identity,
            Optional<String> projectVersionId,
            ProductPersistentPlanBootstrapCommand command) {
        Objects.requireNonNull(command, "command");
        ProductTaskFramePreparation preparation = taskFrames.prepare(
                new ProductTaskFrameIntakeRequest(
                        identity,
                        projectVersionId,
                        command.routingDecision(),
                        command.taskFrameDraft(),
                        command.executionProfile(),
                        command.taskFrameCreatedAt()));
        String runId = preparation.identity().runId();
        return new PersistentPlanBootstrapRequest(
                preparation.freezeRequest(),
                new PlanId(PLAN_PREFIX + sha256(PLAN_DOMAIN + runId)),
                new PlanRevisionId(
                        REVISION_PREFIX + sha256(REVISION_DOMAIN + runId)),
                command.initialPlanDraft(),
                command.planCreatedAt(),
                command.checkpointCreatedAt());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK must provide SHA-256", exception);
        }
    }
}
