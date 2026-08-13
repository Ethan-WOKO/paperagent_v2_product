package com.yanban.api.agent.v2.chain.api;

import com.yanban.api.agent.CandidateChangeArtifactService;
import com.yanban.api.agent.v2.intake.V2NaturalLanguageTurnRequest;
import com.yanban.api.agent.v2.intake.V2NaturalLanguageTurnResponse;
import com.yanban.api.agent.v2.chain.observability.ProjectChainSafeLogger;
import com.yanban.api.agent.v2.chain.finalization.ProductChainCompletedOutcomeAdapter;
import com.yanban.api.agent.v2.chain.context.ProductChainSkillSnapshotService;
import com.yanban.api.agent.v2.chain.context.ProductValidationPublishContextProjector;
import com.yanban.api.agent.v2.persistence.ProductChainStepAuthorityAdapter;
import com.yanban.api.agent.v2.chain.recovery.ProductChainReceivedCommandSource;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import com.yanban.core.agent.AgentTurn;
import com.yanban.core.agent.AgentTurnRepository;
import io.paperagent.v2.chain.ChainCommandStatus;
import io.paperagent.v2.chain.ChainCommandWriter;
import io.paperagent.v2.chain.ChainDeliveryStatus;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainIdentity;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainInstructionWriter;
import io.paperagent.v2.chain.ChainModelRepository;
import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainPendingItemWriter;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainTaskOutcomeWriter;
import io.paperagent.v2.chain.ChainTaskWriter;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.instruction.ChainCancellationRuntime;
import io.paperagent.v2.chain.instruction.ChainInstructionRuntime;
import io.paperagent.v2.chain.instruction.ChainInstructionStateReader;
import io.paperagent.v2.chain.instruction.ChainTaskOutcomeCommandPort;
import io.paperagent.v2.chain.review.ChainTaskOutcomeRuntime;
import io.paperagent.v2.chain.state.ChainPendingItemRuntime;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistedStepRecoveryReady;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import io.paperagent.v2.persistence.PersistedStepRecoverySucceeded;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authenticated Project command boundary and formal-fact query projection.
 * It never reads the deleted intake/adaptive tables and never dispatches the
 * Workspace Chat service.
 */
@Service
public class ProjectChainTurnCoordinator implements ProjectChainTurnApi {
    private static final String EMPTY_ARRAY = "[]";

    private final AgentSessionRepository sessions;
    private final AgentMessageRepository messages;
    private final AgentTurnRepository turns;
    private final ProjectService projects;
    private final ProjectChainSessionCommandLock sessionCommandLock;
    private final ProjectChainSafeLogger safeLogger;
    private final ChainFoundationRepository foundations;
    private final ChainCommandWriter commandWriter;
    private final ChainTaskWriter taskWriter;
    private final ChainInstructionWriter instructionWriter;
    private final ChainWorkflowRepository workflow;
    private final ChainPendingItemWriter pendingWriter;
    private final ChainFinalizationRepository finalization;
    private final ProductChainCompletedOutcomeAdapter outcomeAuthority;
    private final ChainModelRepository models;
    private final ProductChainStepAuthorityAdapter stepAuthorities;
    private final StepRecoveryRepository stepRecovery;
    private final CandidateChangeArtifactService candidateArtifacts;
    private final ProductValidationPublishContextProjector terminalValidations;
    private final NamedParameterJdbcTemplate jdbc;
    private final ProjectChainPlannerProgression plannerProgression;
    private final ProductChainSkillSnapshotService skillSnapshots;
    private final ProjectChainTurnEntryTransactions entryTransactions;

    public ProjectChainTurnCoordinator(
            AgentSessionRepository sessions,
            AgentMessageRepository messages,
            AgentTurnRepository turns,
            ProjectService projects,
            ProjectChainSessionCommandLock sessionCommandLock,
            ProjectChainSafeLogger safeLogger,
            ChainFoundationRepository foundations,
            ChainCommandWriter commandWriter,
            ChainTaskWriter taskWriter,
            ChainInstructionWriter instructionWriter,
            ChainWorkflowRepository workflow,
            ChainPendingItemWriter pendingWriter,
            ChainFinalizationRepository finalization,
            ProductChainCompletedOutcomeAdapter outcomeAuthority,
            ChainModelRepository models,
            ProductChainStepAuthorityAdapter stepAuthorities,
            StepRecoveryRepository stepRecovery,
            CandidateChangeArtifactService candidateArtifacts,
            ProductValidationPublishContextProjector terminalValidations,
            NamedParameterJdbcTemplate jdbc,
            ProjectChainPlannerProgression plannerProgression,
            ProductChainSkillSnapshotService skillSnapshots,
            ProjectChainTurnEntryTransactions entryTransactions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.sessionCommandLock = Objects.requireNonNull(
                sessionCommandLock, "sessionCommandLock");
        this.safeLogger = Objects.requireNonNull(safeLogger, "safeLogger");
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.commandWriter = Objects.requireNonNull(commandWriter, "commandWriter");
        this.taskWriter = Objects.requireNonNull(taskWriter, "taskWriter");
        this.instructionWriter = Objects.requireNonNull(instructionWriter, "instructionWriter");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.pendingWriter = Objects.requireNonNull(pendingWriter, "pendingWriter");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
        this.outcomeAuthority = Objects.requireNonNull(outcomeAuthority,
                "outcomeAuthority");
        this.models = Objects.requireNonNull(models, "models");
        this.stepAuthorities = Objects.requireNonNull(
                stepAuthorities, "stepAuthorities");
        this.stepRecovery = Objects.requireNonNull(stepRecovery, "stepRecovery");
        this.candidateArtifacts = Objects.requireNonNull(candidateArtifacts, "candidateArtifacts");
        this.terminalValidations = Objects.requireNonNull(
                terminalValidations, "terminalValidations");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.plannerProgression = Objects.requireNonNull(plannerProgression, "plannerProgression");
        this.skillSnapshots = Objects.requireNonNull(
                skillSnapshots, "skillSnapshots");
        this.entryTransactions = Objects.requireNonNull(
                entryTransactions, "entryTransactions");
    }

    @Override
    public V2NaturalLanguageTurnResponse start(
            long userId, long sessionId,
            V2NaturalLanguageTurnRequest request) {
        Objects.requireNonNull(request, "request");
        ChainInstructionRelation kind = instructionKind(request.instructionKind());
        String targetClientRequestId = normalized(request.targetClientRequestId());
        if (kind == ChainInstructionRelation.INITIAL) {
            if (targetClientRequestId != null) {
                throw badRequest("CHAIN_TARGET_NOT_ALLOWED");
            }
        } else if (kind != ChainInstructionRelation.SUPPLEMENT
                && kind != ChainInstructionRelation.CORRECTION
                && kind != ChainInstructionRelation.REPLACEMENT) {
            throw badRequest("CHAIN_INSTRUCTION_KIND_INVALID");
        } else if (targetClientRequestId == null) {
            throw badRequest("CHAIN_TARGET_REQUIRED");
        }

        StartBegin begin = entryTransactions.inBeginWrite(() -> beginStart(
                userId, sessionId, request, kind, targetClientRequestId));
        if (begin.command().status() == ChainCommandStatus.COMMITTED) {
            return entryTransactions.inPublicCutWrite(() ->
                    startResponse(begin.command(), begin.replayed()));
        }
        if (begin.cut() == null) {
            throw new IllegalStateException(
                    "CHAIN_COMMAND_RECEIVED_AWAITS_PROGRESSION");
        }

        // A legacy RECEIVED Task boundary may still be recovered here. New
        // INITIAL/REPLACEMENT boundaries are committed atomically by
        // beginStart, before this method can return or a client can poll.
        if (kind == ChainInstructionRelation.INITIAL
                || kind == ChainInstructionRelation.REPLACEMENT) {
            return publishIntakeCut(begin);
        }

        return advanceFirstPlanner(begin, kind, kind,
                request.content(), false);
    }

    /**
     * Continues one already persisted {@code RECEIVED} command through its
     * first Planner cut.  The caller must supply the independently verified
     * received-command authority; this method rereads the mutable product
     * facts before it invokes a model and uses the identical formal commit
     * path as normal API intake.
     *
     * <p>This is deliberately a narrow recovery seam.  It neither schedules
     * work nor advances an active Step.</p>
     */
    public V2NaturalLanguageTurnResponse resumeReceivedPlanner(
            ProductChainReceivedCommandSource.ReceivedCommand received) {
        Objects.requireNonNull(received, "received");
        StartBegin begin = entryTransactions.inBeginWrite(() ->
                readReceivedPlannerStart(received));
        if (received.commandKind() == ChainInstructionRelation.INITIAL
                || received.commandKind()
                == ChainInstructionRelation.REPLACEMENT) {
            return publishIntakeCut(begin);
        }
        boolean alreadyBoundaryReplacement = received.commandKind()
                == ChainInstructionRelation.SUPPLEMENT
                || received.commandKind()
                == ChainInstructionRelation.CORRECTION
                ? received.progressionRelation()
                == ChainInstructionRelation.INITIAL
                && !received.taskId().equals(received.targetTaskId())
                : false;
        return advanceFirstPlanner(begin, received.commandKind(),
                received.progressionRelation(), begin.message().getContent(),
                alreadyBoundaryReplacement);
    }

    private V2NaturalLanguageTurnResponse publishIntakeCut(StartBegin begin) {
        if (begin.cut() == null) {
            throw new IllegalStateException(
                    "received command has no intake authority cut");
        }
        return entryTransactions.inPublicCutWrite(() -> {
            ChainPersistenceRecords.CommandRecord committed = commandWriter
                    .commitCommand(begin.command().commandId(),
                            begin.cut().taskId(), begin.cut().eventId(),
                            begin.cut().instructionId());
            logCommitted(committed);
            return startResponse(committed, begin.replayed());
        });
    }

    private V2NaturalLanguageTurnResponse advanceFirstPlanner(
            StartBegin begin, ChainInstructionRelation commandKind,
            ChainInstructionRelation progressionRelation, String body,
            boolean alreadyBoundaryReplacement) {
        AgentSession session = begin.session();
        ChainPersistenceRecords.TaskRecord target = begin.target();
        ChainPersistenceRecords.CommandRecord registered = begin.command();
        AgentMessage message = begin.message();
        AgentTurn turn = begin.turn();
        Instant now = begin.createdAt();
        CommandCut cut = begin.cut();
        ChainPersistenceRecords.TaskRecord progressionTask = foundations.findTask(cut.taskId())
                .orElseThrow(() -> new IllegalStateException("created task is not readable"));
        ChainPersistenceRecords.InstructionRecord progressionInstruction = foundations
                .findInstruction(cut.instructionId())
                .orElseThrow(() -> new IllegalStateException("created instruction is not readable"));
        ProjectChainPlannerProgression.ProgressionResult progression =
                plannerProgression.advance(
                        session, progressionTask, progressionInstruction,
                        body, plannerRelation(progressionRelation),
                        now);
        if (progression.boundaryChanged()) {
            if (alreadyBoundaryReplacement) {
                throw new IllegalStateException(
                        "replacement intake cannot emit another boundary disposition");
            }
            if (commandKind != ChainInstructionRelation.SUPPLEMENT
                    && commandKind != ChainInstructionRelation.CORRECTION) {
                throw new IllegalStateException(
                        "only a supplement or correction may replace a task after disposition");
            }
            ChainPersistenceRecords.InstructionRecord triggerInstruction =
                    progressionInstruction;
            String dispositionEventId = progression.formalEventId();
            cut = entryTransactions.inBeginWrite(() -> {
                CommandCut replacement = createDispositionReplacement(
                        session, target, registered, message, turn,
                        triggerInstruction, dispositionEventId, now);
                skillSnapshots.copyForBoundaryReplacement(
                        replacement.taskId(), replacement.instructionId(),
                        target.taskId(), now);
                return replacement;
            });
            progressionTask = foundations.findTask(cut.taskId())
                    .orElseThrow(() -> new IllegalStateException(
                            "replacement task is not readable"));
            progressionInstruction = foundations.findInstruction(
                            cut.instructionId())
                    .orElseThrow(() -> new IllegalStateException(
                            "replacement intake instruction is not readable"));
            progression = plannerProgression.advance(
                    session, progressionTask, progressionInstruction,
                    body, plannerRelation(ChainInstructionRelation.INITIAL),
                    now);
            if (progression.boundaryChanged()) {
                throw new IllegalStateException(
                        "replacement intake cannot emit another boundary disposition");
            }
        }
        CommandCut publicCut = cut;
        ProjectChainPlannerProgression.ProgressionResult publicProgression =
                progression;
        return entryTransactions.inPublicCutWrite(() -> {
            ChainPersistenceRecords.CommandRecord committed = commandWriter
                    .commitCommand(registered.commandId(), publicCut.taskId(),
                            publicProgression.formalEventId(),
                            publicCut.instructionId());
            logCommitted(committed);
            return startResponse(committed, false);
        });
    }

    private StartBegin readReceivedPlannerStart(
            ProductChainReceivedCommandSource.ReceivedCommand received) {
        sessionCommandLock.lock(received.userId(), received.sessionId());
        AgentSession session = ownedProjectSession(received.userId(),
                received.sessionId());
        ChainPersistenceRecords.CommandRecord command = foundations
                .findCommand(received.userId(), received.sessionId(),
                        received.clientRequestId())
                .filter(value -> value.commandId().equals(received.commandId())
                        && value.status() == ChainCommandStatus.RECEIVED
                        && value.commandKind() == received.commandKind()
                        && value.requestSha256().equals(
                        received.requestSha256())
                        && Objects.equals(value.targetTaskId(),
                        received.targetTaskId())
                        && Objects.equals(value.targetClientRequestId(),
                        received.targetClientRequestId())
                        && Objects.equals(value.turnId(), received.turnId())
                        && Objects.equals(value.userMessageId(),
                        received.messageId()))
                .orElseThrow(() -> new IllegalStateException(
                        "received command authority changed"));
        ChainPersistenceRecords.TaskRecord task = foundations.findTask(
                        received.taskId())
                .filter(value -> value.userId() == received.userId()
                        && value.sessionId() == received.sessionId())
                .orElseThrow(() -> new IllegalStateException(
                        "received command task authority changed"));
        ChainPersistenceRecords.InstructionRecord instruction = foundations
                .findInstruction(received.instructionId())
                .filter(value -> value.commandId().equals(received.commandId())
                        && value.sessionId() == received.sessionId()
                        && value.relationKind() == received.commandKind()
                        && (task.createdByCommandId().equals(
                        received.commandId())
                        ? task.sourceInstructionId().equals(
                        received.instructionId())
                        : value.originTaskId().equals(received.taskId())))
                .orElseThrow(() -> new IllegalStateException(
                        "received command instruction authority changed"));
        AgentMessage message = messages.findById(received.messageId())
                .filter(value -> value.getUserId() == received.userId()
                        && value.getSessionId() == received.sessionId()
                        && "user".equals(value.getRole())
                        && value.getContent() != null)
                .orElseThrow(() -> new IllegalStateException(
                        "received command message authority changed"));
        AgentTurn turn = turns.findById(received.turnId())
                .filter(value -> value.getUserId() == received.userId()
                        && value.getSessionId() == received.sessionId()
                        && Objects.equals(value.getUserMessageId(),
                        received.messageId()))
                .orElseThrow(() -> new IllegalStateException(
                        "received command turn authority changed"));
        ChainPersistenceRecords.TaskRecord target = received.commandKind()
                == ChainInstructionRelation.SUPPLEMENT
                || received.commandKind() == ChainInstructionRelation.CORRECTION
                || received.commandKind() == ChainInstructionRelation.REPLACEMENT
                ? foundations.findTask(requiredReceivedTarget(received))
                .filter(value -> value.userId() == received.userId()
                        && value.sessionId() == received.sessionId())
                .orElseThrow(() -> new IllegalStateException(
                        "received command target authority changed"))
                : null;
        return new StartBegin(session, target, command, message, turn,
                new CommandCut(task.taskId(),
                        received.instructionBindingEventId(),
                        instruction.instructionId()), Instant.now(), false);
    }

    private static String requiredReceivedTarget(
            ProductChainReceivedCommandSource.ReceivedCommand received) {
        if (received.targetTaskId() == null
                || received.targetTaskId().isBlank()) {
            throw new IllegalStateException(
                    "received command target authority changed");
        }
        return received.targetTaskId();
    }

    private static ProjectChainPlannerProgression.ChainInstructionRelationValue
            plannerRelation(ChainInstructionRelation relation) {
        return relation == ChainInstructionRelation.SUPPLEMENT
                ? ProjectChainPlannerProgression.ChainInstructionRelationValue.SUPPLEMENT
                : relation == ChainInstructionRelation.CORRECTION
                ? ProjectChainPlannerProgression.ChainInstructionRelationValue.CORRECTION
                : ProjectChainPlannerProgression.ChainInstructionRelationValue.INITIAL;
    }

    private StartBegin beginStart(
            long userId, long sessionId,
            V2NaturalLanguageTurnRequest request,
            ChainInstructionRelation kind,
            String targetClientRequestId) {
        sessionCommandLock.lock(userId, sessionId);
        AgentSession session = ownedProjectSession(userId, sessionId);
        ChainPersistenceRecords.TaskRecord target = kind
                == ChainInstructionRelation.INITIAL ? null
                : targetTask(userId, sessionId, targetClientRequestId);
        String digest = commandDigest(kind, targetClientRequestId, null,
                request.content(), request.ragDisabled(), request.skillId());
        Optional<ChainPersistenceRecords.CommandRecord> existing =
                foundations.findCommand(userId, sessionId,
                        request.clientRequestId());
        if (existing.isPresent()) {
            requireSameRequest(existing.get(), kind,
                    target == null ? null : target.taskId(),
                    targetClientRequestId, null, digest);
            if (existing.get().status() == ChainCommandStatus.COMMITTED) {
                return new StartBegin(session, target, existing.get(), null,
                        null, null, existing.get().createdAt(), true);
            }
            if (existing.get().status() == ChainCommandStatus.RECEIVED
                    && (kind == ChainInstructionRelation.INITIAL
                    || kind == ChainInstructionRelation.REPLACEMENT)) {
                return recoverKnownBoundaryCut(
                        session, target, existing.get());
            }
            throw conflict("CHAIN_COMMAND_ID_CONFLICT");
        }
        if (target != null && finalization.findTaskOutcome(
                target.taskId()).isPresent()) {
            throw conflict("CHAIN_TASK_TERMINAL");
        }
        if ((kind == ChainInstructionRelation.SUPPLEMENT
                || kind == ChainInstructionRelation.CORRECTION)
                && !skillSnapshots.preservesSelection(
                target.taskId(), request.skillId())) {
            throw conflict("CHAIN_TASK_SKILL_CHANGE_NOT_ALLOWED");
        }

        Instant now = Instant.now();
        AgentMessage message = messages.saveAndFlush(new AgentMessage(
                sessionId, userId, "user", request.content(), null, null));
        AgentTurn turn = turns.saveAndFlush(
                new AgentTurn(sessionId, userId, message.getId()));
        String commandId = identity("command", userId + "\0" + sessionId
                + "\0" + request.clientRequestId());
        ChainPersistenceRecords.CommandRecord received =
                new ChainPersistenceRecords.CommandRecord(
                        commandId, userId, sessionId,
                        request.clientRequestId(), kind,
                        target == null ? null : target.taskId(),
                        targetClientRequestId, null, digest,
                        turn.getId(), message.getId(), null, null, null,
                        ChainCommandStatus.RECEIVED, null, now, null);
        ChainPersistenceRecords.AppendResult<
                ChainPersistenceRecords.CommandRecord> registered =
                commandWriter.registerCommand(received);
        if (registered.replayed()) {
            return recoverKnownBoundaryCut(
                    session, target, registered.value());
        }

        CommandCut cut = kind == ChainInstructionRelation.INITIAL
                ? createInitial(session, registered.value(), message, turn,
                request, now)
                : kind == ChainInstructionRelation.REPLACEMENT
                ? createReplacement(session, target, registered.value(),
                message, turn, request, now)
                : appendToTask(target, registered.value(), message,
                request.content(), kind, null, now);
        if (kind == ChainInstructionRelation.INITIAL
                || kind == ChainInstructionRelation.REPLACEMENT) {
            skillSnapshots.freezeNewTask(userId, cut.taskId(),
                    cut.instructionId(), request.skillId(), now);
            ChainPersistenceRecords.CommandRecord committed = commandWriter
                    .commitCommand(registered.value().commandId(),
                            cut.taskId(), cut.eventId(), cut.instructionId());
            logCommitted(committed);
            return new StartBegin(session, target, committed, message,
                    turn, cut, now, false);
        }
        return new StartBegin(session, target, registered.value(), message,
                turn, cut, now, false);
    }

    private StartBegin recoverKnownBoundaryCut(
            AgentSession session,
            ChainPersistenceRecords.TaskRecord target,
            ChainPersistenceRecords.CommandRecord command) {
        String taskId = identity("task", command.commandId());
        String instructionId = identity("instruction", command.commandId());
        ChainPersistenceRecords.TaskRecord task = foundations.findTask(taskId)
                .filter(value -> value.createdByCommandId().equals(
                                command.commandId())
                        && value.sourceInstructionId().equals(instructionId)
                        && value.userId() == command.userId()
                        && value.sessionId() == command.sessionId()
                        && value.rootClientRequestId().equals(
                                command.clientRequestId())
                        && value.rootRequestSha256().equals(
                                command.requestSha256())
                        && Objects.equals(value.predecessorTaskId(),
                                target == null ? null : target.taskId()))
                .orElseThrow(() -> conflict("CHAIN_COMMAND_ID_CONFLICT"));
        ChainPersistenceRecords.InstructionRecord instruction = foundations
                .findInstruction(instructionId)
                .filter(value -> value.commandId().equals(command.commandId())
                        && value.originTaskId().equals(taskId)
                        && value.relationKind() == command.commandKind())
                .orElseThrow(() -> conflict("CHAIN_COMMAND_ID_CONFLICT"));
        ChainPersistenceRecords.TaskInstructionBindingRecord binding =
                foundations.findTaskInstructions(taskId, Long.MAX_VALUE)
                        .stream().filter(value -> value.instructionId()
                                .equals(instructionId))
                        .filter(value -> value.relationRole()
                                == ChainPersistenceRecords.BindingRole.ORIGIN)
                        .reduce((left, right) -> {
                            throw conflict("CHAIN_COMMAND_ID_CONFLICT");
                        }).orElseThrow(() -> conflict(
                                "CHAIN_COMMAND_ID_CONFLICT"));
        return new StartBegin(session, target, command, null, null,
                new CommandCut(taskId, binding.eventId(), instructionId),
                command.createdAt(), true);
    }

    @Override
    @Transactional
    public V2TurnCommandResponse reply(
            long userId, long sessionId, String targetClientRequestId,
            String gapId, V2TurnCommandRequest request) {
        Objects.requireNonNull(request, "request");
        sessionCommandLock.lock(userId, sessionId);
        ownedProjectSession(userId, sessionId);
        ChainPersistenceRecords.TaskRecord task = targetTask(
                userId, sessionId, targetClientRequestId);
        ChainPersistenceRecords.PendingItemRecord item = workflow
                .findPendingItems(task.taskId()).stream()
                .filter(value -> value.gapId().equals(gapId))
                .findFirst().orElseThrow(() -> notFound(
                        "CHAIN_GAP_NOT_FOUND"));
        String digest = commandDigest(
                ChainInstructionRelation.ANSWER_TO_PENDING_ITEM,
                targetClientRequestId, gapId, request.content(), null, null);
        Optional<ChainPersistenceRecords.CommandRecord> existing =
                foundations.findCommand(userId, sessionId,
                        request.clientRequestId());
        if (existing.isPresent()) {
            requireReplay(existing.get(),
                    ChainInstructionRelation.ANSWER_TO_PENDING_ITEM,
                    task.taskId(), targetClientRequestId, gapId, digest);
            return commandResponse(existing.get(), true);
        }
        if (finalization.findTaskOutcome(task.taskId()).isPresent()) {
            throw conflict("CHAIN_TASK_TERMINAL");
        }
        if (pendingStatus(item) != ChainPendingItemStatus.PENDING) {
            throw conflict("CHAIN_GAP_NOT_REPLYABLE");
        }

        Instant now = Instant.now();
        AgentMessage message = messages.saveAndFlush(new AgentMessage(
                sessionId, userId, "user", request.content(), null, null));
        AgentTurn turn = turns.saveAndFlush(
                new AgentTurn(sessionId, userId, message.getId()));
        String commandId = identity("command", userId + "\0" + sessionId
                + "\0" + request.clientRequestId());
        ChainPersistenceRecords.CommandRecord received =
                new ChainPersistenceRecords.CommandRecord(
                        commandId, userId, sessionId,
                        request.clientRequestId(),
                        ChainInstructionRelation.ANSWER_TO_PENDING_ITEM,
                        task.taskId(), targetClientRequestId, gapId, digest,
                        turn.getId(), message.getId(), null, null, null,
                        ChainCommandStatus.RECEIVED, null, now, null);
        commandWriter.registerCommand(received);
        CommandCut cut = appendToTask(task, received, message,
                request.content(), ChainInstructionRelation
                        .ANSWER_TO_PENDING_ITEM, gapId, now);
        ChainPendingItemRuntime pending = pendingRuntime();
        String responseEvent = identity("gap-response",
                task.taskId() + "\0" + gapId + "\0" + commandId);
        pending.recordResponse(new ChainPendingItemRuntime.ResponseRequest(
                task.taskId(), gapId, responseEvent,
                cut.instructionId(), now));
        ChainPersistenceRecords.CommandRecord committed = commandWriter
                .commitCommand(commandId, task.taskId(), responseEvent,
                        cut.instructionId());
        logCommitted(committed);
        return commandResponse(committed, false);
    }

    @Override
    @Transactional
    public V2TurnCommandResponse cancel(
            long userId, long sessionId, String targetClientRequestId,
            V2TurnCancelRequest request) {
        Objects.requireNonNull(request, "request");
        sessionCommandLock.lock(userId, sessionId);
        ownedProjectSession(userId, sessionId);
        ChainPersistenceRecords.TaskRecord task = targetTask(
                userId, sessionId, targetClientRequestId);
        String digest = commandDigest(ChainInstructionRelation.CANCEL,
                targetClientRequestId, null, null, null, null);
        Optional<ChainPersistenceRecords.CommandRecord> existing =
                foundations.findCommand(userId, sessionId,
                        request.clientRequestId());
        if (existing.isPresent()) {
            requireReplay(existing.get(), ChainInstructionRelation.CANCEL,
                    task.taskId(), targetClientRequestId, null, digest);
            return commandResponse(existing.get(), true);
        }
        if (finalization.findTaskOutcome(task.taskId()).isPresent()) {
            throw conflict("CHAIN_TASK_TERMINAL");
        }

        Instant now = Instant.now();
        String commandId = identity("command", userId + "\0" + sessionId
                + "\0" + request.clientRequestId());
        ChainPersistenceRecords.CommandRecord received =
                new ChainPersistenceRecords.CommandRecord(
                        commandId, userId, sessionId,
                        request.clientRequestId(),
                        ChainInstructionRelation.CANCEL,
                        task.taskId(), targetClientRequestId, null, digest,
                        null, null, null, null, null,
                        ChainCommandStatus.RECEIVED, null, now, null);
        commandWriter.registerCommand(received);
        CommandCut cut = appendBodylessCancel(task, received, now);
        String outcomeEvent = identity("cancel-outcome",
                task.taskId() + "\0" + commandId);
        ChainCancellationRuntime cancellation = new ChainCancellationRuntime(
                foundations, new ChainInstructionStateReader(
                foundations, workflow, finalization), cancellationPort());
        cancellation.cancel(new ChainCancellationRuntime.CancelRequest(
                task.taskId(), cut.instructionId(), commandId, digest,
                outcomeEvent, now));
        ChainPersistenceRecords.CommandRecord committed = commandWriter
                .commitCommand(commandId, task.taskId(), outcomeEvent,
                        cut.instructionId());
        logCommitted(committed);
        return commandResponse(committed, false);
    }

    @Override
    @Transactional(readOnly = true)
    public V2ProjectTurnResponse get(
            long userId, long sessionId, String rootClientRequestId) {
        ownedProjectSession(userId, sessionId);
        return project(rootTask(userId, sessionId, rootClientRequestId)).value();
    }

    @Override
    @Transactional(readOnly = true)
    public List<V2ProjectTurnListItem> list(
            long userId, long sessionId, int limit) {
        ownedProjectSession(userId, sessionId);
        int bounded = Math.max(1, Math.min(limit, 100));
        List<String> taskIds = jdbc.queryForList("""
                SELECT task.task_id
                  FROM agent_v2_chain_tasks task
                  JOIN agent_v2_chain_commands root_command
                    ON root_command.command_id = task.created_by_command_id
                   AND root_command.status = 'COMMITTED'
                   AND root_command.result_task_id = task.task_id
                 WHERE task.user_id = :userId
                   AND task.session_id = :sessionId
                 ORDER BY task.created_at DESC, task.task_id DESC
                 LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("sessionId", sessionId)
                .addValue("limit", bounded), String.class);
        List<V2ProjectTurnListItem> result = new ArrayList<>();
        for (String taskId : taskIds) {
            ChainPersistenceRecords.TaskRecord task = foundations
                    .findTask(taskId).orElseThrow();
            Projection projection = project(task);
            result.add(V2ProjectTurnListItem.from(
                    projection.value(), sourceQuestion(task),
                    task.createdAt(), projection.updatedAt()));
        }
        return List.copyOf(result);
    }

    private CommandCut createInitial(
            AgentSession session,
            ChainPersistenceRecords.CommandRecord command,
            AgentMessage message, AgentTurn turn,
            V2NaturalLanguageTurnRequest request, Instant now) {
        String taskId = identity("task", command.commandId());
        String instructionId = identity("instruction", command.commandId());
        String version = projects.manifest(
                command.userId(), session.getProjectId()).version();
        ChainPersistenceRecords.TaskRecord task =
                new ChainPersistenceRecords.TaskRecord(
                        taskId, command.commandId(), instructionId, null,
                        command.userId(), command.sessionId(), turn.getId(),
                        message.getId(), command.clientRequestId(),
                        command.requestSha256(), session.getProjectId(),
                        version, 0, now);
        taskWriter.appendTask(task);
        return bindInstruction(task, command, instructionId, message.getId(),
                request.content(), ChainInstructionRelation.INITIAL,
                null, null, 1, ChainPersistenceRecords.BindingRole.ORIGIN,
                now);
    }

    private CommandCut createReplacement(
            AgentSession session,
            ChainPersistenceRecords.TaskRecord predecessor,
            ChainPersistenceRecords.CommandRecord command,
            AgentMessage message, AgentTurn turn,
            V2NaturalLanguageTurnRequest request, Instant now) {
        String taskId = identity("task", command.commandId());
        String instructionId = identity("instruction", command.commandId());
        String version = projects.manifest(
                command.userId(), session.getProjectId()).version();
        ChainPersistenceRecords.TaskRecord task =
                new ChainPersistenceRecords.TaskRecord(
                        taskId, command.commandId(), instructionId,
                        predecessor.taskId(), command.userId(),
                        command.sessionId(), turn.getId(), message.getId(),
                        command.clientRequestId(), command.requestSha256(),
                        session.getProjectId(), version, 0, now);
        taskWriter.appendTask(task);

        ChainPersistenceRecords.InstructionRecord inherited = foundations
                .findInstruction(predecessor.sourceInstructionId())
                .orElseThrow(() -> new IllegalStateException(
                        "predecessor source instruction is missing"));
        bindExistingInstruction(task, inherited, 1,
                ChainPersistenceRecords.BindingRole.INHERITED_ROOT, now);
        ChainPersistenceRecords.InstructionRecord current = currentInstruction(
                predecessor.taskId());
        CommandCut cut = bindInstruction(task, command, instructionId,
                message.getId(), request.content(),
                ChainInstructionRelation.REPLACEMENT,
                current.instructionId(), null, 2,
                ChainPersistenceRecords.BindingRole.ORIGIN, now);
        terminateOpenGapsForSupersession(
                predecessor, instructionId, now);
        supersede(predecessor, command, current, instructionId, now);
        return cut;
    }

    private CommandCut createDispositionReplacement(
            AgentSession session,
            ChainPersistenceRecords.TaskRecord predecessor,
            ChainPersistenceRecords.CommandRecord command,
            AgentMessage message, AgentTurn turn,
            ChainPersistenceRecords.InstructionRecord triggerInstruction,
            String dispositionEventId, Instant now) {
        if (predecessor == null
                || triggerInstruction.parentInstructionId() == null) {
            throw new IllegalStateException(
                    "boundary disposition requires an existing task boundary");
        }
        ChainPersistenceRecords.InstructionDispositionRecord disposition = workflow
                .findInstructionDispositions(predecessor.taskId()).stream()
                .filter(value -> value.eventId().equals(dispositionEventId)
                        && value.instructionId().equals(
                        triggerInstruction.instructionId()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "boundary disposition is not readable"));
        if (!disposition.boundaryChanged()) {
            throw new IllegalStateException(
                    "replacement requires a boundary-changing disposition");
        }
        ChainPersistenceRecords.InstructionRecord oldBoundaryInstruction = foundations
                .findInstruction(triggerInstruction.parentInstructionId())
                .orElseThrow(() -> new IllegalStateException(
                        "old task boundary instruction is missing"));
        if (!currentInstruction(predecessor.taskId()).instructionId()
                .equals(triggerInstruction.instructionId())) {
            throw new IllegalStateException(
                    "boundary disposition targets a stale instruction");
        }

        String taskId = identity("task", command.commandId());
        String version = projects.manifest(
                command.userId(), session.getProjectId()).version();
        ChainPersistenceRecords.TaskRecord replacement =
                new ChainPersistenceRecords.TaskRecord(
                        taskId, command.commandId(),
                        triggerInstruction.instructionId(),
                        predecessor.taskId(), command.userId(),
                        command.sessionId(), turn.getId(), message.getId(),
                        command.clientRequestId(), command.requestSha256(),
                        session.getProjectId(), version, 0, now);
        taskWriter.appendTask(replacement);
        bindExistingInstruction(replacement, triggerInstruction, 1,
                ChainPersistenceRecords.BindingRole.INHERITED_ROOT, now);
        terminateOpenGapsForSupersession(
                predecessor, triggerInstruction.instructionId(), now);
        supersedeAfterBoundaryDisposition(
                predecessor, replacement, command, oldBoundaryInstruction,
                triggerInstruction, disposition, now);
        String eventId = identity("instruction-bound",
                replacement.taskId() + "\0" + 1 + "\0"
                        + triggerInstruction.instructionId());
        return new CommandCut(replacement.taskId(), eventId,
                triggerInstruction.instructionId());
    }

    private void terminateOpenGapsForSupersession(
            ChainPersistenceRecords.TaskRecord predecessor,
            String successorInstructionId, Instant now) {
        ChainPendingItemRuntime pending = pendingRuntime();
        for (ChainPersistenceRecords.PendingItemRecord item
                : workflow.findOpenPendingItems(predecessor.taskId())) {
            String eventId = identity("gap-superseded",
                    predecessor.taskId() + "\0" + item.gapId()
                            + "\0" + successorInstructionId);
            pending.terminate(new ChainPendingItemRuntime.TerminationRequest(
                    predecessor.taskId(), item.gapId(), eventId,
                    ChainPendingItemStatus.CANCELLED,
                    "TASK_SUPERSEDED", now));
        }
    }

    private CommandCut appendToTask(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.CommandRecord command,
            AgentMessage message, String content,
            ChainInstructionRelation relation, String gapId, Instant now) {
        ChainPersistenceRecords.InstructionRecord current =
                currentInstruction(task.taskId());
        int sequence = foundations.findTaskInstructions(
                task.taskId(), Long.MAX_VALUE).size() + 1;
        String instructionId = identity("instruction", command.commandId());
        return bindInstruction(task, command, instructionId,
                message.getId(), content, relation,
                current.instructionId(), gapId, sequence,
                ChainPersistenceRecords.BindingRole.ORIGIN, now);
    }

    private CommandCut appendBodylessCancel(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.CommandRecord command, Instant now) {
        ChainPersistenceRecords.InstructionRecord current =
                currentInstruction(task.taskId());
        int sequence = foundations.findTaskInstructions(
                task.taskId(), Long.MAX_VALUE).size() + 1;
        return bindInstruction(task, command,
                identity("instruction", command.commandId()),
                null, null, ChainInstructionRelation.CANCEL,
                current.instructionId(), null, sequence,
                ChainPersistenceRecords.BindingRole.ORIGIN, now);
    }

    private CommandCut bindInstruction(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.CommandRecord command,
            String instructionId, Long messageId, String content,
            ChainInstructionRelation relation, String parentInstructionId,
            String answeredGapId, int sequence,
            ChainPersistenceRecords.BindingRole role, Instant now) {
        String bodyDigest = content == null ? null : sha256(content);
        ChainPersistenceRecords.InstructionRecord instruction =
                new ChainPersistenceRecords.InstructionRecord(
                        instructionId, command.commandId(),
                        command.sessionId(), task.taskId(), messageId,
                        bodyDigest, "command:" + command.commandId(), relation,
                        parentInstructionId, answeredGapId,
                        sha256(relation + "\0" + Objects.toString(
                                parentInstructionId, ChainIdentity.NONE)
                                + "\0" + Objects.toString(
                                bodyDigest, ChainIdentity.NONE)), now);
        String eventId = identity("instruction-bound",
                task.taskId() + "\0" + sequence + "\0" + instructionId);
        ChainPersistenceRecords.TaskInstructionBindingRecord binding =
                new ChainPersistenceRecords.TaskInstructionBindingRecord(
                        task.taskId(), eventId, instructionId, sequence,
                        role, now);
        ChainPersistenceRecords.AuthorityEventRequest event =
                new ChainPersistenceRecords.AuthorityEventRequest(
                        eventId, task.taskId(), "INSTRUCTION_BOUND", null,
                        sha256(instructionId + "\0" + sequence
                                + "\0" + role), now);
        ChainInstructionRuntime.AppendOutcome appended = instructionRuntime()
                .append(new ChainInstructionRuntime.AppendRequest(
                        task.taskId(), instruction,
                        new ChainPersistenceRecords.AuthoritativeFact<>(
                                event, binding)));
        return new CommandCut(task.taskId(), eventId,
                appended.instruction().instructionId());
    }

    private void bindExistingInstruction(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.InstructionRecord instruction,
            int sequence, ChainPersistenceRecords.BindingRole role,
            Instant now) {
        String eventId = identity("instruction-bound",
                task.taskId() + "\0" + sequence + "\0"
                        + instruction.instructionId());
        ChainPersistenceRecords.TaskInstructionBindingRecord binding =
                new ChainPersistenceRecords.TaskInstructionBindingRecord(
                        task.taskId(), eventId, instruction.instructionId(),
                        sequence, role, now);
        ChainPersistenceRecords.AuthorityEventRequest event =
                new ChainPersistenceRecords.AuthorityEventRequest(
                        eventId, task.taskId(), "INSTRUCTION_BOUND", null,
                        sha256(instruction.instructionId() + "\0" + sequence
                                + "\0" + role), now);
        instructionRuntime().append(new ChainInstructionRuntime.AppendRequest(
                task.taskId(), instruction,
                new ChainPersistenceRecords.AuthoritativeFact<>(
                        event, binding)));
    }

    private ChainInstructionRuntime instructionRuntime() {
        return new ChainInstructionRuntime(
                foundations, workflow, instructionWriter);
    }

    private ChainPendingItemRuntime pendingRuntime() {
        ChainPendingItemRuntime.NormalSuccessorPort successors =
                new ChainPendingItemRuntime.NormalSuccessorPort() {
                    @Override
                    public ChainPendingItemRuntime.OfficialSuccessor commit(
                            ChainPendingItemRuntime.NormalSuccessorRequest request) {
                        throw unsupported();
                    }

                    @Override
                    public Optional<ChainPendingItemRuntime.OfficialSuccessor>
                            findCommitted(String taskId, String transitionId) {
                        return Optional.empty();
                    }
                };
        ChainPendingItemRuntime.PermissionDecisionSource permissions =
                new ChainPendingItemRuntime.PermissionDecisionSource() {
                    @Override
                    public Optional<ChainPersistenceRecords.PermissionDecisionRecord>
                            find(String taskId, String gapId, String decisionId) {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<ChainPersistenceRecords.PermissionDecisionRecord>
                            findLatest(String taskId, String gapId) {
                        return Optional.empty();
                    }
                };
        return new ChainPendingItemRuntime(
                workflow, foundations, pendingWriter,
                proposalId -> { throw unsupported(); },
                proposalId -> { throw unsupported(); },
                successors, permissions,
                (taskId, proposalId, authorityType, authorityRef) -> {
                    throw unsupported();
                });
    }

    private ChainTaskOutcomeCommandPort cancellationPort() {
        return command -> {
            for (ChainPersistenceRecords.PendingItemRecord item
                    : workflow.findOpenPendingItems(command.taskId())) {
                int round = workflow.findPendingItemEvents(item.gapId())
                        .stream().mapToInt(ChainPersistenceRecords
                                .PendingItemEventRecord::responseRound)
                        .max().orElse(0);
                String eventId = identity("gap-cancelled",
                        command.instructionId() + "\0" + item.gapId());
                ChainPersistenceRecords.PendingItemEventRecord closed =
                        new ChainPersistenceRecords.PendingItemEventRecord(
                                item.gapId(), round,
                                ChainPendingItemStatus.CANCELLED,
                                command.taskId(), eventId, null, null, null,
                                canonical(EMPTY_ARRAY), command.createdAt());
                pendingWriter.appendPendingItemEvent(
                        new ChainPersistenceRecords.AuthoritativeFact<>(
                                new ChainPersistenceRecords
                                        .AuthorityEventRequest(
                                        eventId, command.taskId(),
                                        "PENDING_ITEM_EVENT", null,
                                        sha256(item.gapId() + "\0CANCELLED\0"
                                                + command.instructionId()),
                                        command.createdAt()), closed));
            }
            ChainTaskOutcomeRuntime.OutcomeDraft draft =
                    new ChainTaskOutcomeRuntime.OutcomeDraft(
                            command.taskId(), command.eventId(),
                            command.sourceCommandId(), command.instructionId(),
                            null, null, null, canonical(EMPTY_ARRAY),
                            canonical(EMPTY_ARRAY), null, ChainIdentity.NONE,
                            ChainIdentity.NONE, null, null, null, null,
                            canonical(EMPTY_ARRAY), canonical(EMPTY_ARRAY),
                            canonical(EMPTY_ARRAY), command.createdAt());
            ChainTaskOutcomeRuntime.CommitResult result = outcomeAuthority.commit(
                    new ChainTaskOutcomeRuntime.Cancelled(
                            draft, command.instructionId()),
                    cancellationVerifier(command));
            return new ChainTaskOutcomeCommandPort.CancellationSubmission(
                    result.outcome(), result.replayed());
        };
    }

    private ChainTaskOutcomeRuntime.FormalSourceVerifier cancellationVerifier(
            ChainTaskOutcomeCommandPort.CancelledTaskOutcomeCommand source) {
        return new ChainTaskOutcomeRuntime.FormalSourceVerifier() {
            @Override public void verifyCompleted(
                    ChainTaskOutcomeRuntime.Completed value) { throw unsupported(); }
            @Override public void verifyFailed(
                    ChainTaskOutcomeRuntime.Failed value) { throw unsupported(); }
            @Override public void verifySuperseded(
                    ChainTaskOutcomeRuntime.Superseded value) { throw unsupported(); }

            @Override
            public void verifyCancelled(ChainTaskOutcomeRuntime.Cancelled value) {
                ChainPersistenceRecords.CommandRecord command = foundations
                        .findCommand(source.sourceCommandId()).orElseThrow();
                ChainPersistenceRecords.InstructionRecord instruction = foundations
                        .findInstruction(source.instructionId()).orElseThrow();
                if (command.commandKind() != ChainInstructionRelation.CANCEL
                        || !command.requestSha256().equals(
                        source.sourceRequestSha256())
                        || !instruction.commandId().equals(command.commandId())
                        || instruction.relationKind()
                        != ChainInstructionRelation.CANCEL
                        || !instruction.originTaskId().equals(source.taskId())) {
                    throw new IllegalStateException(
                            "formal cancellation source changed");
                }
            }
        };
    }

    private void supersede(
            ChainPersistenceRecords.TaskRecord oldTask,
            ChainPersistenceRecords.CommandRecord command,
            ChainPersistenceRecords.InstructionRecord oldInstruction,
            String replacementInstructionId, Instant now) {
        ChainPersistenceRecords.PlanBindingRecord plan = workflow
                .findPlanBindings(oldTask.taskId()).stream()
                .reduce((left, right) -> right).orElse(null);
        ChainPersistenceRecords.CanonicalJson empty = canonical(EMPTY_ARRAY);
        ChainTaskOutcomeRuntime.OutcomeDraft draft =
                new ChainTaskOutcomeRuntime.OutcomeDraft(
                        oldTask.taskId(), identity("superseded-outcome",
                        oldTask.taskId() + "\0" + replacementInstructionId),
                        command.commandId(), oldInstruction.instructionId(),
                        plan == null ? null : plan.taskFrameId(),
                        plan == null ? null : plan.planId(),
                        plan == null ? null : plan.planRevisionId(), empty,
                        empty, null, ChainIdentity.NONE, ChainIdentity.NONE,
                        null, null, null, null, empty, empty, empty, now);
        ChainTaskOutcomeRuntime.OldBoundary boundary =
                new ChainTaskOutcomeRuntime.OldBoundary(
                        oldInstruction.instructionId(), draft.taskFrameId(),
                        draft.finalPlanId(), draft.finalPlanRevisionId(),
                        draft.coverage(), draft.acceptedSet());
        outcomeAuthority.commit(new ChainTaskOutcomeRuntime.Superseded(
                draft, boundary, replacementInstructionId),
                supersededVerifier(command, replacementInstructionId));
    }

    private void supersedeAfterBoundaryDisposition(
            ChainPersistenceRecords.TaskRecord oldTask,
            ChainPersistenceRecords.TaskRecord replacementTask,
            ChainPersistenceRecords.CommandRecord command,
            ChainPersistenceRecords.InstructionRecord oldBoundaryInstruction,
            ChainPersistenceRecords.InstructionRecord triggerInstruction,
            ChainPersistenceRecords.InstructionDispositionRecord disposition,
            Instant now) {
        ChainPersistenceRecords.PlanBindingRecord plan = workflow
                .findPlanBindings(oldTask.taskId()).stream()
                .reduce((left, right) -> right).orElse(null);
        ChainPersistenceRecords.CanonicalJson empty = canonical(EMPTY_ARRAY);
        ChainTaskOutcomeRuntime.OutcomeDraft draft =
                new ChainTaskOutcomeRuntime.OutcomeDraft(
                        oldTask.taskId(), identity("superseded-outcome",
                        oldTask.taskId() + "\0"
                                + triggerInstruction.instructionId()),
                        command.commandId(),
                        oldBoundaryInstruction.instructionId(),
                        plan == null ? null : plan.taskFrameId(),
                        plan == null ? null : plan.planId(),
                        plan == null ? null : plan.planRevisionId(), empty,
                        empty, null, ChainIdentity.NONE, ChainIdentity.NONE,
                        null, null, null, null, empty, empty, empty, now);
        ChainTaskOutcomeRuntime.OldBoundary boundary =
                new ChainTaskOutcomeRuntime.OldBoundary(
                        oldBoundaryInstruction.instructionId(),
                        draft.taskFrameId(), draft.finalPlanId(),
                        draft.finalPlanRevisionId(), draft.coverage(),
                        draft.acceptedSet());
        outcomeAuthority.commit(new ChainTaskOutcomeRuntime.Superseded(
                draft, boundary, triggerInstruction.instructionId()),
                boundaryDispositionSupersededVerifier(oldTask, replacementTask,
                        command, oldBoundaryInstruction, triggerInstruction,
                        disposition));
    }

    private ChainTaskOutcomeRuntime.FormalSourceVerifier supersededVerifier(
            ChainPersistenceRecords.CommandRecord command,
            String replacementInstructionId) {
        return new ChainTaskOutcomeRuntime.FormalSourceVerifier() {
            @Override public void verifyCompleted(
                    ChainTaskOutcomeRuntime.Completed value) { throw unsupported(); }
            @Override public void verifyFailed(
                    ChainTaskOutcomeRuntime.Failed value) { throw unsupported(); }
            @Override public void verifyCancelled(
                    ChainTaskOutcomeRuntime.Cancelled value) { throw unsupported(); }

            @Override
            public void verifySuperseded(
                    ChainTaskOutcomeRuntime.Superseded value) {
                ChainPersistenceRecords.InstructionRecord replacement =
                        foundations.findInstruction(replacementInstructionId)
                                .orElseThrow();
                if (command.commandKind()
                        != ChainInstructionRelation.REPLACEMENT
                        || !replacement.commandId().equals(command.commandId())
                        || replacement.relationKind()
                        != ChainInstructionRelation.REPLACEMENT) {
                    throw new IllegalStateException(
                            "formal replacement source changed");
                }
            }
        };
    }

    private ChainTaskOutcomeRuntime.FormalSourceVerifier
            boundaryDispositionSupersededVerifier(
            ChainPersistenceRecords.TaskRecord oldTask,
            ChainPersistenceRecords.TaskRecord replacementTask,
            ChainPersistenceRecords.CommandRecord command,
            ChainPersistenceRecords.InstructionRecord oldBoundaryInstruction,
            ChainPersistenceRecords.InstructionRecord triggerInstruction,
            ChainPersistenceRecords.InstructionDispositionRecord disposition) {
        return new ChainTaskOutcomeRuntime.FormalSourceVerifier() {
            @Override public void verifyCompleted(
                    ChainTaskOutcomeRuntime.Completed value) { throw unsupported(); }
            @Override public void verifyFailed(
                    ChainTaskOutcomeRuntime.Failed value) { throw unsupported(); }
            @Override public void verifyCancelled(
                    ChainTaskOutcomeRuntime.Cancelled value) { throw unsupported(); }

            @Override
            public void verifySuperseded(
                    ChainTaskOutcomeRuntime.Superseded value) {
                ChainPersistenceRecords.TaskRecord storedReplacement = foundations
                        .findTask(replacementTask.taskId()).orElseThrow();
                ChainPersistenceRecords.InstructionRecord storedTrigger = foundations
                        .findInstruction(triggerInstruction.instructionId())
                        .orElseThrow();
                ChainPersistenceRecords.InstructionDispositionRecord storedDisposition = workflow
                        .findInstructionDispositions(oldTask.taskId()).stream()
                        .filter(candidate -> candidate.dispositionId().equals(
                                disposition.dispositionId()))
                        .findFirst().orElseThrow();
                List<ChainPersistenceRecords.TaskInstructionBindingRecord>
                        replacementBindings = foundations.findTaskInstructions(
                        replacementTask.taskId(), Long.MAX_VALUE);
                verifyBoundaryReplacementProof(
                        oldTask, storedReplacement, command,
                        oldBoundaryInstruction, storedTrigger,
                        storedDisposition, replacementBindings,
                        value.oldBoundary().instructionId(),
                        value.supersededByInstructionId());
            }
        };
    }

    static void verifyBoundaryReplacementProof(
            ChainPersistenceRecords.TaskRecord oldTask,
            ChainPersistenceRecords.TaskRecord replacementTask,
            ChainPersistenceRecords.CommandRecord command,
            ChainPersistenceRecords.InstructionRecord oldBoundaryInstruction,
            ChainPersistenceRecords.InstructionRecord triggerInstruction,
            ChainPersistenceRecords.InstructionDispositionRecord disposition,
            List<ChainPersistenceRecords.TaskInstructionBindingRecord>
                    replacementBindings,
            String outcomeOldBoundaryInstructionId,
            String outcomeSupersedingInstructionId) {
        Objects.requireNonNull(oldTask, "oldTask");
        Objects.requireNonNull(replacementTask, "replacementTask");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(oldBoundaryInstruction,
                "oldBoundaryInstruction");
        Objects.requireNonNull(triggerInstruction, "triggerInstruction");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(replacementBindings, "replacementBindings");
        boolean supportedCommand = command.commandKind()
                == ChainInstructionRelation.SUPPLEMENT
                || command.commandKind()
                == ChainInstructionRelation.CORRECTION;
        boolean supportedInstruction = triggerInstruction.relationKind()
                == command.commandKind();
        boolean exactBinding = replacementBindings.size() == 1
                && replacementBindings.get(0).taskId().equals(
                replacementTask.taskId())
                && replacementBindings.get(0).instructionId().equals(
                triggerInstruction.instructionId())
                && replacementBindings.get(0).taskInstructionSequence() == 1
                && replacementBindings.get(0).relationRole()
                == ChainPersistenceRecords.BindingRole.INHERITED_ROOT;
        if (!supportedCommand || !supportedInstruction
                || command.userId() != oldTask.userId()
                || command.sessionId() != oldTask.sessionId()
                || !Objects.equals(command.targetTaskId(), oldTask.taskId())
                || !Objects.equals(command.targetClientRequestId(),
                oldTask.rootClientRequestId())
                || !triggerInstruction.commandId().equals(command.commandId())
                || triggerInstruction.sessionId() != oldTask.sessionId()
                || !triggerInstruction.originTaskId().equals(oldTask.taskId())
                || !Objects.equals(triggerInstruction.parentInstructionId(),
                oldBoundaryInstruction.instructionId())
                || !disposition.taskId().equals(oldTask.taskId())
                || !disposition.instructionId().equals(
                triggerInstruction.instructionId())
                || !disposition.boundaryChanged()
                || !replacementTask.createdByCommandId().equals(
                command.commandId())
                || !replacementTask.sourceInstructionId().equals(
                triggerInstruction.instructionId())
                || !Objects.equals(replacementTask.predecessorTaskId(),
                oldTask.taskId())
                || replacementTask.userId() != oldTask.userId()
                || replacementTask.sessionId() != oldTask.sessionId()
                || !Objects.equals(replacementTask.turnId(), command.turnId())
                || !Objects.equals(replacementTask.requestMessageId(),
                command.userMessageId())
                || !replacementTask.rootClientRequestId().equals(
                command.clientRequestId())
                || !replacementTask.rootRequestSha256().equals(
                command.requestSha256())
                || !Objects.equals(replacementTask.projectId(),
                oldTask.projectId())
                || !outcomeOldBoundaryInstructionId.equals(
                oldBoundaryInstruction.instructionId())
                || !outcomeSupersedingInstructionId.equals(
                triggerInstruction.instructionId())
                || !exactBinding) {
            throw new IllegalStateException(
                    "formal boundary replacement source changed");
        }
    }

    private Projection project(ChainPersistenceRecords.TaskRecord task) {
        List<ChainPersistenceRecords.RouteDecisionRecord> routes = workflow
                .findRouteDecisions(task.taskId());
        ChainPersistenceRecords.RouteDecisionRecord route = last(routes);
        ChainPersistenceRecords.PlanBindingRecord plan = last(
                workflow.findPlanBindings(task.taskId()));
        PendingProjection pending = pendingProjection(task.taskId());
        ChainPersistenceRecords.TaskOutcomeRecord outcome = finalization
                .findTaskOutcome(task.taskId()).orElse(null);
        DeliveryProjection delivery = deliveryProjection(task.taskId());
        CandidateProjection candidate = candidateProjection(task, outcome);
        V2ProjectTurnResponse.Validation validation = validationProjection(
                task, candidate, outcome);
        List<V2ProjectTurnResponse.Step> steps = stepProjection(
                task.taskId(), plan, pending,
                workflow.findWorkspaceCandidates(task.taskId()));
        PublishProjection publish = publishProjection(outcome);
        String workState = isProgressionBlocked(task.taskId())
                && outcome == null
                ? "BLOCKED"
                : workState(route, plan, pending, outcome, delivery,
                        steps).name();
        V2ProjectTurnResponse value = new V2ProjectTurnResponse(
                task.rootClientRequestId(), workState,
                outcome == null ? null : outcome.outcomeType().name(),
                delivery.status() == null ? null : delivery.status().name(),
                route == null ? null : route.route().name(),
                plan == null ? null : plan.planId(),
                task.initialProjectVersion(),
                publish.projectVersion(), publish.revisionId(),
                publish.receiptId(),
                steps, pending == null ? null : pending.value(), validation,
                delivery.finalText(), candidate.artifactId(),
                candidate.outputPaths(),
                outcome == null ? null : outcome.failureCategory(),
                outcome == null ? null : outcome.failureCode(),
                delivery.errorCode());
        Instant updatedAt = task.createdAt();
        for (ChainPersistenceRecords.AuthorityEventRecord event
                : foundations.findAuthorityEvents(
                task.taskId(), Long.MAX_VALUE)) {
            if (event.committedAt().isAfter(updatedAt)) {
                updatedAt = event.committedAt();
            }
        }
        return new Projection(value, updatedAt);
    }

    private boolean isProgressionBlocked(String taskId) {
        return !jdbc.queryForList("""
                SELECT task_id
                  FROM agent_v2_chain_progression_guards
                 WHERE task_id = :taskId
                   AND state = 'BLOCKED'
                """, Map.of("taskId", taskId), String.class).isEmpty();
    }

    private PendingProjection pendingProjection(String taskId) {
        List<ChainPersistenceRecords.PendingItemRecord> items = workflow
                .findPendingItems(taskId);
        for (int index = items.size() - 1; index >= 0; index--) {
            ChainPersistenceRecords.PendingItemRecord item = items.get(index);
            ChainPendingItemStatus status = pendingStatus(item);
            if (status != ChainPendingItemStatus.RESOLVED
                    && status != ChainPendingItemStatus.REJECTED
                    && status != ChainPendingItemStatus.CANCELLED) {
                return new PendingProjection(item, status,
                        new V2ProjectTurnResponse.PendingItem(
                                item.gapId(), item.pendingType().name(),
                                status.name(), item.question(),
                                item.expectedFormat()));
            }
        }
        return null;
    }

    private DeliveryProjection deliveryProjection(String taskId) {
        ChainPersistenceRecords.DeliveryRecord delivery = last(
                finalization.findDeliveries(taskId));
        if (delivery == null) {
            return new DeliveryProjection(null, null, null);
        }
        ChainPersistenceRecords.DeliveryEventRecord event = last(
                finalization.findDeliveryEvents(delivery.deliveryId()));
        if (event == null) {
            throw new IllegalStateException("formal Delivery has no state");
        }
        String text = null;
        if (event.eventKind() == ChainDeliveryStatus.SUCCEEDED
                && delivery.answerContentId() != null) {
            text = models.findContent(delivery.answerContentId())
                    .filter(content -> content.taskId().equals(taskId))
                    .map(ChainPersistenceRecords.ContentRecord::body)
                    .orElseThrow(() -> new IllegalStateException(
                            "successful Delivery content is missing"));
        }
        return new DeliveryProjection(event.eventKind(), text,
                event.errorCode());
    }

    private CandidateProjection candidateProjection(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.TaskOutcomeRecord outcome) {
        Long artifactId = outcome == null ? null : outcome.finalArtifactId();
        String fingerprint = null;
        if (artifactId == null) {
            ChainPersistenceRecords.CandidateStepResultRecord candidate =
                    last(workflow.findCandidateStepResults(task.taskId()));
            artifactId = candidate == null ? null : candidate.artifactId();
            fingerprint = candidate == null
                    ? null : candidate.candidateFingerprint();
        }
        if (artifactId == null) {
            ChainPersistenceRecords.WorkspaceCandidateRecord candidate = last(
                    workflow.findWorkspaceCandidates(task.taskId()));
            if (candidate == null) {
                return new CandidateProjection(null, null, List.of());
            }
            artifactId = candidate.artifactId();
            fingerprint = candidate.candidateFingerprint();
        }
        var artifact = candidateArtifacts.getCurrent(task.userId(), artifactId);
        if (fingerprint == null) {
            fingerprint = artifact.fingerprint().sha256();
        }
        List<String> paths = artifact.changes().stream()
                .map(change -> change.relativePath().value())
                .distinct().sorted().toList();
        return new CandidateProjection(artifactId, fingerprint, paths);
    }

    private V2ProjectTurnResponse.Validation validationProjection(
            ChainPersistenceRecords.TaskRecord task,
            CandidateProjection candidate,
            ChainPersistenceRecords.TaskOutcomeRecord outcome) {
        String validationId = outcome == null ? null : outcome.validationId();
        if (validationId == null || ChainIdentity.NONE.equals(validationId)) {
            return null;
        }
        var value = terminalValidations.terminalValidation(
                task, outcome, candidate.fingerprint());
        if (!validationId.equals(value.validationId())) {
            throw new IllegalStateException(
                    "terminal validation identity changed");
        }
        return new V2ProjectTurnResponse.Validation(
                value.validationId(), value.status(), value.requestDigest(),
                value.receiptDigest(), value.receipts().stream().map(receipt ->
                        new V2ProjectTurnResponse.ValidationReceipt(
                                receipt.requirementId(), receipt.subject(),
                                receipt.receiptId(), receipt.actionId(),
                                receipt.candidateArtifactId(),
                                receipt.candidateFingerprint(),
                                receipt.projectVersion())).toList())
                .requireComplete();
    }

    private List<V2ProjectTurnResponse.Step> stepProjection(
            String taskId,
            ChainPersistenceRecords.PlanBindingRecord binding,
            PendingProjection pending,
            List<ChainPersistenceRecords.WorkspaceCandidateRecord>
                    workspaceCandidates) {
        if (binding == null) {
            return List.of();
        }
        var revision = formalPlanRevisionForProjection(taskId, binding);
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>();
        StepRecoverySnapshot recovery = stepRecovery.inspect(
                        new PlanId(binding.planId())).value()
                .orElse(null);
        if (recovery instanceof PersistedStepRecoveryReady ready) {
            states.putAll(ready.checkpoint().checkpoint().stepStates());
        } else if (recovery instanceof PersistedStepRecoveryActive active) {
            states.putAll(active.checkpoint().checkpoint().stepStates());
        }
        List<ChainPersistenceRecords.CandidateStepResultRecord> candidates =
                workflow.findCandidateStepResults(taskId);
        List<ChainPersistenceRecords.ReviewDecisionRecord> reviews =
                workflow.findReviewDecisions(taskId);
        List<ChainPersistenceRecords.AcceptedResultRecord> accepted =
                workflow.findAcceptedResults(taskId);
        List<V2ProjectTurnResponse.Step> result = new ArrayList<>();
        for (int index = 0; index < revision.steps().size(); index++) {
            PlanStep step = revision.steps().get(index);
            String status = stepStatus(step, states.get(step.id()), recovery,
                    pending, candidates, reviews, accepted,
                    workspaceCandidates).name();
            result.add(new V2ProjectTurnResponse.Step(
                    step.id().value(), index, step.intent(), status,
                    step.expectedOutcome()));
        }
        return List.copyOf(result);
    }

    /** Uses the same exact historical Plan authority as execution recovery. */
    io.paperagent.v2.contracts.PlanRevision formalPlanRevisionForProjection(
            String taskId,
            ChainPersistenceRecords.PlanBindingRecord binding) {
        Objects.requireNonNull(binding, "binding");
        return stepAuthorities.findPlanRevision(
                        taskId, binding.planRevisionId())
                .orElseThrow(() -> new IllegalStateException(
                        "formal Plan revision is missing"));
    }

    private static io.paperagent.v2.chain.ChainStepStatus stepStatus(
            PlanStep step, StepExecutionState state,
            StepRecoverySnapshot recovery, PendingProjection pending,
            List<ChainPersistenceRecords.CandidateStepResultRecord> candidates,
            List<ChainPersistenceRecords.ReviewDecisionRecord> reviews,
            List<ChainPersistenceRecords.AcceptedResultRecord> accepted,
            List<ChainPersistenceRecords.WorkspaceCandidateRecord>
                    workspaceCandidates) {
        if (recovery instanceof PersistedStepRecoverySucceeded succeeded) {
            // The terminal recovery snapshot is the authoritative persisted
            // representation after the final StepCompletion.  Read its
            // completed checkpoint here so every projection path reports the
            // finished step instead of falling through to NOT_STARTED.
            state = succeeded.checkpoint().checkpoint().stepStates()
                    .get(step.id());
        }
        if (state == StepExecutionState.SUCCEEDED) {
            return io.paperagent.v2.chain.ChainStepStatus.COMPLETED;
        }
        if (state == StepExecutionState.SUPERSEDED_BY_REPLAN) {
            return io.paperagent.v2.chain.ChainStepStatus
                    .SUPERSEDED_BY_REPLAN;
        }
        if (recovery instanceof PersistedStepRecoveryActive active
                && active.activation().stepId().equals(step.id())) {
            boolean awaitingReview = candidates.stream()
                    .filter(candidate -> candidate.stepId().equals(
                            step.id().value()))
                    .anyMatch(candidate -> accepted.stream().noneMatch(
                                    result -> result.candidateResultId().equals(
                                            candidate.candidateResultId()))
                            && reviews.stream().noneMatch(review ->
                                    review.reviewObjectId().equals(
                                            candidate.candidateResultId())));
            if (awaitingReview) {
                return io.paperagent.v2.chain.ChainStepStatus
                        .AWAITING_REVIEW;
            }
            // A WORKSPACE_CHANGE is a formal candidate-producing effect. Until
            // the reflector result runtime is wired for this product entry
            // point, expose its durable candidate as awaiting review rather
            // than leaving the step falsely marked as still executing.
            if (!workspaceCandidates.isEmpty()) {
                return io.paperagent.v2.chain.ChainStepStatus.AWAITING_REVIEW;
            }
            if (pending != null && pendingTargetsStep(
                    pending.item(), step.id().value())) {
                return io.paperagent.v2.chain.ChainStepStatus.WAITING_GAP;
            }
            return io.paperagent.v2.chain.ChainStepStatus.ACTIVE;
        }
        if (recovery instanceof PersistedStepRecoveryReady ready
                && ready.readyStepId().equals(step.id())) {
            return io.paperagent.v2.chain.ChainStepStatus.READY;
        }
        return io.paperagent.v2.chain.ChainStepStatus.NOT_STARTED;
    }

    private static boolean pendingTargetsStep(
            ChainPersistenceRecords.PendingItemRecord pending,
            String stepId) {
        String canonical = pending.resumePosition().json();
        String escaped = stepId.replace("\\", "\\\\")
                .replace("\"", "\\\"");
        return canonical.contains("\"stepId\":\"" + escaped + "\"");
    }

    private static PublishProjection publishProjection(
            ChainPersistenceRecords.TaskOutcomeRecord outcome) {
        if (outcome == null) {
            return new PublishProjection(null, null, null);
        }
        String projectVersion = normalized(outcome.publishedProjectVersion());
        Long revisionId = outcome.publishedRevisionId();
        String receiptId = normalized(outcome.publishReceiptId());
        int count = (projectVersion == null ? 0 : 1)
                + (revisionId == null ? 0 : 1)
                + (receiptId == null ? 0 : 1);
        if (count != 0 && count != 3) {
            throw new IllegalStateException(
                    "formal publish identity is incomplete");
        }
        return new PublishProjection(projectVersion, revisionId, receiptId);
    }

    private static ChainWorkState workState(
            ChainPersistenceRecords.RouteDecisionRecord route,
            ChainPersistenceRecords.PlanBindingRecord plan,
            PendingProjection pending,
            ChainPersistenceRecords.TaskOutcomeRecord outcome,
            DeliveryProjection delivery,
            List<V2ProjectTurnResponse.Step> steps) {
        if (delivery.status() == ChainDeliveryStatus.SUCCEEDED
                || delivery.status() == ChainDeliveryStatus.DELIVERY_FAILED) {
            return ChainWorkState.TERMINAL;
        }
        if (delivery.status() != null || outcome != null) {
            return ChainWorkState.DELIVERING;
        }
        if (pending != null) {
            if (pending.status()
                    == ChainPendingItemStatus.RESPONSE_RECEIVED) {
                return ChainWorkState.VALIDATING_PENDING_ITEM;
            }
            return pending.item().pendingType()
                    == io.paperagent.v2.chain.ChainPendingItemType.PERMISSION
                    ? ChainWorkState.WAITING_PERMISSION
                    : ChainWorkState.WAITING_USER;
        }
        if (plan != null) {
            if (steps.stream().anyMatch(step -> "AWAITING_REVIEW".equals(
                    step.status()))) {
                return ChainWorkState.AWAITING_REVIEW;
            }
            return ChainWorkState.EXECUTING;
        }
        if (route != null && route.route()
                == io.paperagent.v2.chain.ChainExecutionMode.DIRECT) {
            return ChainWorkState.DIRECT_ANSWERING;
        }
        return ChainWorkState.PLANNING;
    }

    private ChainPendingItemStatus pendingStatus(
            ChainPersistenceRecords.PendingItemRecord item) {
        return workflow.findPendingItemEvents(item.gapId()).stream()
                .max(Comparator.comparingLong(event -> foundations
                        .findAuthorityEvents(item.taskId(), Long.MAX_VALUE)
                        .stream().filter(authority -> authority.eventId()
                                .equals(event.eventId()))
                        .mapToLong(ChainPersistenceRecords.AuthorityEventRecord
                                ::eventSequence).findFirst().orElse(0L)))
                .map(ChainPersistenceRecords.PendingItemEventRecord::eventKind)
                .orElse(ChainPendingItemStatus.PENDING);
    }

    private String sourceQuestion(ChainPersistenceRecords.TaskRecord task) {
        ChainPersistenceRecords.InstructionRecord instruction = foundations
                .findInstruction(task.sourceInstructionId())
                .orElseThrow(() -> new IllegalStateException(
                        "task source instruction is missing"));
        if (instruction.messageId() == null) {
            return "";
        }
        AgentMessage message = messages.findById(instruction.messageId())
                .filter(value -> value.getUserId() == task.userId()
                        && value.getSessionId() == task.sessionId())
                .orElseThrow(() -> new IllegalStateException(
                        "task source message is missing"));
        return message.getContent();
    }

    private V2NaturalLanguageTurnResponse startResponse(
            ChainPersistenceRecords.CommandRecord command,
            boolean replayed) {
        if (command.status() != ChainCommandStatus.COMMITTED) {
            throw new IllegalStateException(
                    "chain command has no committed public cut");
        }
        ChainPersistenceRecords.TaskRecord task = foundations
                .findTask(command.resultTaskId()).orElseThrow();
        Projection projection = project(task);
        Long assistantMessageId = finalization.findDeliveries(task.taskId())
                .stream().reduce((left, right) -> right)
                .map(ChainPersistenceRecords.DeliveryRecord
                        ::assistantMessageId).orElse(null);
        return new V2NaturalLanguageTurnResponse(
                command.sessionId(), command.turnId(),
                command.userMessageId(), assistantMessageId,
                command.clientRequestId(), projection.value().route(),
                projection.value().finalText(), projection.value().planId(),
                replayed, task.rootClientRequestId());
    }

    private V2TurnCommandResponse commandResponse(
            ChainPersistenceRecords.CommandRecord command,
            boolean replayed) {
        if (command.status() != ChainCommandStatus.COMMITTED) {
            throw new IllegalStateException(
                    "chain command has no committed public cut");
        }
        ChainPersistenceRecords.TaskRecord task = foundations
                .findTask(command.resultTaskId()).orElseThrow();
        ChainPersistenceRecords.TaskOutcomeRecord outcome = finalization
                .findTaskOutcome(task.taskId()).orElse(null);
        String gapStatus = command.gapId() == null ? null : workflow
                .findPendingItems(task.taskId()).stream()
                .filter(item -> item.gapId().equals(command.gapId()))
                .findFirst().map(this::pendingStatus).map(Enum::name)
                .orElse(null);
        return new V2TurnCommandResponse(
                task.rootClientRequestId(), command.clientRequestId(),
                command.resultInstructionId(), gapStatus,
                outcome == null ? null : outcome.outcomeType().name(),
                replayed);
    }

    private void logCommitted(ChainPersistenceRecords.CommandRecord command) {
        safeLogger.info(new ProjectChainSafeLogger.SafeEvent(
                        command.clientRequestId(), command.commandId(),
                        command.resultTaskId(), null, null, null,
                        null, null, command.userMessageId() == null ? 0 : 1,
                        0, 0, null, "COMMITTED", 0,
                        null, null, null),
                new ProjectChainSafeLogger.SensitiveBodies(
                        null, null, null, null,
                        null, null, null, null));
    }

    private ChainPersistenceRecords.TaskRecord rootTask(
            long userId, long sessionId, String rootClientRequestId) {
        ChainPersistenceRecords.TaskRecord task = targetTask(
                userId, sessionId, rootClientRequestId);
        if (!task.rootClientRequestId().equals(rootClientRequestId)) {
            throw notFound("CHAIN_TARGET_NOT_FOUND");
        }
        return task;
    }

    private ChainPersistenceRecords.TaskRecord targetTask(
            long userId, long sessionId, String targetClientRequestId) {
        ChainPersistenceRecords.CommandRecord command = foundations
                .findCommand(userId, sessionId, targetClientRequestId)
                .filter(value -> value.status()
                        == ChainCommandStatus.COMMITTED)
                .orElseThrow(() -> notFound("CHAIN_TARGET_NOT_FOUND"));
        ChainPersistenceRecords.TaskRecord task = foundations
                .findTask(command.resultTaskId())
                .orElseThrow(() -> notFound("CHAIN_TARGET_NOT_FOUND"));
        if (task.userId() != userId || task.sessionId() != sessionId) {
            throw notFound("CHAIN_TARGET_NOT_FOUND");
        }
        if (!task.rootClientRequestId().equals(targetClientRequestId)) {
            throw notFound("CHAIN_TARGET_NOT_FOUND");
        }
        return task;
    }

    private AgentSession ownedProjectSession(long userId, long sessionId) {
        AgentSession session = sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> notFound("CHAIN_TARGET_NOT_FOUND"));
        if (session.getScope() != AgentSessionScope.PROJECT
                || session.getProjectId() == null) {
            throw notFound("CHAIN_TARGET_NOT_FOUND");
        }
        return session;
    }

    private ChainPersistenceRecords.InstructionRecord currentInstruction(
            String taskId) {
        return foundations.findTaskInstructions(taskId, Long.MAX_VALUE)
                .stream().max(Comparator.comparingLong(
                        ChainPersistenceRecords.TaskInstructionBindingRecord
                                ::taskInstructionSequence))
                .flatMap(binding -> foundations.findInstruction(
                        binding.instructionId()))
                .orElseThrow(() -> new IllegalStateException(
                        "task instruction chain is empty"));
    }

    private static void requireReplay(
            ChainPersistenceRecords.CommandRecord command,
            ChainInstructionRelation kind, String targetTaskId,
            String targetClientRequestId, String gapId, String digest) {
        requireSameRequest(command, kind, targetTaskId,
                targetClientRequestId, gapId, digest);
        if (command.status() != ChainCommandStatus.COMMITTED) {
            throw conflict("CHAIN_COMMAND_ID_CONFLICT");
        }
    }

    private static void requireSameRequest(
            ChainPersistenceRecords.CommandRecord command,
            ChainInstructionRelation kind, String targetTaskId,
            String targetClientRequestId, String gapId, String digest) {
        if (command.commandKind() != kind
                || !Objects.equals(command.targetTaskId(), targetTaskId)
                || !Objects.equals(command.targetClientRequestId(),
                targetClientRequestId)
                || !Objects.equals(command.gapId(), gapId)
                || !command.requestSha256().equals(digest)) {
            throw conflict("CHAIN_COMMAND_ID_CONFLICT");
        }
    }

    private static ChainInstructionRelation instructionKind(String value) {
        if (value == null || value.isBlank()) {
            return ChainInstructionRelation.INITIAL;
        }
        try {
            return ChainInstructionRelation.valueOf(value.trim());
        } catch (IllegalArgumentException invalid) {
            throw badRequest("CHAIN_INSTRUCTION_KIND_INVALID");
        }
    }

    private static String commandDigest(
            ChainInstructionRelation kind,
            String targetClientRequestId, String gapId,
            String content, Boolean ragDisabled, String skillId) {
        return sha256(kind.name() + "\0"
                + Objects.toString(targetClientRequestId, ChainIdentity.NONE)
                + "\0" + Objects.toString(gapId, ChainIdentity.NONE)
                + "\0" + (content == null
                ? ChainIdentity.NONE : sha256(content))
                + "\0" + Objects.toString(ragDisabled, "DEFAULT")
                + "\0" + Objects.toString(skillId, ChainIdentity.NONE));
    }

    private static ChainPersistenceRecords.CanonicalJson canonical(
            String json) {
        return new ChainPersistenceRecords.CanonicalJson(
                1, sha256(json), json);
    }

    private static String identity(String prefix, String material) {
        return prefix + "." + sha256(material);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", impossible);
        }
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException(
                "operation is outside this command boundary");
    }

    private static ProjectChainApiException badRequest(String code) {
        return new ProjectChainApiException(HttpStatus.BAD_REQUEST, code);
    }

    private static ProjectChainApiException notFound(String code) {
        return new ProjectChainApiException(HttpStatus.NOT_FOUND, code);
    }

    private static ProjectChainApiException conflict(String code) {
        return new ProjectChainApiException(HttpStatus.CONFLICT, code);
    }

    private static <T> T last(List<T> values) {
        return values == null || values.isEmpty()
                ? null : values.get(values.size() - 1);
    }

    private record CommandCut(
            String taskId, String eventId, String instructionId) {
    }

    private record StartBegin(
            AgentSession session,
            ChainPersistenceRecords.TaskRecord target,
            ChainPersistenceRecords.CommandRecord command,
            AgentMessage message,
            AgentTurn turn,
            CommandCut cut,
            Instant createdAt,
            boolean replayed) {
    }

    private record Projection(
            V2ProjectTurnResponse value, Instant updatedAt) {
    }

    private record PendingProjection(
            ChainPersistenceRecords.PendingItemRecord item,
            ChainPendingItemStatus status,
            V2ProjectTurnResponse.PendingItem value) {
    }

    private record DeliveryProjection(
            ChainDeliveryStatus status, String finalText, String errorCode) {
    }

    private record CandidateProjection(
            Long artifactId, String fingerprint, List<String> outputPaths) {
        private CandidateProjection {
            outputPaths = List.copyOf(outputPaths);
        }
    }

    private record PublishProjection(
            String projectVersion, Long revisionId, String receiptId) {
    }
}
