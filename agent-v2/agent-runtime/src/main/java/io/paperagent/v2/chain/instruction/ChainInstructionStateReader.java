package io.paperagent.v2.chain.instruction;

import io.paperagent.v2.chain.ChainExecutionMode;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainInstructionRelation;
import io.paperagent.v2.chain.ChainPendingItemStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainWorkflowRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Rebuilds the instruction gate exclusively from immutable authority records. */
public final class ChainInstructionStateReader {
    private static final long UNBOUNDED_SEQUENCE_CUT = Long.MAX_VALUE;

    private final ChainFoundationRepository foundations;
    private final ChainWorkflowRepository workflow;
    private final ChainFinalizationRepository finalization;

    public ChainInstructionStateReader(
            ChainFoundationRepository foundations,
            ChainWorkflowRepository workflow,
            ChainFinalizationRepository finalization) {
        this.foundations = Objects.requireNonNull(foundations, "foundations");
        this.workflow = Objects.requireNonNull(workflow, "workflow");
        this.finalization = Objects.requireNonNull(finalization, "finalization");
    }

    public ChainInstructionState read(String taskId) {
        return read(taskId, UNBOUNDED_SEQUENCE_CUT);
    }

    public ChainInstructionState read(String taskId, long sequenceCut) {
        required(taskId, "taskId");
        if (sequenceCut < 0) {
            throw new IllegalArgumentException("sequenceCut must not be negative");
        }
        ChainPersistenceRecords.TaskRecord task = foundations.findTask(taskId)
                .orElseThrow(() -> failure(
                        ChainInstructionException.Code.TASK_NOT_FOUND,
                        "task does not exist: " + taskId));
        List<ChainPersistenceRecords.TaskInstructionBindingRecord> bindings =
                foundations.findTaskInstructions(taskId, sequenceCut).stream()
                        .sorted(Comparator.comparingLong(
                                ChainPersistenceRecords.TaskInstructionBindingRecord::taskInstructionSequence))
                        .toList();
        List<ChainPersistenceRecords.AuthorityEventRecord> authorityEvents =
                foundations.findAuthorityEvents(taskId,
                        foundations.highestAuthorityEventSequence(taskId));
        List<ChainInstructionState.Entry> entries = validateEntries(
                task, bindings, authorityEvents, foundations);
        if (entries.isEmpty()) {
            throw failure(ChainInstructionException.Code.INSTRUCTION_CHAIN_INVALID,
                    "a task must have at least one bound instruction");
        }

        ChainPersistenceRecords.InstructionRecord current =
                entries.get(entries.size() - 1).instruction();
        GateDecision decision = gate(
                taskId, current,
                AuthorityOrder.from(taskId, authorityEvents));
        return new ChainInstructionState(taskId,
                entries.get(entries.size() - 1).binding().taskInstructionSequence(),
                entries, decision.gate(), decision.authorityRef());
    }

    static List<ChainInstructionState.Entry> validateEntries(
            ChainPersistenceRecords.TaskRecord task,
            List<ChainPersistenceRecords.TaskInstructionBindingRecord> bindings,
            List<ChainPersistenceRecords.AuthorityEventRecord> events,
            ChainFoundationRepository foundations) {
        Map<String, ChainPersistenceRecords.AuthorityEventRecord> eventById = new HashMap<>();
        Set<Long> eventSequences = new HashSet<>();
        for (ChainPersistenceRecords.AuthorityEventRecord event : events) {
            if (!task.taskId().equals(event.taskId())
                    || eventById.put(event.eventId(), event) != null
                    || !eventSequences.add(event.eventSequence())) {
                throw failure(ChainInstructionException.Code.INSTRUCTION_CHAIN_INVALID,
                        "task authority event identities and sequences must be unique");
            }
        }
        Set<String> instructionIds = new HashSet<>();
        List<ChainInstructionState.Entry> entries = new ArrayList<>(bindings.size());
        ChainPersistenceRecords.InstructionRecord previous = null;
        long previousAuthoritySequence = 0;
        for (int index = 0; index < bindings.size(); index++) {
            ChainPersistenceRecords.TaskInstructionBindingRecord binding = bindings.get(index);
            long expectedSequence = index + 1L;
            if (!task.taskId().equals(binding.taskId())
                    || binding.taskInstructionSequence() != expectedSequence
                    || !instructionIds.add(binding.instructionId())) {
                throw failure(ChainInstructionException.Code.INSTRUCTION_CHAIN_INVALID,
                        "instruction bindings must be unique and contiguous from sequence one");
            }
            ChainPersistenceRecords.AuthorityEventRecord event = eventById.get(binding.eventId());
            if (event == null || !task.taskId().equals(event.taskId())
                    || !"INSTRUCTION_BOUND".equals(event.eventType())
                    || event.eventSequence() <= previousAuthoritySequence) {
                throw failure(ChainInstructionException.Code.INSTRUCTION_CHAIN_INVALID,
                        "instruction bindings require an ordered INSTRUCTION_BOUND event prefix");
            }
            ChainPersistenceRecords.InstructionRecord instruction = foundations
                    .findInstruction(binding.instructionId())
                    .orElseThrow(() -> failure(
                            ChainInstructionException.Code.INSTRUCTION_CHAIN_INVALID,
                            "bound instruction is missing: " + binding.instructionId()));
            validateInstruction(task, binding, instruction, previous, index == 0);
            entries.add(new ChainInstructionState.Entry(binding, instruction));
            previous = instruction;
            previousAuthoritySequence = event.eventSequence();
        }
        return List.copyOf(entries);
    }

    private GateDecision gate(
            String taskId,
            ChainPersistenceRecords.InstructionRecord current,
            AuthorityOrder authority) {
        var outcome = finalization.findTaskOutcome(taskId);
        if (outcome.isPresent()) {
            ChainPersistenceRecords.TaskOutcomeRecord terminal = outcome.get();
            if (!taskId.equals(terminal.taskId())) {
                throw failure(ChainInstructionException.Code.INSTRUCTION_CHAIN_INVALID,
                        "task outcome belongs to another task");
            }
            authority.sequence(terminal, "TASK_OUTCOME", false);
            ChainInstructionState.Gate gate = switch (terminal.outcomeType()) {
                case CANCELLED -> ChainInstructionState.Gate.CANCELLED;
                case SUPERSEDED -> ChainInstructionState.Gate.SUPERSEDED;
                case COMPLETED, FAILED -> ChainInstructionState.Gate.TERMINAL;
            };
            return new GateDecision(gate, terminal.outcomeId());
        }
        if (current.relationKind() == ChainInstructionRelation.CANCEL) {
            return new GateDecision(ChainInstructionState.Gate.CANCELLED,
                    current.instructionId());
        }
        GateDecision pendingGate = pendingGate(taskId, authority);
        if (pendingGate != null) {
            return pendingGate;
        }
        if (current.relationKind() == ChainInstructionRelation.ANSWER_TO_PENDING_ITEM) {
            List<ChainPersistenceRecords.PendingItemEventRecord> pendingEvents = workflow
                    .findPendingItemEvents(current.answeredGapId());
            pendingEvents.forEach(event -> authority.sequence(
                    event, "PENDING_ITEM_" + event.eventKind().name(), false));
            List<ChainPersistenceRecords.PendingItemEventRecord> events = pendingEvents.stream()
                    .sorted(Comparator.comparingLong(event -> authority.sequence(
                            event, "PENDING_ITEM_" + event.eventKind().name(), false)))
                    .toList();
            if (events.isEmpty()
                    || events.get(events.size() - 1).eventKind()
                    != ChainPendingItemStatus.RESOLVED) {
                String ref = events.isEmpty() ? current.answeredGapId()
                        : events.get(events.size() - 1).eventId();
                return new GateDecision(
                        ChainInstructionState.Gate.PAUSED_FOR_PENDING_VALIDATION, ref);
            }
            return executionGate(taskId, null,
                    authority,
                    events.get(events.size() - 1).eventId());
        }
        if (current.relationKind() != ChainInstructionRelation.INITIAL
                && !hasSuccessorFor(
                taskId, current.instructionId(), authority)) {
            return new GateDecision(
                    ChainInstructionState.Gate.PAUSED_FOR_DISPOSITION,
                    current.instructionId());
        }
        return executionGate(
                taskId, current.instructionId(), authority,
                current.instructionId());
    }

    private GateDecision pendingGate(
            String taskId,
            AuthorityOrder authority) {
        List<ChainPersistenceRecords.PendingItemRecord> items =
                workflow.findPendingItems(taskId);
        Set<String> gapIds = new HashSet<>();
        List<BlockingGap> blocking = new ArrayList<>();
        boolean duplicateGap = false;
        for (ChainPersistenceRecords.PendingItemRecord item : items) {
            long itemSequence = authority.sequence(
                    item, "PENDING_ITEM", false);
            if (!gapIds.add(item.gapId())) {
                duplicateGap = true;
                continue;
            }
            List<ChainPersistenceRecords.PendingItemEventRecord> events = workflow
                    .findPendingItemEvents(item.gapId());
            for (ChainPersistenceRecords.PendingItemEventRecord event : events) {
                if (!item.gapId().equals(event.gapId())) {
                    throw failure(
                            ChainInstructionException.Code.INSTRUCTION_CHAIN_INVALID,
                            "PendingItem event belongs to another gap");
                }
                long eventSequence = authority.sequence(
                        event,
                        "PENDING_ITEM_" + event.eventKind().name(),
                        false);
                if (eventSequence <= itemSequence) {
                    throw failure(
                            ChainInstructionException.Code.INSTRUCTION_CHAIN_INVALID,
                            "PendingItem event must follow its gap authority");
                }
            }
            ChainPersistenceRecords.PendingItemEventRecord latest = events.stream()
                    .max(Comparator.comparingLong(event -> authority.sequence(
                            event,
                            "PENDING_ITEM_" + event.eventKind().name(),
                            false)))
                    .orElse(null);
            ChainPendingItemStatus status = latest == null
                    ? ChainPendingItemStatus.PENDING
                    : latest.eventKind();
            boolean permissionGap = item.pendingType()
                    == io.paperagent.v2.chain.ChainPendingItemType.PERMISSION;
            if (permissionGap || status != ChainPendingItemStatus.RESOLVED) {
                blocking.add(new BlockingGap(
                        latest == null ? item.gapId() : latest.eventId()));
            }
        }
        if (duplicateGap || blocking.size() > 1) {
            return new GateDecision(
                    ChainInstructionState.Gate.PAUSED_FOR_PENDING_VALIDATION,
                    taskId);
        }
        if (blocking.size() == 1) {
            return new GateDecision(
                    ChainInstructionState.Gate.PAUSED_FOR_PENDING_VALIDATION,
                    blocking.get(0).authorityRef());
        }
        return null;
    }

    private boolean hasSuccessorFor(
            String taskId,
            String instructionId,
            AuthorityOrder authority) {
        List<ChainPersistenceRecords.RouteDecisionRecord> routes =
                workflow.findRouteDecisions(taskId);
        routes.forEach(route -> authority.sequence(
                route, "ROUTE_DECISION", false));
        List<ChainPersistenceRecords.PlanBindingRecord> plans =
                workflow.findPlanBindings(taskId);
        plans.forEach(plan -> authority.sequence(
                plan, "PLAN_BINDING", false));
        List<ChainPersistenceRecords.InstructionDispositionRecord> dispositions =
                workflow.findInstructionDispositions(taskId);
        dispositions.forEach(disposition -> authority.sequence(
                disposition, "INSTRUCTION_DISPOSITION", false));
        return routes.stream()
                        .filter(route -> instructionId.equals(route.instructionId()))
                        .findAny().isPresent()
                || plans.stream()
                        .filter(plan -> instructionId.equals(plan.instructionId()))
                        .findAny().isPresent()
                || dispositions.stream()
                        .filter(disposition -> instructionId.equals(disposition.instructionId()))
                        .findAny().isPresent();
    }

    private GateDecision executionGate(
            String taskId,
            String instructionId,
            AuthorityOrder authority,
            String fallbackAuthorityRef) {
        List<ChainPersistenceRecords.PlanBindingRecord> allPlans =
                workflow.findPlanBindings(taskId);
        allPlans.forEach(plan -> authority.sequence(
                plan, "PLAN_BINDING", false));
        List<ChainPersistenceRecords.PlanBindingRecord> plans = allPlans.stream()
                .filter(plan -> instructionId == null
                        || instructionId.equals(plan.instructionId()))
                .sorted(Comparator.comparingLong(plan -> authority.sequence(
                        plan, "PLAN_BINDING", false)))
                .toList();
        if (!plans.isEmpty()) {
            return new GateDecision(ChainInstructionState.Gate.SIDE_EFFECTS_ALLOWED,
                    plans.get(plans.size() - 1).planBindingId());
        }
        List<ChainPersistenceRecords.RouteDecisionRecord> allRoutes = workflow
                .findRouteDecisions(taskId);
        allRoutes.forEach(route -> authority.sequence(
                route, "ROUTE_DECISION", false));
        List<ChainPersistenceRecords.RouteDecisionRecord> routes = allRoutes.stream()
                .filter(route -> instructionId == null
                        || instructionId.equals(route.instructionId()))
                .sorted(Comparator.comparingLong(route -> authority.sequence(
                        route, "ROUTE_DECISION", false)))
                .toList();
        if (!routes.isEmpty()) {
            ChainPersistenceRecords.RouteDecisionRecord route = routes.get(routes.size() - 1);
            return route.route() == ChainExecutionMode.DIRECT
                    ? new GateDecision(ChainInstructionState.Gate.DIRECT_ANSWER,
                    route.routeDecisionId())
                    : new GateDecision(ChainInstructionState.Gate.PLANNING,
                    route.routeDecisionId());
        }
        List<ChainPersistenceRecords.InstructionDispositionRecord> dispositions =
                workflow.findInstructionDispositions(taskId).stream()
                        .filter(value -> instructionId == null
                                || instructionId.equals(value.instructionId()))
                        .sorted(Comparator.comparingLong(value -> authority.sequence(
                                value, "INSTRUCTION_DISPOSITION", false)))
                        .toList();
        if (!dispositions.isEmpty()) {
            ChainPersistenceRecords.InstructionDispositionRecord disposition =
                    dispositions.get(dispositions.size() - 1);
            if (disposition.replyRequired()) {
                return new GateDecision(ChainInstructionState.Gate.DIRECT_ANSWER,
                        disposition.dispositionId());
            }
        }
        return new GateDecision(ChainInstructionState.Gate.PLANNING,
                fallbackAuthorityRef);
    }

    private static void validateInstruction(
            ChainPersistenceRecords.TaskRecord task,
            ChainPersistenceRecords.TaskInstructionBindingRecord binding,
            ChainPersistenceRecords.InstructionRecord instruction,
            ChainPersistenceRecords.InstructionRecord previous,
            boolean first) {
        if (instruction.sessionId() != task.sessionId()) {
            throw failure(ChainInstructionException.Code.INSTRUCTION_CHAIN_INVALID,
                    "instruction session does not match its task");
        }
        if (binding.relationRole() == ChainPersistenceRecords.BindingRole.ORIGIN
                && !task.taskId().equals(instruction.originTaskId())) {
            throw failure(ChainInstructionException.Code.INSTRUCTION_CHAIN_INVALID,
                    "ORIGIN instruction must originate from the bound task");
        }
        boolean answersGap = instruction.relationKind()
                == ChainInstructionRelation.ANSWER_TO_PENDING_ITEM;
        if (answersGap != (instruction.answeredGapId() != null)) {
            throw failure(ChainInstructionException.Code.INSTRUCTION_CHAIN_INVALID,
                    "answeredGapId must match ANSWER_TO_PENDING_ITEM relation");
        }
        if (first) {
            if (binding.relationRole() == ChainPersistenceRecords.BindingRole.ORIGIN
                    && instruction.relationKind() != ChainInstructionRelation.INITIAL) {
                throw failure(ChainInstructionException.Code.INSTRUCTION_CHAIN_INVALID,
                        "the first ORIGIN binding must be INITIAL");
            }
            if (instruction.parentInstructionId() != null
                    && binding.relationRole() == ChainPersistenceRecords.BindingRole.ORIGIN) {
                throw failure(ChainInstructionException.Code.INSTRUCTION_CHAIN_INVALID,
                        "the first ORIGIN instruction cannot have a parent");
            }
        } else {
            if (binding.relationRole() != ChainPersistenceRecords.BindingRole.ORIGIN
                    || instruction.relationKind() == ChainInstructionRelation.INITIAL
                    || !previous.instructionId().equals(instruction.parentInstructionId())) {
                throw failure(ChainInstructionException.Code.INSTRUCTION_CHAIN_INVALID,
                        "new ORIGIN instructions must extend the current immutable head");
            }
        }
    }

    private record GateDecision(ChainInstructionState.Gate gate, String authorityRef) {
    }

    private record BlockingGap(String authorityRef) {
    }

    private record AuthorityOrder(
            String taskId,
            Map<String, ChainPersistenceRecords.AuthorityEventRecord> byId) {
        static AuthorityOrder from(
                String taskId,
                List<ChainPersistenceRecords.AuthorityEventRecord> events) {
            Map<String, ChainPersistenceRecords.AuthorityEventRecord> byId =
                    new HashMap<>();
            Set<Long> sequences = new HashSet<>();
            for (ChainPersistenceRecords.AuthorityEventRecord event : events) {
                if (!taskId.equals(event.taskId())
                        || byId.put(event.eventId(), event) != null
                        || !sequences.add(event.eventSequence())) {
                    throw failure(
                            ChainInstructionException.Code.INSTRUCTION_CHAIN_INVALID,
                            "task authority event prefix is inconsistent");
                }
            }
            return new AuthorityOrder(taskId, Map.copyOf(byId));
        }

        long sequence(
                ChainPersistenceRecords.TaskAuthorityFact fact,
                String expectedType,
                boolean prefixMatch) {
            ChainPersistenceRecords.AuthorityEventRecord event = byId.get(
                    fact.eventId());
            boolean typeMatches = event != null && (prefixMatch
                    ? event.eventType().startsWith(expectedType)
                    : event.eventType().equals(expectedType));
            if (!taskId.equals(fact.taskId())
                    || event == null
                    || !taskId.equals(event.taskId())
                    || !typeMatches) {
                throw failure(
                        ChainInstructionException.Code.INSTRUCTION_CHAIN_INVALID,
                        "workflow fact lacks its ordered formal authority event");
            }
            return event.eventSequence();
        }
    }

    private static ChainInstructionException failure(
            ChainInstructionException.Code code, String message) {
        return new ChainInstructionException(code, message);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
