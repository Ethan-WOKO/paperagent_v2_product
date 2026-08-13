package io.paperagent.v2.chain.model;

import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainModelInvocationWriter;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainRuntimePolicy;
import io.paperagent.v2.chain.ModelProposal;
import io.paperagent.v2.chain.ProviderRoleOutput;
import io.paperagent.v2.chain.context.ChainContextManager;
import io.paperagent.v2.chain.context.ChainContextErrorCode;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainFrozenContext;

import java.util.List;
import java.util.Objects;

/**
 * Recoverable two-layer model protocol. It never persists or logs provider raw output.
 */
public final class ChainModelProtocolService {
    private static final System.Logger LOG = System.getLogger(
            ChainModelProtocolService.class.getName());
    private final ChainContextManager contexts;
    private final ChainModelRepository models;
    private final ChainModelInvocationWriter invocations;
    private final ChainModelMaterializationPort materialization;
    private final ChainModelCallPort provider;
    private final ChainRoleOutputDecoder decoder;

    public ChainModelProtocolService(
            ChainContextManager contexts,
            ChainModelRepository models,
            ChainModelInvocationWriter invocations,
            ChainModelMaterializationPort materialization,
            ChainModelCallPort provider,
            ChainRoleOutputDecoder decoder) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.models = Objects.requireNonNull(models, "models");
        this.invocations = Objects.requireNonNull(invocations, "invocations");
        this.materialization = Objects.requireNonNull(materialization, "materialization");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    public ChainModelProtocolOutcome invoke(ChainModelProtocolRequest request) {
        Objects.requireNonNull(request, "request");
        validateRoleWorkState(request.role(), request.workState());
        ChainFrozenContext frozen = requireCompleteContext(request);
        ChainPersistenceRecords.ContextRevisionRecord context = frozen.revision();
        ChainRuntimePolicy runtimePolicy = ChainRuntimePolicy.requireVersion(
                context.runtimePolicyVersion());
        ChainPersistenceRecords.ModelInvocationRecord invocation = invocation(request, context);
        appendOrVerifyInvocation(invocation);

        var existingProposal = models.findProposalByInvocation(request.invocationId());
        if (existingProposal.isPresent()) {
            ChainPersistenceRecords.ModelProposalRecord proposal = existingProposal.get();
            verifyProposalReplay(request, proposal);
            List<ChainPersistenceRecords.ProviderAttemptRecord> attempts =
                    models.findProviderAttempts(request.invocationId());
            verifyAttemptPrefix(request, attempts);
            ChainPersistenceRecords.ContentRecord body = proposal.bodyAuthorityRef() == null
                    ? null
                    : models.findContent(proposal.bodyAuthorityRef()).orElseThrow(() ->
                            new ChainModelProtocolException(
                                    ChainModelProtocolException.Code.CONTENT_REPLAY_MISMATCH,
                                     "proposal body content is missing"));
            verifyRecoveredProposal(request, proposal, body, attempts);
            return new ChainModelProtocolOutcome.ProposalReady(
                    proposal, body, attempts.size(), true);
        }

        List<ChainPersistenceRecords.ProviderAttemptRecord> priorAttempts =
                models.findProviderAttempts(request.invocationId());
        verifyAttemptPrefix(request, priorAttempts);
        if (priorAttempts.size() >= runtimePolicy.providerAttemptsTotal()) {
            return failedFrom(priorAttempts, request.invocationId());
        }

        String repairFeedback = null;
        String previousInvalidOutput = null;
        for (int attemptNo = priorAttempts.size() + 1;
             attemptNo <= runtimePolicy.providerAttemptsTotal();
             attemptNo++) {
            boolean repair = attemptNo > 1;
            ChainModelCallRequest call = new ChainModelCallRequest(
                    request.invocationId(), request.contextRevisionId(), request.completionToken(),
                    request.role(), request.workState(), request.callReason(),
                    request.provider(), request.model(), frozen.canonicalPrompt(),
                    attemptNo, repair, repair ? nonblankRepair(repairFeedback) : null,
                    repair ? previousInvalidOutput : null);
            ChainModelCallResult result = provider.call(call);
            if (result instanceof ChainModelCallResult.Failure failure) {
                appendFailedAttempt(request, attemptNo, failure.durationMs(), failure.finishReason(),
                        ChainPersistenceRecords.ValidationStatus.NOT_RUN,
                        ChainPersistenceRecords.ValidationStatus.NOT_RUN,
                        failure.errorCode());
                repairFeedback = "provider failure: " + failure.errorCode();
                previousInvalidOutput = null;
                continue;
            }

            ChainModelCallResult.Success success = (ChainModelCallResult.Success) result;
            ProviderRoleOutput output;
            try {
                output = decoder.decode(success.rawOutput(), request.role(),
                        request.workState(), request.boundGapId());
                output.validateFor(request.role(), request.workState(), request.boundGapId());
            } catch (RuntimeException protocolFailure) {
                appendFailedAttempt(request, attemptNo, success.durationMs(), success.finishReason(),
                        ChainPersistenceRecords.ValidationStatus.FAILED,
                        ChainPersistenceRecords.ValidationStatus.NOT_RUN,
                        "PROVIDER_SCHEMA_INVALID");
                // Keep diagnostics bounded and credential-safe: the parser exposes
                // only a protocol code and JSON path, never the provider response.
                LOG.log(System.Logger.Level.WARNING,
                        "chain provider schema rejected invocationId={0} attemptNo={1} failureType={2} diagnostic={3}",
                        new Object[]{request.invocationId(), attemptNo,
                                protocolFailure.getClass().getSimpleName(),
                                sanitizedRepair(protocolFailure)});
                repairFeedback = repairHint(request.role(), protocolFailure);
                previousInvalidOutput = success.rawOutput();
                continue;
            }

            try {
                return materialize(request, frozen, output, success, attemptNo);
            } catch (ChainModelProtocolException validationFailure) {
                appendFailedAttempt(request, attemptNo, success.durationMs(), success.finishReason(),
                        ChainPersistenceRecords.ValidationStatus.PASSED,
                        ChainPersistenceRecords.ValidationStatus.FAILED,
                        validationFailure.code().name());
                LOG.log(System.Logger.Level.WARNING,
                        "chain proposal validation rejected invocationId={0} attemptNo={1} code={2} diagnostic={3}",
                        new Object[]{request.invocationId(), attemptNo,
                                validationFailure.code(), sanitizedRepair(validationFailure)});
                repairFeedback = repairHint(request.role(), validationFailure,
                        frozen.visibleSourceRefs());
                previousInvalidOutput = success.rawOutput();
            }
        }
        return failedFrom(models.findProviderAttempts(request.invocationId()), request.invocationId());
    }

    private ChainModelProtocolOutcome materialize(
            ChainModelProtocolRequest request,
            ChainFrozenContext frozen,
            ProviderRoleOutput output,
            ChainModelCallResult.Success success,
            int attemptNo) {
        ChainModelCanonical.Body inlineBody = ChainModelCanonical.body(output.payload());
        String contentId = inlineBody == null ? null : "content." + ChainModelCanonical.sha256(
                request.taskId() + "\0" + request.invocationId() + "\0" + inlineBody.kind().name());
        ChainModelCanonical.MaterializedPayload materialized =
                ChainModelCanonical.materialize(output.payload(), contentId);
        for (String ref : materialized.sourceRefs()) {
            if (!frozen.visibleSourceRefs().contains(ref)) {
                throw new ChainModelProtocolException(
                        ChainModelProtocolException.Code.SOURCE_REF_NOT_VISIBLE,
                        "proposal source ref was not visible in the frozen ContextRevision: " + ref);
            }
        }

        String payloadHash = ChainModelCanonical.sha256(materialized.canonicalPayload());
        String proposalId = "proposal." + ChainModelCanonical.sha256(
                request.invocationId() + "\0" + output.kind() + "\0" + payloadHash);
        ChainIdentity.Proposal identity = new ChainIdentity.Proposal(
                proposalId, request.invocationId(), request.contextRevisionId(), request.role(),
                output.payload().kind(), payloadHash, materialized.sourceRefs(), contentId);
        new ModelProposal(ProviderRoleOutput.SCHEMA_VERSION, identity, request.workState(),
                materialized.sourceRefs(), materialized.bodyAuthorityType(), contentId,
                materialized.canonicalPayload());

        ChainPersistenceRecords.ContentRecord content = inlineBody == null ? null
                : new ChainPersistenceRecords.ContentRecord(
                        contentId, request.taskId(), request.invocationId(), inlineBody.kind(),
                        inlineBody.value(), ChainModelCanonical.sha256(inlineBody.value()),
                        inlineBody.mediaType(), request.createdAt());
        ChainPersistenceRecords.ModelProposalRecord proposal =
                new ChainPersistenceRecords.ModelProposalRecord(
                        proposalId, request.taskId(), request.invocationId(), 1, request.role(),
                        output.payload().kind(), canonical(materialized.canonicalPayload()),
                        canonical(ChainModelCanonical.json(materialized.sourceRefs())),
                        materialized.bodyAuthorityType(), contentId, request.createdAt());
        ChainPersistenceRecords.ProviderAttemptRecord attempt = attempt(
                request, attemptNo, success.durationMs(), success.finishReason(),
                ChainPersistenceRecords.ValidationStatus.PASSED,
                ChainPersistenceRecords.ValidationStatus.PASSED, null);
        ChainModelMaterializationPort.SuccessfulMaterialization stored =
                materialization.persistSuccessfulAttempt(attempt, content, proposal);
        verifyProposalReplay(request, stored.proposal());
        if (!sameProposal(stored.proposal(), proposal)
                || !sameAttempt(stored.attempt(), attempt)) {
            throw new ChainModelProtocolException(
                    ChainModelProtocolException.Code.PROPOSAL_REPLAY_MISMATCH,
                    "successful materialization changed the validated proposal or attempt");
        }
        if (content != null && (stored.bodyContent() == null
                || !sameContent(stored.bodyContent(), content))) {
            throw new ChainModelProtocolException(
                    ChainModelProtocolException.Code.CONTENT_REPLAY_MISMATCH,
                    "stored content does not match the materialized body");
        }
        return new ChainModelProtocolOutcome.ProposalReady(
                stored.proposal(), stored.bodyContent(), attemptNo, stored.replayed());
    }

    private ChainFrozenContext requireCompleteContext(
            ChainModelProtocolRequest request) {
        final ChainFrozenContext frozen;
        try {
            frozen = contexts.recover(request.taskId(), request.contextRevisionId());
        } catch (ChainContextException missingOrInvalid) {
            ChainModelProtocolException.Code code = switch (missingOrInvalid.code()) {
                case CONTEXT_REVISION_NOT_FOUND ->
                        ChainModelProtocolException.Code.CONTEXT_NOT_FOUND;
                case CONTEXT_REVISION_NOT_RECOVERABLE ->
                        ChainModelProtocolException.Code.CONTEXT_NOT_COMPLETE;
                case CONTEXT_REVISION_TASK_MISMATCH ->
                        ChainModelProtocolException.Code.CONTEXT_IDENTITY_MISMATCH;
                default -> ChainModelProtocolException.Code.CONTEXT_REQUEST_DIGEST_MISMATCH;
            };
            throw new ChainModelProtocolException(
                    code,
                    "ContextRevision cannot be recovered");
        }
        ChainPersistenceRecords.ContextRevisionRecord context = frozen.revision();
        if (context.status() != ChainContextRevisionStatus.COMPLETE) {
            throw new ChainModelProtocolException(
                    ChainModelProtocolException.Code.CONTEXT_NOT_COMPLETE,
                    "model invocation requires a COMPLETE ContextRevision");
        }
        if (!context.contextRevisionId().equals(request.contextRevisionId())
                || !context.taskId().equals(request.taskId())
                || context.role() != request.role()
                || context.workState() != request.workState()
                || !context.callReason().equals(request.callReason())
                || !context.completionToken().equals(request.completionToken())) {
            throw new ChainModelProtocolException(
                    ChainModelProtocolException.Code.CONTEXT_IDENTITY_MISMATCH,
                    "invocation identity does not match the frozen ContextRevision");
        }
        if (!ChainModelCanonical.sha256(frozen.canonicalPrompt())
                .equals(context.requestDigest())) {
            throw new ChainModelProtocolException(
                    ChainModelProtocolException.Code.CONTEXT_REQUEST_DIGEST_MISMATCH,
                    "frozen model request does not match the ContextRevision request digest");
        }
        return frozen;
    }

    private ChainPersistenceRecords.ModelInvocationRecord invocation(
            ChainModelProtocolRequest request,
            ChainPersistenceRecords.ContextRevisionRecord context) {
        return new ChainPersistenceRecords.ModelInvocationRecord(
                request.invocationId(), request.taskId(), request.contextRevisionId(),
                context.completionToken(), request.role(), request.workState(), request.callReason(),
                request.provider(), request.model(), request.invocationOrdinal(),
                context.runtimePolicyVersion(), request.createdAt());
    }

    private void appendOrVerifyInvocation(ChainPersistenceRecords.ModelInvocationRecord requested) {
        ChainPersistenceRecords.ModelInvocationRecord stored =
                invocations.appendInvocation(requested).value();
        if (!sameInvocation(stored, requested)) {
            throw new ChainModelProtocolException(
                    ChainModelProtocolException.Code.INVOCATION_REPLAY_MISMATCH,
                    "invocation replay changed its frozen identity");
        }
    }

    private void appendFailedAttempt(
            ChainModelProtocolRequest request, int attemptNo, long durationMs, String finishReason,
            ChainPersistenceRecords.ValidationStatus schema,
            ChainPersistenceRecords.ValidationStatus proposal,
            String errorCode) {
        ChainPersistenceRecords.ProviderAttemptRecord requested = attempt(
                request, attemptNo, durationMs, finishReason, schema, proposal, errorCode);
        ChainPersistenceRecords.ProviderAttemptRecord stored =
                invocations.appendProviderAttempt(requested).value();
        if (!sameAttempt(stored, requested)) {
            throw new ChainModelProtocolException(
                    ChainModelProtocolException.Code.ATTEMPT_PREFIX_INVALID,
                    "provider attempt replay changed its frozen result");
        }
    }

    private static ChainPersistenceRecords.ProviderAttemptRecord attempt(
            ChainModelProtocolRequest request, int attemptNo, long durationMs, String finishReason,
            ChainPersistenceRecords.ValidationStatus schema,
            ChainPersistenceRecords.ValidationStatus proposal,
            String errorCode) {
        return new ChainPersistenceRecords.ProviderAttemptRecord(
                request.invocationId(), attemptNo, request.taskId(), durationMs,
                finishReason, schema, proposal, errorCode, request.createdAt());
    }

    private static ChainPersistenceRecords.CanonicalJson canonical(String json) {
        return new ChainPersistenceRecords.CanonicalJson(
                1, ChainModelCanonical.sha256(json), json);
    }

    private static boolean sameInvocation(
            ChainPersistenceRecords.ModelInvocationRecord left,
            ChainPersistenceRecords.ModelInvocationRecord right) {
        return left.invocationId().equals(right.invocationId())
                && left.taskId().equals(right.taskId())
                && left.contextRevisionId().equals(right.contextRevisionId())
                && left.completionToken().equals(right.completionToken())
                && left.role() == right.role()
                && left.workState() == right.workState()
                && left.callReason().equals(right.callReason())
                && left.provider().equals(right.provider())
                && left.model().equals(right.model())
                && left.invocationOrdinal() == right.invocationOrdinal()
                && left.runtimePolicyVersion().equals(right.runtimePolicyVersion());
    }

    private static void verifyProposalReplay(
            ChainModelProtocolRequest request,
            ChainPersistenceRecords.ModelProposalRecord proposal) {
        if (!proposal.taskId().equals(request.taskId())
                || !proposal.invocationId().equals(request.invocationId())
                || proposal.role() != request.role()
                || proposal.proposalKind().role() != request.role()) {
            throw new ChainModelProtocolException(
                    ChainModelProtocolException.Code.PROPOSAL_REPLAY_MISMATCH,
                    "proposal replay changed invocation, task, or role identity");
        }
    }

    private static void verifyRecoveredProposal(
            ChainModelProtocolRequest request,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ChainPersistenceRecords.ContentRecord body,
            List<ChainPersistenceRecords.ProviderAttemptRecord> attempts) {
        if (attempts.isEmpty()) {
            throw new ChainModelProtocolException(
                    ChainModelProtocolException.Code.PROPOSAL_REPLAY_MISMATCH,
                    "a recovered proposal requires its validated provider attempt");
        }
        ChainPersistenceRecords.ProviderAttemptRecord winner =
                attempts.get(attempts.size() - 1);
        if (winner.schemaValidationStatus()
                != ChainPersistenceRecords.ValidationStatus.PASSED
                || winner.proposalValidationStatus()
                != ChainPersistenceRecords.ValidationStatus.PASSED
                || winner.errorCode() != null) {
            throw new ChainModelProtocolException(
                    ChainModelProtocolException.Code.PROPOSAL_REPLAY_MISMATCH,
                    "a recovered proposal is not bound to a successful validated attempt");
        }
        if (!ChainModelCanonical.sha256(proposal.payload().json())
                        .equals(proposal.payload().sha256())
                || !ChainModelCanonical.sha256(proposal.sourceRefs().json())
                        .equals(proposal.sourceRefs().sha256())) {
            throw new ChainModelProtocolException(
                    ChainModelProtocolException.Code.PROPOSAL_REPLAY_MISMATCH,
                    "recovered proposal canonical data digest mismatch");
        }
        String expectedProposalId = "proposal." + ChainModelCanonical.sha256(
                request.invocationId() + "\0" + proposal.proposalKind().wireName()
                        + "\0" + proposal.payload().sha256());
        if (!expectedProposalId.equals(proposal.proposalId())) {
            throw new ChainModelProtocolException(
                    ChainModelProtocolException.Code.PROPOSAL_REPLAY_MISMATCH,
                    "recovered proposal identity does not match its validated payload");
        }
        if (body == null) {
            if (proposal.bodyAuthorityType() != null
                    || proposal.bodyAuthorityRef() != null) {
                throw new ChainModelProtocolException(
                        ChainModelProtocolException.Code.CONTENT_REPLAY_MISMATCH,
                        "recovered proposal body authority is incomplete");
            }
            return;
        }
        String expectedContentId = "content." + ChainModelCanonical.sha256(
                request.taskId() + "\0" + request.invocationId() + "\0"
                        + body.contentKind().name());
        if (!body.taskId().equals(request.taskId())
                || !body.invocationId().equals(request.invocationId())
                || !body.contentId().equals(expectedContentId)
                || !body.contentId().equals(proposal.bodyAuthorityRef())
                || !body.contentKind().name().equals(proposal.bodyAuthorityType())
                || !ChainModelCanonical.sha256(body.body()).equals(body.bodySha256())) {
            throw new ChainModelProtocolException(
                    ChainModelProtocolException.Code.CONTENT_REPLAY_MISMATCH,
                    "recovered proposal body does not match its authority identity");
        }
    }

    private static boolean sameProposal(
            ChainPersistenceRecords.ModelProposalRecord left,
            ChainPersistenceRecords.ModelProposalRecord right) {
        return left.proposalId().equals(right.proposalId())
                && left.taskId().equals(right.taskId())
                && left.invocationId().equals(right.invocationId())
                && left.schemaVersion() == right.schemaVersion()
                && left.role() == right.role()
                && left.proposalKind() == right.proposalKind()
                && left.payload().equals(right.payload())
                && left.sourceRefs().equals(right.sourceRefs())
                && Objects.equals(left.bodyAuthorityType(), right.bodyAuthorityType())
                && Objects.equals(left.bodyAuthorityRef(), right.bodyAuthorityRef());
    }

    private static boolean sameAttempt(
            ChainPersistenceRecords.ProviderAttemptRecord left,
            ChainPersistenceRecords.ProviderAttemptRecord right) {
        return left.invocationId().equals(right.invocationId())
                && left.attemptNo() == right.attemptNo()
                && left.taskId().equals(right.taskId())
                && left.durationMs() == right.durationMs()
                && left.finishReason().equals(right.finishReason())
                && left.schemaValidationStatus() == right.schemaValidationStatus()
                && left.proposalValidationStatus() == right.proposalValidationStatus()
                && Objects.equals(left.errorCode(), right.errorCode());
    }

    private static boolean sameContent(
            ChainPersistenceRecords.ContentRecord left,
            ChainPersistenceRecords.ContentRecord right) {
        return left.contentId().equals(right.contentId())
                && left.taskId().equals(right.taskId())
                && left.invocationId().equals(right.invocationId())
                && left.contentKind() == right.contentKind()
                && left.body().equals(right.body())
                && left.bodySha256().equals(right.bodySha256())
                && left.mediaType().equals(right.mediaType());
    }

    private static ChainModelProtocolOutcome.ModelCallFailed failedFrom(
            List<ChainPersistenceRecords.ProviderAttemptRecord> attempts,
            String invocationId) {
        if (attempts.isEmpty()) {
            throw new IllegalStateException("MODEL_CALL_FAILED requires at least one recorded attempt");
        }
        ChainPersistenceRecords.ProviderAttemptRecord last = attempts.get(attempts.size() - 1);
        return new ChainModelProtocolOutcome.ModelCallFailed(
                invocationId,
                last.errorCode() == null ? "MODEL_CALL_FAILED" : last.errorCode(),
                attempts.size());
    }

    private static void verifyAttemptPrefix(
            ChainModelProtocolRequest request,
            List<ChainPersistenceRecords.ProviderAttemptRecord> attempts) {
        for (int index = 0; index < attempts.size(); index++) {
            ChainPersistenceRecords.ProviderAttemptRecord attempt = attempts.get(index);
            if (!attempt.invocationId().equals(request.invocationId())
                    || !attempt.taskId().equals(request.taskId())
                    || attempt.attemptNo() != index + 1) {
                throw new ChainModelProtocolException(
                        ChainModelProtocolException.Code.ATTEMPT_PREFIX_INVALID,
                        "provider attempt history is not one contiguous invocation prefix");
            }
        }
    }

    private static String sanitizedRepair(RuntimeException failure) {
        if (failure instanceof ChainModelAuthorityBindingRepairException safe) {
            return safe.safeFeedback();
        }
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return "provider schema validation failed";
        return message.length() <= 256 ? message : message.substring(0, 256);
    }

    private static String repairHint(
            io.paperagent.v2.chain.ChainRole role,
            RuntimeException failure) {
        return repairHint(role, failure, java.util.Set.of());
    }

    private static String repairHint(
            io.paperagent.v2.chain.ChainRole role,
            RuntimeException failure,
            java.util.Set<String> visibleSourceRefs) {
        String hint = sanitizedRepair(failure);
        if (hint.startsWith("INVALID_JSON at ")) {
            String syntaxDetail = hint.contains("before EOF")
                    ? " Close every opened string, array, and object before stopping; the final non-whitespace "
                            + "character of the complete root response must be '}'."
                    : hint.contains("duplicate object key")
                    ? " Every JSON object key must occur exactly once in its containing object; remove duplicate "
                            + "members instead of repeating or merging them as adjacent keys."
                    : "";
            return hint + ";" + syntaxDetail
                    + " Replace the entire response with one syntactically valid JSON object. "
                    + "Its root has exactly three members separated by literal ASCII commas, in this shape: "
                    + "{\"schemaVersion\":\"1\",\"kind\":\"<allowed-kind>\",\"payload\":{}}. "
                    + "Replace <allowed-kind> and the empty payload with one complete role-allowed kind and its "
                    + "complete typed payload from the frozen schema; do not copy angle-bracket placeholders, "
                    + "omit a comma, add markdown, or return an abbreviated object";
        }
        if (role == io.paperagent.v2.chain.ChainRole.EXECUTOR
                && hint.contains("self-repair fields must be all present or all absent")) {
            return hint + "; for TOOL_ACTION, priorErrorRef, priorActionRef, changeFromPriorAction, "
                    + "and expectedProgress are one all-or-none group. A possible future failure stated in the "
                    + "instruction is not a prior failed action. Unless the frozen Context contains both an exact "
                    + "prior error authority and its exact prior action authority, replace the four members with "
                    + "\"priorErrorRef\":null,\"priorActionRef\":null,\"changeFromPriorAction\":null,"
                    + "\"expectedProgress\":null. expectedOutputs describes the current action's expected "
                    + "outputs; expectedProgress is not a general current-action field and describes only "
                    + "progress relative to the exact prior failed action. If both authorities exist, provide all four "
                    + "as nonblank strings and copy priorErrorRef and priorActionRef from exact visible "
                    + "authority identifiers";
        }
        if (role == io.paperagent.v2.chain.ChainRole.EXECUTOR
                && hint.contains("inlineCanonicalChangeBody")) {
            return hint + "; retry WORKSPACE_CHANGE.inlineCanonicalChangeBody as canonical one-line JSON "
                    + "with exact root {\"changes\":[...]}; every item has type, path, and "
                    + "expectedBaselineSha256; ADD uses baseline NONE and requires text; MODIFY uses a "
                    + "64-character lowercase SHA-256 baseline and requires text; DELETE uses a "
                    + "64-character lowercase SHA-256 baseline and forbids text; reject all extra fields; "
                    + "paths must be nonblank and unique after case folding; changes[].path must exactly "
                    + "equal targetFiles in the same order; escape every quote, backslash, and newline in text";
        }
        if (role == io.paperagent.v2.chain.ChainRole.PLANNER
                && hint.contains("PERSISTENT_ROUTE_WITHOUT_REQUIREMENT")) {
            return hint + "; the PERSISTENT_PLAN semantic kind is rejected. Reassess routing only from "
                    + "the effective user request. Context merely containing a Project, ProjectVersion, "
                    + "tool catalog, capability, or permission does not create a requirement. If needsTool, "
                    + "needsNetwork, needsProject, and needsPersistentProgress are all false, replace the "
                    + "complete response with DIRECT_ROUTE. Use PERSISTENT_PLAN only when at least one "
                    + "routingBoundary flag is genuinely required by the requested work";
        }
        if (role == io.paperagent.v2.chain.ChainRole.PLANNER
                && hint.contains("objects must not be empty")) {
            return hint + "; PERSISTENT_PLAN requires at least one exact visible authority target in "
                    + "taskFrameDraft.objects. Do not invent a target or put prose or a Project path there. "
                    + "If the effective user request has no persistent target and all routing boundary flags "
                    + "are false, the PERSISTENT_PLAN semantic kind is rejected and the complete response "
                    + "must be replaced with DIRECT_ROUTE";
        }
        if (role == io.paperagent.v2.chain.ChainRole.PLANNER
                && hint.contains("candidate validation completion condition")) {
            return hint + "; repair only the linked Candidate-validation fields in the previous JSON. "
                    + "Locate the one validationRequirements item whose subject is exactly CANDIDATE. "
                    + "Find the Step that binds its requirementId. That Step must be a later validation Step with "
                    + "mayChangeCandidate=false that depends on the last Candidate-changing Step. Never bind the "
                    + "requirement to a Candidate-changing Step. Copy the "
                    + "completionCondition byte-for-byte into that binding Step: the exact string must be an element of the Step's "
                    + "completionConditions array and must also be the complete value of "
                    + "candidateValidationCompletionCondition. Keep that requirementId bound in the same "
                    + "binding Step's validationRequirementIds. Do not translate, paraphrase, shorten, or independently "
                    + "rewrite any of the three copies, and preserve all unrelated fields from the previous JSON";
        }
        if (role == io.paperagent.v2.chain.ChainRole.PLANNER
                && (hint.contains("TaskFrame requirements")
                || hint.contains("taskFrame.requirements")
                || hint.contains("validationRequirementIds")
                || hint.contains("validation requirement")
                || hint.contains("candidate validation completion condition"))) {
            return hint + "; emit the complete typed planning contract: taskFrame.requirements.declarationMode "
                    + "must be EXPLICIT, deliveryRequirement must be FINAL_DELIVERY_REQUIRED, and publishRequirement "
                    + "must be exactly REQUIRED or NOT_REQUIRED. Each validationRequirements item must contain a stable "
                    + "requirementId, subject (CANDIDATE or ACTION_RECEIPT), and completionCondition. Copy every declared "
                    + "requirementId exactly once into one plan.steps[].validationRequirementIds; use [] when no validation "
                    + "is required. If any Step has mayChangeCandidate=true, explicitly declare exactly one CANDIDATE "
                    + "validation requirement and bind it to a later non-changing validation Step that depends "
                    + "directly or transitively on the last Candidate-changing Step. The Candidate-changing Step "
                    + "must not bind that requirement because it creates no validation Receipt. This is one plan-level aggregate check "
                    + "of the final Candidate, not one CANDIDATE item per Step, deliverable, target, or requested property. "
                    + "Preserve Step-specific checks as ordinary completionConditions; use ACTION_RECEIPT only when the "
                    + "check is proven by an executed action receipt. Count only items whose subject is the exact "
                    + "string CANDIDATE: the count must be 1; ACTION_RECEIPT does not satisfy this rule. If the diagnostic "
                    + "reports count=0, add one CANDIDATE item. If it reports a count above 1, replace those duplicate "
                    + "Candidate items with one aggregate CANDIDATE item whose completionCondition preserves all of their "
                    + "required final-Candidate checks; remove the duplicate IDs from validationRequirementIds without "
                    + "deleting the affected Steps or their ordinary completionConditions. Retype an item as ACTION_RECEIPT "
                    + "only if its authority is actually an executed action receipt. Copy the aggregate requirement's "
                    + "completionCondition "
                    + "byte-for-byte into both that Step's completionConditions array and "
                    + "candidateValidationCompletionCondition. Linked abstract example: "
                    + "taskFrame.requirements.validationRequirements=[{\"requirementId\":\"validation-A\","
                    + "\"subject\":\"CANDIDATE\",\"completionCondition\":\"condition-A\"}], and a later "
                    + "validation Step depends on the last Candidate-changing Step and has "
                    + "validationRequirementIds=[\"validation-A\"], "
                    + "completionConditions=[\"condition-A\"], and "
                    + "candidateValidationCompletionCondition=\"condition-A\". While mayChangeCandidate=true, "
                    + "do not remove that requirement, its Step binding, or either exact condition copy to silence "
                    + "a validation error. When no Candidate validation "
                    + "condition applies, use JSON null, never an empty string; do not paraphrase either occurrence. "
                    + "PermissionTier and free-form constraints "
                    + "must never substitute for explicit validation or publication requirements";
        }
        if (role == io.paperagent.v2.chain.ChainRole.PLANNER
                && hint.contains("coverage cannot carry fact refs")) {
            return hint + "; requirementCoverage entries with status PLANNED or UNSATISFIED must use "
                    + "factRefs: []; only SATISFIED may carry factRefs, and SATISFIED requires one or more "
                    + "exact visible authority identifiers";
        }
        if (role == io.paperagent.v2.chain.ChainRole.PLANNER
                && hint.contains("gap validation and bound gap are only legal")) {
            return hint + "; this invocation is not validating a bound PendingItem, so emit "
                    + "payload.gapValidation as JSON null and do not invent a gapId or validation checks";
        }
        if (role == io.paperagent.v2.chain.ChainRole.PLANNER
                && hint.contains("knownFactRefs must not be empty")) {
            return hint + "; PLANNING_BLOCKED is legal only for a genuine authority-backed blocker and requires "
                    + "one or more exact visible knownFactRefs. A schema-repair retry must not switch a viable "
                    + "plan into PLANNING_BLOCKED merely to avoid correcting invalid plan fields; preserve the "
                    + "intended semantic proposal kind unless visible authority facts require a different route";
        }
        if (role == io.paperagent.v2.chain.ChainRole.REFLECTOR
                && hint.contains("validationAssessment must bind the frozen TaskFrame requirements")) {
            return hint + "; in the combined step-accept-and-ready-to-finalize form, this assessment binds the "
                    + "requirement declaration, not a Validation, Receipt, Candidate, or evidence ref. Emit "
                    + "finalization.validationAssessment with status BOUND, authorityRef copied exactly from "
                    + "payload.acceptance.taskFrameRef, and reason as JSON null. Preserve unrelated fields";
        }
        if (role == io.paperagent.v2.chain.ChainRole.REFLECTOR
                && hint.contains("publishRequirementAssessment must bind the exact TaskFrame")) {
            return hint + "; in the combined step-accept-and-ready-to-finalize form, this assessment binds the "
                    + "publish requirement declaration, not a publish result, Receipt, Candidate, or evidence ref. "
                    + "Emit finalization.publishRequirementAssessment with status BOUND, authorityRef copied "
                    + "exactly from payload.acceptance.taskFrameRef, and reason as JSON null. Preserve unrelated fields";
        }
        if (role == io.paperagent.v2.chain.ChainRole.REFLECTOR
                && (hint.contains("AssessmentStatus")
                || hint.contains("finalArtifactAssessment")
                || hint.contains("finalCandidateAssessment")
                || hint.contains("validationAssessment")
                || hint.contains("publishRequirementAssessment")
                || hint.contains("BOUND assessment")
                || hint.contains("non-binding reason"))) {
            return hint + "; authority assessment rule: status must be exactly BOUND, NOT_REQUIRED, MISSING, or UNSATISFIED; "
                    + "BOUND requires nonblank authorityRef and JSON-null reason; every other status requires JSON-null authorityRef "
                    + "and a nonblank reason. Valid abstract examples: "
                    + "{\"status\":\"BOUND\",\"authorityRef\":\"exact-visible-ref\",\"reason\":null} and "
                    + "{\"status\":\"MISSING\",\"authorityRef\":null,\"reason\":\"nonblank reason\"}. "
                    + "An empty string is not JSON null";
        }
        if (role == io.paperagent.v2.chain.ChainRole.REFLECTOR
                && hint.contains("combined final-step review")
                && hint.contains("common review payload")) {
            return hint + "; for the combined step-accept-and-ready-to-finalize form, payload.review "
                    + "and payload.acceptance.review must be byte-for-byte identical JSON objects. "
                    + "First create one complete ReviewCommon object, then copy that exact object into both "
                    + "locations without changing any scalar, array element, or array order";
        }
        if (role == io.paperagent.v2.chain.ChainRole.EXECUTOR
                && hint.contains("validationSources requirementIds must exactly match active Step")) {
            return hint + "; copy every ID from the frozen active Step validationRequirementIds exactly once "
                    + "into validationSources[].requirementId and include no other ID. Bind each one to the "
                    + "visible formal Receipt that proves it, and include that same receiptRef in receiptRefs. "
                    + "Use validationSources: [] only when the active Step list is empty";
        }
        if (role == io.paperagent.v2.chain.ChainRole.REFLECTOR
                && hint.contains("proposal source ref was not visible")) {
            String allowList = visibleSourceRefs.stream().sorted()
                    .map(value -> "\"" + value + "\"")
                    .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
            return hint + "; the rejected value is prose or a step description, not an authority ref. "
                    + "Remove it from every *Refs field (reviewedObjectRefs, directFactRefs, factRefs, authorityRef, "
                    + "candidateRef, stepRef, planRevisionRef, taskFrameRef) and copy only exact identifiers from the "
                    + "frozen ContextRevision visible source refs; keep descriptions in reviewScope or decisionReason. "
                    + "For this retry, the exact visible-source-ref allow-list is " + allowList
                    + "; no other string may appear in a *Refs field or authorityRef";
        }
        if (role == io.paperagent.v2.chain.ChainRole.EXECUTOR
                && hint.contains("proposal source ref was not visible")) {
            String allowList = visibleSourceRefs.stream().sorted()
                    .map(value -> "\"" + value + "\"")
                    .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
            return hint + "; for a tool action, copy toolId from descriptor.id and requiredPermission "
                    + "from permissionRef of the same visible completeToolSchemas entry. Capability names and "
                    + "public aliases are not authority references. Every other authority-reference field must "
                    + "also use only an exact identifier from this frozen visible-source-ref allow-list "
                    + allowList + "; do not invent or paraphrase a reference";
        }
        if (role == io.paperagent.v2.chain.ChainRole.PLANNER
                && hint.contains("proposal source ref was not visible")) {
            String allowList = visibleSourceRefs.stream().sorted()
                    .map(value -> "\"" + value + "\"")
                    .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
            return hint + "; remove project paths, descriptions, requirements, and prose from every "
                    + "*Refs field and from TaskFrame objects, which is also an authority-reference collection. "
                    + "Put descriptive values in the applicable Step scopes, constraints, deliverables, or "
                    + "completion conditions. An authority-reference field may contain only exact "
                    + "identifiers from this frozen visible-source-ref allow-list " + allowList
                    + "; if no visible authority identifier is required, use []";
        }
        if (role == io.paperagent.v2.chain.ChainRole.ANSWER
                && hint.contains("proposal source ref was not visible")) {
            String allowList = visibleSourceRefs.stream().sorted()
                    .map(value -> "\"" + value + "\"")
                    .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
            return hint + "; every Answer reference field must copy an exact identifier from "
                    + "the frozen ContextRevision visible-source-ref allow-list " + allowList
                    + "; do not invent, paraphrase, or substitute any reference";
        }
        if (role == io.paperagent.v2.chain.ChainRole.ANSWER
                && hint.contains("final Answer refs must exactly copy")) {
            return hint + "; re-read rules.answerSchema in the same frozen ContextRevision, "
                    + "copy its complete FINAL_DELIVERY JSON structure and every reference value "
                    + "exactly, and replace only payload.inlineAnswerBody";
        }
        if (role == io.paperagent.v2.chain.ChainRole.ANSWER
                && hint.contains("status Answer refs must exactly copy")) {
            return hint + "; re-read runtime.answerPayloadTemplate in the same frozen "
                    + "ContextRevision, copy its complete STATUS_OR_FAILURE root JSON object and "
                    + "every reference value exactly, and replace only payload.inlineAnswerBody";
        }
        if (role == io.paperagent.v2.chain.ChainRole.ANSWER
                && hint.contains("Direct Answer must copy")) {
            return hint + "; re-read runtime.answerPayloadTemplate in the same frozen "
                    + "ContextRevision, copy its complete DIRECT_ANSWER root JSON object and "
                    + "every directTaskSpecification and factRefs value exactly, and replace "
                    + "only payload.inlineAnswerBody";
        }
        return hint;
    }

    private static String nonblankRepair(String feedback) {
        return feedback == null || feedback.isBlank() ? "previous attempt was invalid" : feedback;
    }

    static void validateRoleWorkState(
            io.paperagent.v2.chain.ChainRole role,
            io.paperagent.v2.chain.ChainWorkState state) {
        boolean allowed = switch (role) {
            case PLANNER -> state == io.paperagent.v2.chain.ChainWorkState.PLANNING
                    || state == io.paperagent.v2.chain.ChainWorkState.CLASSIFYING_INSTRUCTION
                    || state == io.paperagent.v2.chain.ChainWorkState.VALIDATING_PENDING_ITEM;
            case EXECUTOR -> state == io.paperagent.v2.chain.ChainWorkState.EXECUTING
                    || state == io.paperagent.v2.chain.ChainWorkState.VALIDATING_PENDING_ITEM;
            case REFLECTOR -> state == io.paperagent.v2.chain.ChainWorkState.AWAITING_REVIEW
                    || state == io.paperagent.v2.chain.ChainWorkState.FINALIZING;
            case ANSWER -> state == io.paperagent.v2.chain.ChainWorkState.DIRECT_ANSWERING
                    || state == io.paperagent.v2.chain.ChainWorkState.WAITING_USER
                    || state == io.paperagent.v2.chain.ChainWorkState.WAITING_PERMISSION
                    || state == io.paperagent.v2.chain.ChainWorkState.DELIVERING
                    || state == io.paperagent.v2.chain.ChainWorkState.TERMINAL;
        };
        if (!allowed) {
            throw new IllegalArgumentException(
                    "role " + role + " cannot be invoked from work state " + state);
        }
    }
}
