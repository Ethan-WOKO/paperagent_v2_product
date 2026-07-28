package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.EffectResultRequest;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ProductEffectExecutionClaimTransactions {
    private final ProductPlanBootstrapJpaRepository bootstraps;
    private final ProductStepActivationJpaRepository activations;
    private final ProductStepActivationCodec activationCodec;
    private final ProductStepCompletionJpaRepository completions;
    private final ProductLeaseJpaRepository leases;
    private final ProductEffectExecutionClaimJpaRepository claims;
    private final ProductEffectOutcomeResultJpaRepository results;
    private final ProductEffectOutcomeMarkerReader markers;
    private final ProductReceiptJpaRepository receipts;
    private final ProductReceiptCodec receiptCodec;
    private final ProductEffectOutcomeCodec outcomeCodec;
    private final EntityManager entityManager;

    ProductEffectExecutionClaimTransactions(
            ProductPlanBootstrapJpaRepository bootstraps,
            ProductStepActivationJpaRepository activations,
            ProductStepActivationCodec activationCodec,
            ProductStepCompletionJpaRepository completions,
            ProductLeaseJpaRepository leases,
            ProductEffectExecutionClaimJpaRepository claims,
            ProductEffectOutcomeResultJpaRepository results,
            ProductEffectOutcomeMarkerReader markers,
            ProductReceiptJpaRepository receipts,
            ProductReceiptCodec receiptCodec,
            ProductEffectOutcomeCodec outcomeCodec,
            EntityManager entityManager) {
        this.bootstraps = bootstraps;
        this.activations = activations;
        this.activationCodec = activationCodec;
        this.completions = completions;
        this.leases = leases;
        this.claims = claims;
        this.results = results;
        this.markers = markers;
        this.receipts = receipts;
        this.receiptCodec = receiptCodec;
        this.outcomeCodec = outcomeCodec;
        this.entityManager = entityManager;
    }

    @Transactional
    ProductEffectExecutionClaimResult execute(
            ProductEffectExecutionClaimRequest request) {
        String planId = request.intent().intent().planId().value();
        if (bootstraps.lockByPlanId(planId).isEmpty()) {
            throw failed("authority.plan");
        }
        ProductLeaseEntity authoritativeLease = validateAuthority(request);

        ProductEffectOutcomeResultEntity resultRow =
                results.findById(request.intent().intent().toolCallId().value())
                        .orElse(null);
        ProductEffectExecutionClaimEntity claim = claims.findById(
                request.intent().intent().toolCallId().value()).orElse(null);
        if (resultRow != null) {
            var marker = markers.result(resultRow);
            if (claim == null || marker == null
                    || !sameClaim(claim, request)) {
                throw failed("result.corrupt");
            }
            return new ProductEffectExecutionClaimResult(
                    marker.result(), true);
        }
        if (claim != null) {
            throw failed("claim.incomplete");
        }

        var intent = request.intent().intent();
        entityManager.persist(new ProductEffectExecutionClaimEntity(
                intent.toolCallId().value(), intent.planId().value(),
                intent.stepId().value(),
                request.intent().activationEventId().value(),
                request.observedAt()));
        entityManager.flush();

        ExecutionReceipt receipt = request.execution().get();
        if (receipt == null
                || !receipt.toolCallId().equals(intent.toolCallId())) {
            throw failed("execution.receipt");
        }
        entityManager.refresh(authoritativeLease);
        validateLease(
                authoritativeLease, request, receipt.endedAt(),
                "authority.leaseAfterExecution");
        EffectResultRequest effectRequest = new EffectResultRequest(
                receipt, request.leaseToken(), request.fencingToken());
        PersistedEffectResult persisted = new PersistedEffectResult(
                receipt, request.lease().ownerId(),
                request.lease().fencingToken());
        if (receipts.existsById(receipt.id().value())) {
            throw failed("execution.receiptId");
        }
        entityManager.persist(new ProductReceiptEntity(
                receipt.id().value(), receipt.toolCallId().value(),
                ProductReceiptOwnership.EFFECT_INTENT, "EFFECT_OUTCOME",
                receiptCodec.encode(receipt), request.observedAt()));
        entityManager.flush();
        entityManager.persist(new ProductEffectOutcomeResultEntity(
                receipt.toolCallId().value(), receipt.id().value(),
                request.lease().ownerId(), request.lease().fencingToken(),
                outcomeCodec.encodeResultRequest(effectRequest),
                outcomeCodec.encodeResultResult(persisted),
                request.observedAt()));
        entityManager.flush();
        return new ProductEffectExecutionClaimResult(persisted, false);
    }

    private ProductLeaseEntity validateAuthority(
            ProductEffectExecutionClaimRequest request) {
        PersistedEffectIntent durable = markers.intent(
                request.intent().intent().toolCallId().value());
        var recovery = request.recovery();
        var intent = request.intent();
        if (durable == null || !durable.equals(intent)
                || !recovery.planId().equals(intent.intent().planId())
                || !recovery.activation().stepId().equals(
                        intent.intent().stepId())
                || !recovery.activation().activationEvent().id().equals(
                        intent.activationEventId())
                || recovery.checkpoint().checkpoint().stepStates().get(
                        intent.intent().stepId()) != StepExecutionState.ACTIVE
                || completions
                        .findByPlanIdAndStepIdAndActivationEventId(
                                intent.intent().planId().value(),
                                intent.intent().stepId().value(),
                                intent.activationEventId().value())
                        .isPresent()) {
            throw failed("authority.activeStep");
        }
        ProductStepActivationEntity activation = activations.findById(
                intent.activationEventId().value()).orElse(null);
        if (activation == null
                || !activationCodec.decodeResult(
                        activation.resultFormatVersion(),
                        activation.resultSha256(),
                        activation.resultJson())
                        .equals(recovery.activation())) {
            throw failed("authority.activation");
        }
        ProductLeaseEntity lease = leases
                .findFirstByPlanIdOrderByFencingTokenDesc(
                        intent.intent().planId().value()).orElse(null);
        validateLease(lease, request, request.observedAt(), "authority.lease");
        return lease;
    }

    private static void validateLease(
            ProductLeaseEntity lease,
            ProductEffectExecutionClaimRequest request,
            java.time.Instant effectiveAt,
            String path) {
        if (lease == null || effectiveAt == null
                || lease.releasedAt() != null
                || !lease.planId().equals(request.lease().planId().value())
                || !lease.leaseToken().equals(request.leaseToken())
                || !lease.leaseToken().equals(request.lease().leaseToken())
                || !lease.ownerId().equals(request.lease().ownerId())
                || lease.fencingToken() != request.fencingToken()
                || lease.fencingToken() != request.lease().fencingToken()
                || !lease.expiresAt().equals(request.lease().expiresAt())
                || !effectiveAt.isBefore(lease.expiresAt())) {
            throw failed(path);
        }
    }

    private static boolean sameClaim(
            ProductEffectExecutionClaimEntity claim,
            ProductEffectExecutionClaimRequest request) {
        var intent = request.intent();
        return claim.toolCallId().equals(intent.intent().toolCallId().value())
                && claim.planId().equals(intent.intent().planId().value())
                && claim.stepId().equals(intent.intent().stepId().value())
                && claim.activationEventId().equals(
                        intent.activationEventId().value())
                && claim.claimedAt() != null;
    }

    private static ProductEffectExecutionClaimException failed(String path) {
        return new ProductEffectExecutionClaimException(path);
    }
}
