package com.yanban.api.agent.v2.chain.recovery;

import io.paperagent.v2.chain.ChainInstructionRelation;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Reads a complete, still-RECEIVED command cut without advancing it. */
@Component
public final class ProductChainReceivedCommandSource {
    private static final int MAX_SCAN_LIMIT = 100;

    private final NamedParameterJdbcTemplate jdbc;

    public ProductChainReceivedCommandSource(
            NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public Optional<ReceivedCommand> findExact(
            long userId,
            long sessionId,
            String clientRequestId,
            String requestSha256) {
        positive(userId, "userId");
        positive(sessionId, "sessionId");
        required(clientRequestId, "clientRequestId");
        sha256Required(requestSha256, "requestSha256");
        Optional<CommandRow> found = one(commandRows("""
                SELECT * FROM agent_v2_chain_commands
                 WHERE user_id = :userId
                   AND session_id = :sessionId
                   AND client_request_id = :clientRequestId
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("sessionId", sessionId)
                .addValue("clientRequestId", clientRequestId)),
                "received command request authority is ambiguous");
        if (found.isEmpty()) return Optional.empty();
        CommandRow command = found.orElseThrow();
        require(command.userId() == userId
                        && command.sessionId() == sessionId
                        && command.clientRequestId().equals(clientRequestId)
                        && command.requestSha256().equals(requestSha256),
                "received command request digest changed");
        return received(command);
    }

    public Optional<ReceivedCommand> findByCommandId(String commandId) {
        required(commandId, "commandId");
        Optional<CommandRow> found = one(commandRows("""
                SELECT * FROM agent_v2_chain_commands
                 WHERE command_id = :commandId
                """, new MapSqlParameterSource(
                "commandId", commandId)),
                "received command identity is ambiguous");
        if (found.isEmpty()) return Optional.empty();
        require(found.orElseThrow().commandId().equals(commandId),
                "received command identity changed");
        return received(found.orElseThrow());
    }

    public ScanPage scan(ScanCursor after, int limit) {
        if (limit < 1) throw new IllegalArgumentException(
                "limit must be positive");
        int bounded = Math.min(limit, MAX_SCAN_LIMIT);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("limit", bounded + 1);
        String cursorPredicate = "";
        if (after != null) {
            parameters.addValue("afterCreatedAt", after.createdAt())
                    .addValue("afterCommandId", after.commandId());
            cursorPredicate = """
                       AND (created_at > :afterCreatedAt
                            OR (created_at = :afterCreatedAt
                                AND command_id > :afterCommandId))
                    """;
        }
        List<ScanCandidate> candidates = jdbc.queryForList("""
                SELECT command_id, created_at
                  FROM agent_v2_chain_commands
                 WHERE status = 'RECEIVED'
                   AND result_task_id IS NULL
                   AND result_event_id IS NULL
                   AND result_instruction_id IS NULL
                   AND result_code IS NULL
                   AND committed_at IS NULL
                """ + cursorPredicate + """
                 ORDER BY created_at, command_id
                 LIMIT :limit
                """, parameters).stream().map(row -> new ScanCandidate(
                        text(row, "command_id"),
                        instant(row.get("created_at")))).toList();
        boolean hasMore = candidates.size() > bounded;
        List<ScanCandidate> pageCandidates = hasMore
                ? candidates.subList(0, bounded) : candidates;
        List<ScanResult> result = new ArrayList<>();
        for (ScanCandidate candidate : pageCandidates) {
            String commandId = candidate.commandId();
            try {
                Optional<ReceivedCommand> command = findByCommandId(commandId);
                result.add(command.<ScanResult>map(Ready::new)
                        .orElseGet(() -> new NoLongerReceived(commandId)));
            } catch (RuntimeException blocked) {
                result.add(new Blocked(commandId,
                        "CHAIN_RECEIVED_COMMAND_READ_BLOCKED",
                        Objects.toString(blocked.getMessage(),
                                "received command authority is invalid")));
            }
        }
        ScanCursor nextCursor = pageCandidates.isEmpty() ? after
                : new ScanCursor(pageCandidates.get(
                pageCandidates.size() - 1).createdAt(),
                pageCandidates.get(pageCandidates.size() - 1).commandId());
        return new ScanPage(result, nextCursor, hasMore);
    }

    private Optional<ReceivedCommand> received(CommandRow command) {
        verifyCommandShape(command);
        if (!"RECEIVED".equals(command.status())) return Optional.empty();
        require(command.resultTaskId() == null
                        && command.resultEventId() == null
                        && command.resultInstructionId() == null
                        && command.resultCode() == null
                        && command.committedAt() == null,
                "RECEIVED command carries terminal state");
        require(command.turnId() != null && command.turnId() > 0
                        && command.messageId() != null
                        && command.messageId() > 0,
                "recoverable command lacks turn or message identity");

        List<TaskRow> created = taskRows("""
                SELECT * FROM agent_v2_chain_tasks
                 WHERE created_by_command_id = :commandId
                """, new MapSqlParameterSource(
                "commandId", command.commandId()));
        require(created.size() <= 1,
                "received command created ambiguous tasks");
        boolean createdByCommand = !created.isEmpty();
        TaskRow task;
        if (createdByCommand) {
            task = created.get(0);
            verifyCreatedTaskBoundary(command, task);
        } else {
            require(command.commandKind()
                            == ChainInstructionRelation.SUPPLEMENT
                            || command.commandKind()
                            == ChainInstructionRelation.CORRECTION,
                    "received command has no recoverable task authority");
            required(command.targetTaskId(), "targetTaskId");
            task = one(taskRows("""
                    SELECT * FROM agent_v2_chain_tasks
                     WHERE task_id = :taskId
                    """, new MapSqlParameterSource(
                    "taskId", command.targetTaskId())),
                    "received command target task authority is ambiguous")
                    .orElseThrow(() -> new IllegalStateException(
                            "received command target task is missing"));
            require(command.targetClientRequestId().equals(
                            task.rootClientRequestId()),
                    "received command target root identity changed");
        }
        verifyTaskOwner(command, task);
        verifyRoot(task, command, createdByCommand);

        InstructionRow instruction = one(instructionRows("""
                SELECT instruction.*, binding.event_id AS binding_event_id,
                       binding.relation_role,
                       binding.task_instruction_sequence,
                       event.event_type
                  FROM agent_v2_chain_instructions instruction
                  JOIN agent_v2_chain_task_instruction_bindings binding
                    ON binding.instruction_id = instruction.instruction_id
                   AND binding.task_id = :taskId
                  JOIN agent_v2_chain_authority_events event
                    ON event.task_id = binding.task_id
                   AND event.event_id = binding.event_id
                 WHERE instruction.command_id = :commandId
                """, new MapSqlParameterSource()
                .addValue("commandId", command.commandId())
                .addValue("taskId", task.taskId())),
                "received command instruction authority is ambiguous")
                .orElseThrow(() -> new IllegalStateException(
                        "received command instruction is missing"));
        require(instruction.sessionId() == command.sessionId()
                        && instruction.relationKind() == command.commandKind()
                        && Objects.equals(instruction.messageId(),
                        command.messageId())
                        && "INSTRUCTION_BOUND".equals(
                        instruction.bindingEventType()),
                "received command instruction identity changed");
        boolean dispositionReplacement = verifyInstructionBinding(
                command, task, instruction, createdByCommand);
        verifyConversation(command, instruction);
        ChainInstructionRelation progressionRelation =
                dispositionReplacement
                        || command.commandKind()
                        == ChainInstructionRelation.REPLACEMENT
                        ? ChainInstructionRelation.INITIAL
                        : command.commandKind();

        return Optional.of(new ReceivedCommand(
                command.commandId(), task.taskId(),
                instruction.instructionId(), instruction.bindingEventId(),
                command.userId(), command.sessionId(),
                command.clientRequestId(), command.requestSha256(),
                command.commandKind(), progressionRelation,
                command.turnId(), command.messageId(),
                task.rootClientRequestId(), task.rootRequestSha256(),
                command.targetTaskId(), command.targetClientRequestId()));
    }

    private boolean verifyInstructionBinding(
            CommandRow command,
            TaskRow task,
            InstructionRow instruction,
            boolean createdByCommand) {
        require(!createdByCommand
                        || task.sourceInstructionId().equals(
                        instruction.instructionId()),
                "created task source instruction changed");
        if ("ORIGIN".equals(instruction.bindingRole())) {
            require(!createdByCommand
                            || command.commandKind()
                            == ChainInstructionRelation.INITIAL
                            || command.commandKind()
                            == ChainInstructionRelation.REPLACEMENT,
                    "created task has no formal intake relation");
            require(instruction.originTaskId().equals(task.taskId()),
                    "received command instruction origin changed");
            if (createdByCommand
                    && command.commandKind()
                    == ChainInstructionRelation.INITIAL) {
                require(instruction.bindingSequence() == 1L,
                        "initial task intake binding changed");
                require(bindingCount(task.taskId()) == 1L,
                        "initial task has additional instruction bindings");
            } else if (createdByCommand) {
                verifyExplicitReplacement(command, task, instruction);
            } else {
                requireCurrentInstruction(task.taskId(),
                        instruction.instructionId(),
                        "received command instruction is not current");
            }
            return false;
        }
        require(createdByCommand
                        && task.predecessorTaskId() != null
                        && "INHERITED_ROOT".equals(
                        instruction.bindingRole())
                        && instruction.bindingSequence() == 1L
                        && (command.commandKind()
                        == ChainInstructionRelation.SUPPLEMENT
                        || command.commandKind()
                        == ChainInstructionRelation.CORRECTION)
                        && instruction.originTaskId().equals(
                        task.predecessorTaskId()),
                "received command instruction binding changed");
        Long replacementBindingCount = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM agent_v2_chain_task_instruction_bindings
                 WHERE task_id = :taskId
                """, Map.of("taskId", task.taskId()), Long.class);
        require(replacementBindingCount != null
                        && replacementBindingCount == 1L,
                "boundary replacement bindings changed");
        required(instruction.parentInstructionId(), "parentInstructionId");
        List<Map<String, Object>> dispositions = jdbc.queryForList("""
                SELECT disposition.disposition_id,
                       disposition.boundary_changed,
                       event.event_type
                  FROM agent_v2_chain_instruction_dispositions disposition
                  JOIN agent_v2_chain_authority_events event
                    ON event.task_id = disposition.task_id
                   AND event.event_id = disposition.event_id
                  JOIN agent_v2_chain_model_proposals proposal
                    ON proposal.task_id = disposition.task_id
                   AND proposal.proposal_id = disposition.proposal_id
                  JOIN agent_v2_chain_model_invocations invocation
                    ON invocation.task_id = proposal.task_id
                   AND invocation.invocation_id = proposal.invocation_id
                  JOIN agent_v2_chain_context_revisions context_revision
                    ON context_revision.task_id = invocation.task_id
                   AND context_revision.context_revision_id =
                       invocation.context_revision_id
                   AND context_revision.instruction_id =
                       disposition.instruction_id
                 WHERE disposition.task_id = :predecessorTaskId
                   AND disposition.instruction_id = :instructionId
                """, new MapSqlParameterSource()
                .addValue("predecessorTaskId", task.predecessorTaskId())
                .addValue("instructionId", instruction.instructionId()));
        Map<String, Object> disposition = one(dispositions,
                "boundary disposition authority is ambiguous")
                .orElseThrow(() -> new IllegalStateException(
                        "boundary disposition authority is missing"));
        require(number(disposition, "boundary_changed") == 1L
                        && "INSTRUCTION_DISPOSITION".equals(
                        text(disposition, "event_type")),
                "boundary disposition authority changed");
        List<String> currentInstructions = jdbc.queryForList("""
                SELECT instruction_id
                  FROM agent_v2_chain_task_instruction_bindings
                 WHERE task_id = :taskId
                 ORDER BY task_instruction_sequence DESC
                 LIMIT 2
                """, Map.of("taskId", task.predecessorTaskId()),
                String.class);
        require(currentInstructions.size() == 2
                        && currentInstructions.get(0).equals(
                        instruction.instructionId())
                        && currentInstructions.get(1).equals(
                        instruction.parentInstructionId()),
                "boundary disposition trigger or old boundary is not current");
        requireSupersededOutcome(command, task.predecessorTaskId(),
                instruction.parentInstructionId(),
                instruction.instructionId());
        return true;
    }

    private long bindingCount(String taskId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM agent_v2_chain_task_instruction_bindings
                 WHERE task_id = :taskId
                """, Map.of("taskId", taskId), Long.class);
        return count == null ? -1L : count;
    }

    private void requireCurrentInstruction(
            String taskId, String instructionId, String message) {
        List<String> current = jdbc.queryForList("""
                SELECT instruction_id
                  FROM agent_v2_chain_task_instruction_bindings
                 WHERE task_id = :taskId
                 ORDER BY task_instruction_sequence DESC
                 LIMIT 1
                """, Map.of("taskId", taskId), String.class);
        require(current.size() == 1 && instructionId.equals(current.get(0)),
                message);
    }

    private void verifyExplicitReplacement(
            CommandRow command,
            TaskRow task,
            InstructionRow instruction) {
        require(instruction.bindingSequence() == 2L,
                "explicit replacement intake binding changed");
        required(task.predecessorTaskId(), "predecessorTaskId");
        required(instruction.parentInstructionId(), "parentInstructionId");
        TaskRow predecessor = one(taskRows("""
                SELECT * FROM agent_v2_chain_tasks
                 WHERE task_id = :taskId
                """, new MapSqlParameterSource(
                "taskId", task.predecessorTaskId())),
                "explicit replacement predecessor is ambiguous")
                .orElseThrow(() -> new IllegalStateException(
                        "explicit replacement predecessor is missing"));
        List<Map<String, Object>> bindings = jdbc.queryForList("""
                SELECT instruction_id, task_instruction_sequence,
                       relation_role
                  FROM agent_v2_chain_task_instruction_bindings
                 WHERE task_id = :taskId
                 ORDER BY task_instruction_sequence
                """, Map.of("taskId", task.taskId()));
        require(bindings.size() == 2
                        && number(bindings.get(0),
                        "task_instruction_sequence") == 1L
                        && predecessor.sourceInstructionId().equals(
                        text(bindings.get(0), "instruction_id"))
                        && "INHERITED_ROOT".equals(
                        text(bindings.get(0), "relation_role"))
                        && number(bindings.get(1),
                        "task_instruction_sequence") == 2L
                        && instruction.instructionId().equals(
                        text(bindings.get(1), "instruction_id"))
                        && "ORIGIN".equals(
                        text(bindings.get(1), "relation_role")),
                "explicit replacement retained bindings changed");
        List<String> oldCurrent = jdbc.queryForList("""
                SELECT instruction_id
                  FROM agent_v2_chain_task_instruction_bindings
                 WHERE task_id = :taskId
                 ORDER BY task_instruction_sequence DESC
                 LIMIT 1
                """, Map.of("taskId", task.predecessorTaskId()),
                String.class);
        require(oldCurrent.size() == 1
                        && oldCurrent.get(0).equals(
                        instruction.parentInstructionId()),
                "explicit replacement parent is not the old boundary");
        requireSupersededOutcome(command, task.predecessorTaskId(),
                instruction.parentInstructionId(),
                instruction.instructionId());
    }

    private void requireSupersededOutcome(
            CommandRow command,
            String predecessorTaskId,
            String oldBoundaryInstructionId,
            String supersedingInstructionId) {
        List<Map<String, Object>> outcomes = jdbc.queryForList("""
                SELECT outcome.source_command_id, outcome.outcome_type,
                       outcome.instruction_id, outcome.source_decision_id,
                       event.event_type
                  FROM agent_v2_chain_task_outcomes outcome
                  JOIN agent_v2_chain_authority_events event
                    ON event.task_id = outcome.task_id
                   AND event.event_id = outcome.event_id
                 WHERE outcome.task_id = :predecessorTaskId
                """, Map.of("predecessorTaskId", predecessorTaskId));
        Map<String, Object> outcome = one(outcomes,
                "boundary replacement outcome authority is ambiguous")
                .orElseThrow(() -> new IllegalStateException(
                        "boundary replacement outcome authority is missing"));
        require(command.commandId().equals(
                        text(outcome, "source_command_id"))
                        && "SUPERSEDED".equals(text(outcome, "outcome_type"))
                        && oldBoundaryInstructionId.equals(
                        text(outcome, "instruction_id"))
                        && supersedingInstructionId.equals(
                        text(outcome, "source_decision_id"))
                        && "TASK_OUTCOME".equals(
                        text(outcome, "event_type")),
                "formal boundary replacement outcome changed");
    }

    private static void verifyCommandShape(CommandRow command) {
        required(command.commandId(), "commandId");
        positive(command.userId(), "userId");
        positive(command.sessionId(), "sessionId");
        required(command.clientRequestId(), "clientRequestId");
        sha256Required(command.requestSha256(), "requestSha256");
        require((command.targetTaskId() == null)
                        == (command.targetClientRequestId() == null),
                "command target identity is partial");
    }

    private void verifyCreatedTaskBoundary(
            CommandRow command, TaskRow task) {
        require(task.createdByCommandId().equals(command.commandId())
                        && task.rootClientRequestId().equals(
                        command.clientRequestId())
                        && task.rootRequestSha256().equals(
                        command.requestSha256())
                        && task.turnId() == command.turnId()
                        && Objects.equals(task.requestMessageId(),
                        command.messageId()),
                "created task command identity changed");
        if (command.targetTaskId() == null) {
            require(task.predecessorTaskId() == null,
                    "root task unexpectedly has a predecessor");
            return;
        }
        require(command.targetTaskId().equals(task.predecessorTaskId()),
                "replacement predecessor identity changed");
        TaskRow predecessor = one(taskRows("""
                SELECT * FROM agent_v2_chain_tasks
                 WHERE task_id = :taskId
                """, new MapSqlParameterSource(
                "taskId", command.targetTaskId())),
                "replacement predecessor authority is ambiguous")
                .orElseThrow(() -> new IllegalStateException(
                        "replacement predecessor is missing"));
        require(predecessor.userId() == command.userId()
                        && predecessor.sessionId() == command.sessionId()
                        && predecessor.rootClientRequestId().equals(
                        command.targetClientRequestId())
                        && Objects.equals(predecessor.projectId(),
                        task.projectId()),
                "replacement predecessor root identity changed");
    }

    private static void verifyTaskOwner(
            CommandRow command, TaskRow task) {
        require(task.userId() == command.userId()
                        && task.sessionId() == command.sessionId(),
                "received command task owner changed");
    }

    private void verifyRoot(
            TaskRow task, CommandRow current, boolean createdByCurrent) {
        CommandRow root = current.commandId().equals(
                task.createdByCommandId()) ? current : one(commandRows("""
                SELECT * FROM agent_v2_chain_commands
                 WHERE command_id = :commandId
                """, new MapSqlParameterSource(
                "commandId", task.createdByCommandId())),
                "root command authority is ambiguous").orElseThrow(() ->
                new IllegalStateException("root command is missing"));
        require(root.userId() == task.userId()
                        && root.sessionId() == task.sessionId()
                        && root.clientRequestId().equals(
                        task.rootClientRequestId())
                        && root.requestSha256().equals(task.rootRequestSha256())
                        && Objects.equals(root.turnId(), task.turnId())
                        && Objects.equals(root.messageId(),
                        task.requestMessageId()),
                "task root command identity changed");
        if (!createdByCurrent) {
            require("COMMITTED".equals(root.status())
                            && task.taskId().equals(root.resultTaskId())
                            && root.resultEventId() != null
                            && task.sourceInstructionId().equals(
                            root.resultInstructionId())
                            && root.resultCode() == null
                            && root.committedAt() != null,
                    "target task root command is not formally committed");
            Long eventMatches = jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM agent_v2_chain_authority_events
                     WHERE task_id = :taskId
                       AND event_id = :eventId
                    """, new MapSqlParameterSource()
                    .addValue("taskId", task.taskId())
                    .addValue("eventId", root.resultEventId()), Long.class);
            require(eventMatches != null && eventMatches == 1L,
                    "target task root result event changed");
        }
    }

    private void verifyConversation(
            CommandRow command, InstructionRow instruction) {
        List<Map<String, Object>> messages = jdbc.queryForList("""
                SELECT id, user_id, session_id, role, content
                  FROM agent_messages
                 WHERE id = :messageId
                """, Map.of("messageId", command.messageId()));
        Map<String, Object> message = one(messages,
                "received command message authority is ambiguous")
                .orElseThrow(() -> new IllegalStateException(
                        "received command message is missing"));
        require(number(message, "user_id") == command.userId()
                        && number(message, "session_id")
                        == command.sessionId()
                        && "user".equals(text(message, "role")),
                "received command message owner changed");
        String content = nullableText(message, "content");
        require(content != null && instruction.bodySha256() != null
                        && instruction.bodySha256().equals(sha256(content)),
                "received command message body changed");

        List<Map<String, Object>> turns = jdbc.queryForList("""
                SELECT id, user_id, session_id, user_message_id
                  FROM agent_turns
                 WHERE id = :turnId
                """, Map.of("turnId", command.turnId()));
        Map<String, Object> turn = one(turns,
                "received command turn authority is ambiguous")
                .orElseThrow(() -> new IllegalStateException(
                        "received command turn is missing"));
        require(number(turn, "user_id") == command.userId()
                        && number(turn, "session_id") == command.sessionId()
                        && number(turn, "user_message_id")
                        == command.messageId(),
                "received command turn identity changed");
    }

    private List<CommandRow> commandRows(
            String sql, MapSqlParameterSource parameters) {
        return jdbc.queryForList(sql, parameters).stream()
                .map(ProductChainReceivedCommandSource::command).toList();
    }

    private List<TaskRow> taskRows(
            String sql, MapSqlParameterSource parameters) {
        return jdbc.queryForList(sql, parameters).stream()
                .map(ProductChainReceivedCommandSource::task).toList();
    }

    private List<InstructionRow> instructionRows(
            String sql, MapSqlParameterSource parameters) {
        return jdbc.queryForList(sql, parameters).stream()
                .map(ProductChainReceivedCommandSource::instruction).toList();
    }

    private static CommandRow command(Map<String, Object> row) {
        return new CommandRow(text(row, "command_id"),
                number(row, "user_id"), number(row, "session_id"),
                text(row, "client_request_id"), ChainInstructionRelation
                .valueOf(text(row, "command_kind")),
                nullableText(row, "target_task_id"), nullableText(
                row, "target_client_request_id"), text(row, "request_sha256"),
                nullableNumber(row, "turn_id"), nullableNumber(
                row, "user_message_id"), nullableText(row, "result_task_id"),
                nullableText(row, "result_event_id"), nullableText(
                row, "result_instruction_id"), text(row, "status"),
                nullableText(row, "result_code"), row.get("committed_at"));
    }

    private static TaskRow task(Map<String, Object> row) {
        return new TaskRow(text(row, "task_id"), text(row,
                "created_by_command_id"), text(row, "source_instruction_id"),
                nullableText(row, "predecessor_task_id"),
                number(row, "user_id"), number(row, "session_id"),
                number(row, "turn_id"), nullableNumber(
                row, "request_message_id"), text(row,
                "root_client_request_id"), text(row, "root_request_sha256"),
                nullableNumber(row, "project_id"));
    }

    private static InstructionRow instruction(Map<String, Object> row) {
        return new InstructionRow(text(row, "instruction_id"),
                number(row, "session_id"), text(row, "origin_task_id"),
                nullableNumber(row, "message_id"), nullableText(
                row, "body_sha256"), ChainInstructionRelation.valueOf(
                text(row, "relation_kind")), nullableText(
                row, "parent_instruction_id"),
                text(row, "binding_event_id"),
                text(row, "relation_role"), number(
                row, "task_instruction_sequence"),
                text(row, "event_type"));
    }

    private static <T> Optional<T> one(List<T> values, String error) {
        if (values.size() > 1) throw new IllegalStateException(error);
        return values.stream().findFirst();
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) Objects.requireNonNull(row.get(key), key)).longValue();
    }

    private static Long nullableNumber(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : ((Number) value).longValue();
    }

    private static String text(Map<String, Object> row, String key) {
        return Objects.requireNonNull(row.get(key), key).toString();
    }

    private static String nullableText(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof Instant instant) return instant;
        if (value instanceof LocalDateTime local) {
            return local.toInstant(ZoneOffset.UTC);
        }
        throw new IllegalStateException(
                "received command cursor timestamp is invalid");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void positive(long value, String name) {
        if (value < 1) throw new IllegalArgumentException(
                name + " must be positive");
    }

    private static void required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static void sha256Required(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public record ReceivedCommand(
            String commandId,
            String taskId,
            String instructionId,
            String instructionBindingEventId,
            long userId,
            long sessionId,
            String clientRequestId,
            String requestSha256,
            ChainInstructionRelation commandKind,
            ChainInstructionRelation progressionRelation,
            long turnId,
            long messageId,
            String rootClientRequestId,
            String rootRequestSha256,
            String targetTaskId,
            String targetClientRequestId) {
    }

    public sealed interface ScanResult permits Ready, Blocked,
            NoLongerReceived {
        String commandId();
    }

    public record ScanCursor(Instant createdAt, String commandId) {
        public ScanCursor {
            Objects.requireNonNull(createdAt, "createdAt");
            required(commandId, "commandId");
        }
    }

    public record ScanPage(
            List<ScanResult> entries,
            ScanCursor nextCursor,
            boolean hasMore) {
        public ScanPage {
            entries = List.copyOf(entries);
            if (!entries.isEmpty()) {
                Objects.requireNonNull(nextCursor, "nextCursor");
            }
        }
    }

    public record Ready(ReceivedCommand command) implements ScanResult {
        public Ready {
            Objects.requireNonNull(command, "command");
        }

        @Override
        public String commandId() {
            return command.commandId();
        }
    }

    public record Blocked(
            String commandId,
            String errorCode,
            String reason) implements ScanResult {
        public Blocked {
            required(commandId, "commandId");
            required(errorCode, "errorCode");
            required(reason, "reason");
        }
    }

    public record NoLongerReceived(String commandId) implements ScanResult {
        public NoLongerReceived {
            required(commandId, "commandId");
        }
    }

    private record CommandRow(
            String commandId, long userId, long sessionId,
            String clientRequestId, ChainInstructionRelation commandKind,
            String targetTaskId, String targetClientRequestId,
            String requestSha256, Long turnId, Long messageId,
            String resultTaskId, String resultEventId,
            String resultInstructionId, String status, String resultCode,
            Object committedAt) {
    }

    private record TaskRow(
            String taskId, String createdByCommandId,
            String sourceInstructionId, String predecessorTaskId,
            long userId, long sessionId, long turnId, Long requestMessageId,
            String rootClientRequestId, String rootRequestSha256,
            Long projectId) {
    }

    private record InstructionRow(
            String instructionId, long sessionId, String originTaskId,
            Long messageId, String bodySha256,
            ChainInstructionRelation relationKind,
            String parentInstructionId,
            String bindingEventId,
            String bindingRole,
            long bindingSequence,
            String bindingEventType) {
    }

    private record ScanCandidate(String commandId, Instant createdAt) {
    }
}
