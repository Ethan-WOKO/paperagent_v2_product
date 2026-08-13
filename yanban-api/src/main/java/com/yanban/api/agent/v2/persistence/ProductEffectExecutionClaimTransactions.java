package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.EffectResultRequest;
import io.paperagent.v2.persistence.PersistedEffectIntent;
import io.paperagent.v2.persistence.PersistedEffectResult;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class ProductEffectExecutionClaimTransactions {
    private final ProductPlanBootstrapJpaRepository bootstraps;
    private final ProductStepActivationJpaRepository activations;
    private final ProductStepActivationCodec activationCodec;
    private final ProductStepInterruptionJpaRepository interruptions;
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
            ProductStepInterruptionJpaRepository interruptions,
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
        this.interruptions = interruptions;
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ProductEffectExecutionClaimResult execute(
            ProductEffectExecutionClaimRequest request) {
        ProductLeaseEntity authoritativeLease = lockAndValidate(request);
        var replay = replay(request);
        if (replay.isPresent()) return replay.orElseThrow();
        claim(request, false);
        ExecutionReceipt receipt = request.execution().get();
        return persistResult(request, authoritativeLease, receipt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Optional<ProductEffectExecutionClaimResult> claimOrReplay(
            ProductEffectExecutionClaimRequest request) {
        lockAndValidate(request);
        var replay = replay(request);
        if (replay.isPresent()) return replay;
        claim(request, true);
        return Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ProductEffectExecutionClaimResult complete(
            ProductEffectExecutionClaimRequest request,
            ExecutionReceipt receipt) {
        ProductLeaseEntity authoritativeLease = lockAndValidate(request);
        var replay = replay(request);
        if (replay.isPresent()) return replay.orElseThrow();
        ProductEffectExecutionClaimEntity claim = claims.findById(
                request.intent().intent().toolCallId().value()).orElse(null);
        if (claim == null || !sameClaim(claim, request)) {
            throw failed("claim.missingOrChanged");
        }
        return persistResult(request, authoritativeLease, receipt);
    }

    private ProductLeaseEntity lockAndValidate(
            ProductEffectExecutionClaimRequest request) {
        String planId = request.intent().intent().planId().value();
        if (bootstraps.lockByPlanId(planId).isEmpty()) {
            throw failed("authority.plan");
        }
        return validateAuthority(request);
    }

    private Optional<ProductEffectExecutionClaimResult> replay(
            ProductEffectExecutionClaimRequest request) {
        ProductEffectOutcomeResultEntity resultRow = results.findById(
                request.intent().intent().toolCallId().value()).orElse(null);
        if (resultRow == null) return Optional.empty();
        ProductEffectExecutionClaimEntity claim = claims.findById(
                request.intent().intent().toolCallId().value()).orElse(null);
        var marker = markers.result(resultRow);
        if (claim == null || marker == null || !sameClaim(claim, request)) {
            throw failed("result.corrupt");
        }
        return Optional.of(new ProductEffectExecutionClaimResult(
                marker.result(), true));
    }

    private void claim(
            ProductEffectExecutionClaimRequest request,
            boolean allowExactExistingClaim) {
        ProductEffectExecutionClaimEntity claim = claims.findById(
                request.intent().intent().toolCallId().value()).orElse(null);
        if (claim != null) {
            if (allowExactExistingClaim && sameClaim(claim, request)) return;
            throw failed("claim.incomplete");
        }
        var intent = request.intent().intent();
        entityManager.persist(new ProductEffectExecutionClaimEntity(
                intent.toolCallId().value(), intent.planId().value(),
                intent.stepId().value(),
                request.intent().activationEventId().value(),
                request.observedAt()));
        entityManager.flush();
    }

    private ProductEffectExecutionClaimResult persistResult(
            ProductEffectExecutionClaimRequest request,
            ProductLeaseEntity authoritativeLease,
            ExecutionReceipt receipt) {
        var intent = request.intent().intent();
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
        boolean interrupted = !interruptions.findAllByPlanId(
                intent.intent().planId().value()).isEmpty();
        boolean activeStep = recovery.checkpoint().checkpoint().stepStates().get(
                intent.intent().stepId()) == StepExecutionState.ACTIVE;
        boolean completed = completions
                .findByPlanIdAndStepIdAndActivationEventId(
                        intent.intent().planId().value(),
                        intent.intent().stepId().value(),
                        intent.activationEventId().value())
                .isPresent();
        boolean invalid = durable == null || !durable.equals(intent)
                || interrupted
                || !recovery.planId().equals(intent.intent().planId())
                || !recovery.activation().stepId().equals(
                        intent.intent().stepId())
                || !recovery.activation().activationEvent().id().equals(
                        intent.activationEventId())
                || !activeStep || completed;
        if (invalid) {
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
        if (lease == null) throw failed(path + ".missing");
        if (effectiveAt == null) throw failed(path + ".effectiveAtMissing");
        if (lease.releasedAt() != null) throw failed(path + ".released");
        if (!lease.planId().equals(request.lease().planId().value()))
            throw failed(path + ".planMismatch");
        if (!lease.leaseToken().equals(request.leaseToken())
                || !lease.leaseToken().equals(
                        request.lease().leaseToken()))
            throw failed(path + ".tokenMismatch");
        if (!lease.ownerId().equals(request.lease().ownerId()))
            throw failed(path + ".ownerMismatch");
        if (lease.fencingToken() != request.fencingToken()
                || lease.fencingToken()
                        != request.lease().fencingToken())
            throw failed(path + ".fenceMismatch");
        if (!lease.expiresAt().equals(request.lease().expiresAt()))
            throw failed(path + ".expiresAtMismatch");
        if (!effectiveAt.isBefore(lease.expiresAt())) {
            long timingDeltaMillis = java.time.Duration.between(
                    lease.expiresAt(), effectiveAt).toMillis();
            throw new ProductEffectExecutionClaimException(
                    path + ".expired", timingDeltaMillis);
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
