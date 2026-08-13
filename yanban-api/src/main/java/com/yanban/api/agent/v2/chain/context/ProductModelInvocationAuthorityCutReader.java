package com.yanban.api.agent.v2.chain.context;

import com.yanban.api.agent.v2.chain.persistence.ProductChainContextRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainFinalizationRepositoryAdapter;
import com.yanban.api.agent.v2.chain.persistence.ProductChainModelRepositoryAdapter;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainDeliveryStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainRole;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Exact repository reads and integrity checks for a model lineage cut. */
final class ProductModelInvocationAuthorityCutReader {
    private static final ChainContextModule MODULE =
            ChainContextModule.MODEL_INVOCATIONS_AND_PROPOSALS;
    private final ProductChainModelRepositoryAdapter models;
    private final ProductModelInvocationPrefixReader prefixes;
    private final ProductChainContextRepositoryAdapter contexts;
    private final ProductChainFinalizationRepositoryAdapter finalization;

    ProductModelInvocationAuthorityCutReader(
            ProductChainModelRepositoryAdapter models,
            ProductChainContextRepositoryAdapter contexts,
            ProductChainFinalizationRepositoryAdapter finalization) {
        this.models = models;
        this.prefixes = new ProductModelInvocationPrefixReader(models);
        this.contexts = contexts;
        this.finalization = finalization;
    }

    Cut read(ChainPersistenceRecords.ContextRevisionRecord building) {
        List<ChainPersistenceRecords.ContextRevisionRecord> lineage =
                lineage(building);
        List<ChainPersistenceRecords.ModelInvocationRecord> invocations =
                prefixes.exactInvocations(building.taskId(), lineage);
        List<ProductModelInvocationProjectionValues.InvocationView> views =
                new ArrayList<>();
        for (var invocation : invocations) {
            var context = lineage.stream().filter(value -> value
                    .contextRevisionId().equals(invocation.contextRevisionId()))
                    .findFirst().orElseThrow(() -> blocked(
                            "invocation Context is outside the lineage"));
            views.add(view(context, invocation));
        }
        ChainPersistenceRecords.TaskOutcomeRecord outcome = null;
        List<ProductModelInvocationProjectionValues.DeliveryView> deliveries =
                List.of();
        if (building.role() == ChainRole.ANSWER && !views.isEmpty()) {
            outcome = finalization.findTaskOutcome(building.taskId())
                    .orElse(null);
            if (outcome != null && !outcome.taskId().equals(building.taskId())) {
                throw blocked("TaskOutcome belongs to another task");
            }
            deliveries = deliveries(building.taskId());
        }
        return new Cut(lineage.stream().map(ChainPersistenceRecords
                .ContextRevisionRecord::contextRevisionId).toList(),
                views, outcome, deliveries);
    }

    private List<ChainPersistenceRecords.ContextRevisionRecord> lineage(
            ChainPersistenceRecords.ContextRevisionRecord building) {
        List<ChainPersistenceRecords.ContextRevisionRecord> result =
                new ArrayList<>();
        Set<String> seen = new HashSet<>(Set.of(
                building.contextRevisionId()));
        String current = building.parentContextRevisionId();
        while (current != null) {
            if (!seen.add(current)) throw blocked("Context lineage is cyclic");
            var ancestor = contexts.findContextRevision(current)
                    .orElseThrow(() -> blocked(
                            "Context lineage ancestor is missing"));
            if (!ancestor.taskId().equals(building.taskId())
                    || ancestor.status() != ChainContextRevisionStatus.COMPLETE
                    || ancestor.completionToken() == null) {
                throw blocked("Context ancestor is not a complete task cut");
            }
            result.add(ancestor);
            current = ancestor.parentContextRevisionId();
        }
        Collections.reverse(result);
        return List.copyOf(result);
    }

    private ProductModelInvocationProjectionValues.InvocationView view(
            ChainPersistenceRecords.ContextRevisionRecord context,
            ChainPersistenceRecords.ModelInvocationRecord invocation) {
        var attempts = models.findProviderAttempts(invocation.invocationId());
        for (int index = 0; index < attempts.size(); index++) {
            var attempt = attempts.get(index);
            if (!attempt.taskId().equals(invocation.taskId())
                    || !attempt.invocationId().equals(invocation.invocationId())
                    || attempt.attemptNo() != index + 1) {
                throw blocked("provider attempt prefix is inconsistent");
            }
        }
        var proposal = models.findProposalByInvocation(
                invocation.invocationId()).orElse(null);
        List<ChainPersistenceRecords.ProposalStateEventRecord> states =
                List.of();
        if (proposal != null) {
            if (!proposal.taskId().equals(invocation.taskId())
                    || !proposal.invocationId().equals(invocation.invocationId())
                    || proposal.role() != invocation.role()) {
                throw blocked("proposal identity mismatches its invocation");
            }
            verifyDigest(proposal.payload(), "proposal payload");
            verifyDigest(proposal.sourceRefs(), "proposal source refs");
            states = proposalStates(proposal);
        }
        return new ProductModelInvocationProjectionValues.InvocationView(
                context, invocation, attempts, proposal, states);
    }

    private List<ChainPersistenceRecords.ProposalStateEventRecord>
            proposalStates(ChainPersistenceRecords.ModelProposalRecord proposal) {
        var states = models.findProposalStateEvents(proposal.proposalId());
        List<ChainProposalState> prefix = new ArrayList<>();
        for (int index = 0; index < states.size(); index++) {
            var state = states.get(index);
            if (!state.taskId().equals(proposal.taskId())
                    || !state.proposalId().equals(proposal.proposalId())
                    || state.stateSequence() != index + 1L) {
                throw blocked("proposal state prefix is inconsistent");
            }
            try {
                state.validateNextFor(prefix);
                if (state.stateKind()
                        == ChainProposalState.REPLACED_BY_OFFICIAL_RESULT) {
                    ChainPersistenceRecords.ProposalOfficialAuthorityType
                            .valueOf(state.officialAuthorityType());
                }
            } catch (IllegalArgumentException invalid) {
                throw blocked("proposal state prefix is invalid");
            }
            prefix.add(state.stateKind());
        }
        return List.copyOf(states);
    }

    private List<ProductModelInvocationProjectionValues.DeliveryView>
            deliveries(String taskId) {
        List<ProductModelInvocationProjectionValues.DeliveryView> result =
                new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (var delivery : finalization.findDeliveries(taskId)) {
            if (!delivery.taskId().equals(taskId)
                    || !ids.add(delivery.deliveryId())) {
                throw blocked("delivery identity is inconsistent");
            }
            var events = finalization.findDeliveryEvents(delivery.deliveryId());
            boolean terminal = false;
            for (int index = 0; index < events.size(); index++) {
                var event = events.get(index);
                if (terminal || !event.taskId().equals(taskId)
                        || !event.deliveryId().equals(delivery.deliveryId())
                        || event.eventSequence() != index + 1L) {
                    throw blocked("delivery event prefix is inconsistent");
                }
                terminal = event.eventKind() == ChainDeliveryStatus.SUCCEEDED
                        || event.eventKind()
                        == ChainDeliveryStatus.DELIVERY_FAILED;
            }
            result.add(new ProductModelInvocationProjectionValues.DeliveryView(
                    delivery, events));
        }
        return List.copyOf(result);
    }

    private static void verifyDigest(
            ChainPersistenceRecords.CanonicalJson value, String name) {
        if (!ProductChainContractProjectionCodec.sha256(value.json())
                .equals(value.sha256())) {
            throw blocked(name + " digest is invalid");
        }
    }

    private static RuntimeException blocked(String reason) {
        return ProductChainContextProjectionSupport.blocked(MODULE, reason);
    }

    record Cut(
            List<String> lineage,
            List<ProductModelInvocationProjectionValues.InvocationView>
                    invocations,
            ChainPersistenceRecords.TaskOutcomeRecord outcome,
            List<ProductModelInvocationProjectionValues.DeliveryView>
                    deliveries) {
    }
}
