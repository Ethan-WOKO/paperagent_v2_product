package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceMaterializationLimits;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PlanExecutionContextConfirmationRequest;
import io.paperagent.v2.persistence.PlanExecutionContextReservationRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;

import java.time.Instant;

final class ProductPlanExecutionContextTestFixtures {
    static final Instant NOW = ProductExecutionStartTestFixtures.NOW;
    static final ContentHash FINGERPRINT =
            new ContentHash("sha256", "a".repeat(64));

    private ProductPlanExecutionContextTestFixtures() {
    }

    static PersistedPlanBootstrap bootstrap(String plan, String task) {
        return ProductPlanBootstrapTestFixtures.project(plan, task);
    }

    static WorkspaceMaterializationSpec spec(String suffix) {
        return new WorkspaceMaterializationSpec(
                new WorkspaceId("workspace-" + suffix),
                new ProjectVersionRef("project-42", "version-7"),
                new WorkspaceMaterializationLimits(1024, 4096, 16));
    }

    static PlanExecutionContextReservationRequest reservation(
            PersistedPlanBootstrap bootstrap, String token, long fence,
            WorkspaceMaterializationSpec spec) {
        return new PlanExecutionContextReservationRequest(
                bootstrap.plan().id(), token, fence,
                bootstrap.plan().latestRevision().id(),
                bootstrap.plan().latestRevision().number(),
                2, 1, spec);
    }

    static PlanExecutionContextConfirmationRequest confirmation(
            PersistedPlanBootstrap bootstrap, String token, long fence,
            WorkspaceMaterializationSpec spec) {
        return new PlanExecutionContextConfirmationRequest(
                bootstrap.plan().id(), token, fence, spec, FINGERPRINT);
    }

    static void seedStarted(
            PersistedPlanBootstrap bootstrap,
            String owner, String token, long fence,
            ProductPlanBootstrapJpaRepository bootstraps,
            ProductPlanBootstrapCodec bootstrapCodec,
            ProductLeaseJpaRepository leases,
            ProductExecutionStartJpaRepository starts,
            ProductExecutionStartCodec startCodec) {
        var encodedBootstrap = bootstrapCodec.encode(bootstrap);
        bootstraps.saveAndFlush(new ProductPlanBootstrapEntity(
                bootstrap.plan().id().value(),
                bootstrap.taskFrame().id().value(),
                encodedBootstrap.formatVersion(),
                encodedBootstrap.sha256(),
                encodedBootstrap.json(),
                NOW.minusSeconds(2)));
        leases.saveAndFlush(new ProductLeaseEntity(
                bootstrap.plan().id().value(), fence, owner, token,
                NOW.minusSeconds(1), NOW.plusSeconds(60)));
        ExecutionStartRequest request =
                ProductExecutionStartTestFixtures.request(
                        bootstrap, token, fence,
                        "start-" + bootstrap.plan().id().value());
        PersistedExecutionStart result = new PersistedExecutionStart(
                bootstrap.plan().id(), owner, fence,
                request.startEvent(),
                new VersionedCheckpoint(2, request.startedCheckpoint()));
        starts.saveAndFlush(new ProductExecutionStartEntity(
                bootstrap.plan().id().value(),
                request.startEvent().id().value(),
                owner, fence,
                startCodec.encodeRequest(request),
                startCodec.encodeResult(result), NOW));
    }
}
