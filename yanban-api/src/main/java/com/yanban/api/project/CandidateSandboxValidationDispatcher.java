package com.yanban.api.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.sandbox.SandboxBrokerClient;
import com.yanban.api.agent.sandbox.SandboxExecutionException;
import com.yanban.api.agent.sandbox.SandboxFailureCode;
import com.yanban.api.agent.sandbox.SandboxExecutionProperties;
import com.yanban.sandbox.contract.SandboxDispatch;
import com.yanban.sandbox.contract.SandboxDispatchResponse;
import com.yanban.sandbox.contract.SandboxExecutionStatus;
import com.yanban.sandbox.contract.SandboxExecutionView;
import com.yanban.sandbox.contract.SandboxReceipt;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Restart-safe Candidate validation dispatch/poll/cancel loop. It never projects Plan or Evidence state. */
@Component
@ConditionalOnProperty(prefix = "yanban.sandbox", name = "enabled", havingValue = "true")
class CandidateSandboxValidationDispatcher {
    private final CandidateSandboxValidationRepository validations;
    private final SandboxBrokerClient broker;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final CandidateValidationAnalysisProjectionService analysis;
    private final SandboxExecutionProperties sandboxProperties;
    private final CandidateValidationRepairService repairs;

    CandidateSandboxValidationDispatcher(CandidateSandboxValidationRepository validations,
                                         SandboxBrokerClient broker, ObjectMapper json, JdbcTemplate jdbc,
                                         TransactionTemplate transactions,
                                         CandidateValidationAnalysisProjectionService analysis,
                                         SandboxExecutionProperties sandboxProperties,
                                         CandidateValidationRepairService repairs) {
        this.validations = validations; this.broker = broker; this.json = json; this.jdbc = jdbc;
        this.transactions = transactions; this.analysis = analysis; this.sandboxProperties = sandboxProperties;
        this.repairs = repairs;
    }

    @Scheduled(fixedDelayString = "${yanban.sandbox.dispatch-delay-ms:1000}")
    void reconcile() {
        for (CandidateSandboxValidation candidate : validations.findReconcileable()) reconcile(candidate.validationId());
    }

    void reconcile(String validationId) {
        String token = transactions.execute(status -> {
            CandidateSandboxValidation value = validations.lockByValidationId(validationId).orElse(null);
            LocalDateTime now = dbNow();
            if (value == null || !value.claimable(now)) return null;
            String claimed = value.claim(now); validations.saveAndFlush(value); return claimed;
        });
        if (token == null) return;
        try { execute(validationId, token); }
        catch (SandboxExecutionException exception) {
            if (exception.code() == SandboxFailureCode.SANDBOX_UNAVAILABLE) {
                retry(validationId, token, exception.code().name());
            } else {
                fail(validationId, token, exception.code().name());
            }
        }
        catch (RuntimeException exception) {
            fail(validationId, token, SandboxFailureCode.PROVIDER_REJECTED.name());
        }
    }

    private void execute(String validationId, String token) {
        CandidateSandboxValidation snapshot = validations.findByValidationId(validationId).orElseThrow();
        if ("CANCEL_REQUESTED".equals(snapshot.status())) {
            if (snapshot.brokerExecutionId() == null) {
                commit(validationId, token, value -> value.cancelledBeforeDispatch(dbNow()));
            } else {
                broker.cancel(snapshot.brokerExecutionId(), 1L);
                projectView(snapshot, broker.status(snapshot.brokerExecutionId()), validationId, token);
            }
            return;
        }
        if (snapshot.brokerExecutionId() == null) {
            SandboxDispatch request = readDispatch(snapshot.requestJson());
            SandboxDispatchResponse dispatched = broker.dispatch(request);
            if (!snapshot.requestDigest().equals(dispatched.requestDigest()) || dispatched.fence() != 1L) conflict();
            commit(validationId, token, value -> value.dispatched(dispatched.executionId(), dispatched.status().name(), dbNow()));
            return;
        }
        projectView(snapshot, broker.status(snapshot.brokerExecutionId()), validationId, token);
    }

    private void projectView(CandidateSandboxValidation snapshot, SandboxExecutionView view,
                             String validationId, String token) {
        if (!snapshot.requestDigest().equals(view.requestDigest()) || view.fence() != 1L
                || !snapshot.brokerExecutionId().equals(view.executionId())) conflict();
        if (!terminal(view.status())) {
            commit(validationId, token, value -> value.polled(view.status().name(), dbNow()));
            return;
        }
        if (view.receipt() == null && view.status() != SandboxExecutionStatus.CLEANUP_FAILED) conflict();
        SandboxReceipt receipt = view.receipt();
        if (receipt != null) validateReceipt(snapshot, receipt, view.status());
        String receiptJson = receipt == null ? null : write(receipt);
        String digest = receiptJson == null ? null : CandidateSandboxValidationService.sha256(receiptJson);
        commit(validationId, token, value -> value.complete(view.status().name(), digest, receiptJson,
                view.errorCode() == null ? null : view.errorCode().name(), dbNow()));
        if (receipt != null) analysis.analyzeAfterCommit(validationId);
        if (view.status() == SandboxExecutionStatus.FAILED && receipt != null) {
            repairs.repairAfterFailure(validationId);
        }
    }

    private void validateReceipt(CandidateSandboxValidation value, SandboxReceipt receipt,
                                 SandboxExecutionStatus status) {
        SandboxDispatch request = readDispatch(value.requestJson());
        if (!value.brokerExecutionId().equals(receipt.executionId())
                || !request.idempotencyKey().equals(receipt.idempotencyKey())
                || !value.requestDigest().equals(receipt.requestDigest()) || receipt.fence() != 1L
                || value.userId() != receipt.userId() || value.projectId() != receipt.projectId()
                || value.sessionId() != receipt.sessionId() || value.artifactId() != receipt.planId()
                || request.stepId() != receipt.stepId() || !value.projectVersion().equals(receipt.projectVersion())
                || !value.policyDigest().equals(receipt.policyDigest())
                || !sandboxProperties.getProvider().equals(receipt.provider())
                || receipt.status() != status || receipt.startedAt() == null || receipt.finishedAt() == null
                || receipt.finishedAt().isBefore(receipt.startedAt()) || receipt.stdout() == null || receipt.stderr() == null
                || (long) receipt.stdout().getBytes(StandardCharsets.UTF_8).length
                    + receipt.stderr().getBytes(StandardCharsets.UTF_8).length > 20L * 1024 * 1024
                || (status == SandboxExecutionStatus.SUCCEEDED
                    && (receipt.exitCode() == null || receipt.exitCode() != 0 || receipt.errorCode() != null))
                || (status == SandboxExecutionStatus.FAILED && receipt.errorCode() == null
                    && (receipt.exitCode() == null || receipt.exitCode() == 0))
                || (!Set.of(SandboxExecutionStatus.SUCCEEDED, SandboxExecutionStatus.FAILED).contains(status)
                    && receipt.errorCode() == null)) conflict();
    }

    private void commit(String validationId, String token, java.util.function.Consumer<CandidateSandboxValidation> change) {
        transactions.executeWithoutResult(status -> {
            CandidateSandboxValidation value = validations.lockByValidationId(validationId).orElseThrow();
            if (!value.owns(token, dbNow())) throw new IllegalStateException("stale Candidate validation claim");
            change.accept(value); validations.saveAndFlush(value);
        });
    }
    private void retry(String validationId, String token, String code) {
        try { commit(validationId, token, value -> value.retry(code, dbNow())); }
        catch (RuntimeException ignored) { /* A newer owner or terminal projection won the race. */ }
    }
    private void fail(String validationId, String token, String code) {
        try { commit(validationId, token, value -> value.fail(code, dbNow())); }
        catch (RuntimeException ignored) { /* A newer owner or terminal projection won the race. */ }
    }
    private boolean terminal(SandboxExecutionStatus status) {
        return Set.of(SandboxExecutionStatus.SUCCEEDED, SandboxExecutionStatus.FAILED,
                SandboxExecutionStatus.CANCELLED, SandboxExecutionStatus.TIMED_OUT,
                SandboxExecutionStatus.CLEANUP_FAILED).contains(status);
    }
    private SandboxDispatch readDispatch(String value) {
        try { return json.readValue(value, SandboxDispatch.class); }
        catch (Exception exception) { throw new IllegalStateException("Stored Candidate validation request is invalid", exception); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private LocalDateTime dbNow() { return jdbc.queryForObject("select current_timestamp", LocalDateTime.class); }
    private void conflict() { throw new SandboxExecutionException(SandboxFailureCode.RECEIPT_CONFLICT,
            "Candidate validation Broker receipt identity mismatch"); }
}
