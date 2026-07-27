package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PlanExecutionContextConfirmationRequest;
import io.paperagent.v2.persistence.PlanExecutionContextRepository;
import io.paperagent.v2.persistence.PlanExecutionContextReservationRequest;
import io.paperagent.v2.persistence.PlanExecutionContextSnapshot;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextConfirmed;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextReserved;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
public final class ProductPlanExecutionContextRepositoryAdapter
        implements PlanExecutionContextRepository {
    private final ProductPlanExecutionContextTransactions transactions;

    public ProductPlanExecutionContextRepositoryAdapter(
            ProductPlanExecutionContextTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public PersistenceResult<PersistedPlanExecutionContextReserved> reserve(
            PlanExecutionContextReservationRequest request) {
        if (request == null) {
            return invalid("request");
        }
        try {
            return transactions.reserve(request);
        } catch (RuntimeException exception) {
            if (!constraintViolation(exception)) {
                throw exception;
            }
            return switch (transactions.workspaceOwnerStatus(
                    request.materializationSpec().workspaceId().value())) {
                case CANONICAL -> PersistenceResult.rejected(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.materializationSpec.workspaceId");
                case PARTIAL -> PersistenceResult.rejected(
                        PersistenceErrorCode
                                .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                        "planExecutionContext");
                case ABSENT -> throw exception;
            };
        }
    }

    @Override
    public PersistenceResult<PersistedPlanExecutionContextConfirmed> confirm(
            PlanExecutionContextConfirmationRequest request) {
        return request == null
                ? invalid("request")
                : transactions.confirm(request);
    }

    @Override
    public PersistenceResult<PlanExecutionContextSnapshot> inspect(
            PlanId planId) {
        return planId == null
                ? invalid("planId")
                : transactions.inspect(planId);
    }

    private static boolean constraintViolation(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof DataIntegrityViolationException
                    || current instanceof ConstraintViolationException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static <T> PersistenceResult<T> invalid(String path) {
        return PersistenceResult.rejected(
                PersistenceErrorCode.INVALID_ARGUMENT, path);
    }
}
