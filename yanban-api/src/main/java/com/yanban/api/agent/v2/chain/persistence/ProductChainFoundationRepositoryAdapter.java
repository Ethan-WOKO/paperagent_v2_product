package com.yanban.api.agent.v2.chain.persistence;

import io.paperagent.v2.chain.ChainCommandStatus;
import io.paperagent.v2.chain.ChainCommandWriter;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainInstructionWriter;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainTaskWriter;
import io.paperagent.v2.chain.ChainPersistenceRecords.AppendResult;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthoritativeAppendResult;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthoritativeFact;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthorityEventRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.CommandRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.InstructionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskInstructionBindingRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskRecord;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ProductChainFoundationRepositoryAdapter
        implements ChainFoundationRepository, ChainCommandWriter,
        ChainTaskWriter, ChainInstructionWriter {
    private static final String COMMANDS = "agent_v2_chain_commands";
    private static final String TASKS = "agent_v2_chain_tasks";
    private static final String INSTRUCTIONS = "agent_v2_chain_instructions";
    private static final String TASK_INSTRUCTIONS =
            "agent_v2_chain_task_instruction_bindings";

    private final ProductChainTransactions transactions;
    private final ProductChainTimeSource time;

    public ProductChainFoundationRepositoryAdapter(
            ProductChainTransactions transactions,
            ProductChainTimeSource time) {
        this.transactions = transactions;
        this.time = time;
    }

    @Override
    public AppendResult<CommandRecord> registerCommand(CommandRecord command) {
        if (command.status() != ChainCommandStatus.RECEIVED) {
            throw new ProductChainPersistenceException(
                    "CHAIN_COMMAND_MUST_START_RECEIVED");
        }
        Map<String, Object> identity = ordered("user_id", command.userId(),
                        "session_id", command.sessionId(),
                        "client_request_id", command.clientRequestId());
        return command.targetTaskId() == null
                ? transactions.append(COMMANDS, CommandRecord.class, command,
                identity,
                ProductChainFoundationRepositoryAdapter::sameCommandRequest)
                : transactions.appendTaskScoped(COMMANDS,
                CommandRecord.class, command, identity,
                command.targetTaskId(),
                ProductChainFoundationRepositoryAdapter::sameCommandRequest);
    }

    @Override
    public Optional<CommandRecord> findCommand(
            long userId, long sessionId, String clientRequestId) {
        return transactions.find(COMMANDS, CommandRecord.class,
                ordered("user_id", userId, "session_id", sessionId,
                        "client_request_id", clientRequestId));
    }

    @Override
    public Optional<CommandRecord> findCommand(String commandId) {
        return transactions.find(COMMANDS, CommandRecord.class,
                Map.of("command_id", commandId));
    }

    @Override
    public CommandRecord commitCommand(
            String commandId, String resultTaskId,
            String resultEventId, String resultInstructionId) {
        return terminalCommand(commandId, ChainCommandStatus.COMMITTED,
                resultTaskId, resultEventId, resultInstructionId, null);
    }

    @Override
    public CommandRecord failCommand(String commandId, String failureCode) {
        return terminalCommand(commandId, ChainCommandStatus.FAILED,
                null, null, null, failureCode);
    }

    @Override
    public AppendResult<TaskRecord> appendTask(TaskRecord task) {
        if (task.nextEventSequence() != 0) {
            throw new ProductChainPersistenceException(
                    "CHAIN_TASK_EVENT_SEQUENCE_NOT_INITIAL");
        }
        return transactions.append(TASKS, TaskRecord.class, task,
                Map.of("task_id", task.taskId()),
                ProductChainFoundationRepositoryAdapter::sameTaskCreation);
    }

    @Override
    public Optional<TaskRecord> findTask(String taskId) {
        return transactions.find(TASKS, TaskRecord.class,
                Map.of("task_id", taskId));
    }

    @Override
    public Optional<InstructionRecord> findInstruction(
            String instructionId) {
        return transactions.find(INSTRUCTIONS, InstructionRecord.class,
                Map.of("instruction_id", instructionId));
    }

    @Override
    public AppendResult<InstructionRecord> appendInstruction(
            InstructionRecord instruction) {
        return transactions.appendTaskScoped(INSTRUCTIONS,
                InstructionRecord.class, instruction,
                Map.of("instruction_id", instruction.instructionId()),
                instruction.originTaskId());
    }

    @Override
    public AuthoritativeAppendResult<TaskInstructionBindingRecord>
            appendTaskInstructionBinding(
                    AuthoritativeFact<TaskInstructionBindingRecord> binding) {
        TaskInstructionBindingRecord fact = binding.fact();
        return transactions.appendAuthoritative(
                TASK_INSTRUCTIONS, TaskInstructionBindingRecord.class,
                binding, ordered("task_id", fact.taskId(),
                        "task_instruction_sequence",
                        fact.taskInstructionSequence()));
    }

    @Override
    public List<TaskInstructionBindingRecord> findTaskInstructions(
            String taskId, long sequenceCut) {
        String sql = "SELECT * FROM " + TASK_INSTRUCTIONS
                + " WHERE task_id = :taskId"
                + " AND task_instruction_sequence <= :sequenceCut"
                + " ORDER BY task_instruction_sequence";
        return transactions.jdbc().queryForList(sql,
                        new MapSqlParameterSource()
                                .addValue("taskId", taskId)
                                .addValue("sequenceCut", sequenceCut))
                .stream()
                .map(row -> transactions.codec().decode(
                        TaskInstructionBindingRecord.class, row))
                .toList();
    }

    @Override
    public List<AuthorityEventRecord> findAuthorityEvents(
            String taskId, long sequenceCut) {
        return transactions.jdbc().queryForList("""
                        SELECT *
                          FROM agent_v2_chain_authority_events
                         WHERE task_id = :taskId
                           AND event_sequence <= :sequenceCut
                         ORDER BY event_sequence
                        """, new MapSqlParameterSource()
                        .addValue("taskId", taskId)
                        .addValue("sequenceCut", sequenceCut)).stream()
                .map(row -> transactions.codec().decode(
                        AuthorityEventRecord.class, row))
                .toList();
    }

    @Override
    public long highestAuthorityEventSequence(String taskId) {
        return transactions.scalar("""
                SELECT COALESCE(MAX(event_sequence), 0)
                  FROM agent_v2_chain_authority_events
                 WHERE task_id = :taskId
                """, Map.of("taskId", taskId));
    }

    private CommandRecord terminalCommand(
            String commandId, ChainCommandStatus target,
            String resultTaskId, String resultEventId,
            String resultInstructionId,
            String failureCode) {
        return transactions.inWrite(() -> {
            CommandRecord current = transactions.findCurrent(
                    COMMANDS, CommandRecord.class,
                    Map.of("command_id", commandId), true)
                    .orElseThrow(() -> new ProductChainPersistenceException(
                            "CHAIN_COMMAND_NOT_FOUND"));
            if (current.status() != ChainCommandStatus.RECEIVED) {
                if (sameTerminal(current, target, resultTaskId,
                        resultEventId, resultInstructionId, failureCode)) {
                    return current;
                }
                throw new ProductChainPersistenceException(
                        "CHAIN_COMMAND_ALREADY_TERMINAL");
            }
            if (target == ChainCommandStatus.COMMITTED) {
                if (resultTaskId == null || resultEventId == null
                        || resultInstructionId == null) {
                    throw new ProductChainPersistenceException(
                            "CHAIN_COMMAND_RESULT_INCOMPLETE");
                }
                long resultMatches = transactions.scalar("""
                        SELECT COUNT(*)
                          FROM agent_v2_chain_tasks task
                          JOIN agent_v2_chain_authority_events event
                            ON event.task_id = task.task_id
                          JOIN agent_v2_chain_task_instruction_bindings binding
                            ON binding.task_id = task.task_id
                          JOIN agent_v2_chain_instructions instruction
                            ON instruction.instruction_id = binding.instruction_id
                         WHERE task.task_id = :taskId
                           AND task.user_id = :userId
                           AND task.session_id = :sessionId
                           AND event.event_id = :eventId
                           AND instruction.instruction_id = :instructionId
                           AND instruction.command_id = :commandId
                           AND instruction.session_id = :sessionId
                        """, ordered("taskId", resultTaskId,
                        "userId", current.userId(),
                        "sessionId", current.sessionId(),
                        "eventId", resultEventId,
                        "instructionId", resultInstructionId,
                        "commandId", commandId));
                if (resultMatches != 1) {
                    throw new ProductChainPersistenceException(
                            "CHAIN_COMMAND_RESULT_REFS_MISMATCH");
                }
            } else if (failureCode == null || failureCode.isBlank()) {
                throw new ProductChainPersistenceException(
                        "CHAIN_COMMAND_FAILURE_CODE_REQUIRED");
            }
            Instant committedAt = time.now();
            int changed = transactions.jdbc().update("""
                    UPDATE agent_v2_chain_commands
                       SET result_task_id = :resultTaskId,
                           result_event_id = :resultEventId,
                           result_instruction_id = :resultInstructionId,
                           status = :status,
                           result_code = :resultCode,
                           committed_at = :committedAt
                     WHERE command_id = :commandId
                       AND status = 'RECEIVED'
                    """, new MapSqlParameterSource()
                    .addValue("resultTaskId", resultTaskId)
                    .addValue("resultEventId", resultEventId)
                    .addValue("resultInstructionId", resultInstructionId)
                    .addValue("status", target.name())
                    .addValue("resultCode", failureCode)
                    .addValue("committedAt", committedAt)
                    .addValue("commandId", commandId));
            if (changed != 1) {
                throw new ProductChainPersistenceException(
                        "CHAIN_COMMAND_CAS_FAILED");
            }
            return transactions.findCurrent(COMMANDS, CommandRecord.class,
                    Map.of("command_id", commandId), false).orElseThrow();
        });
    }

    private static boolean sameTerminal(
            CommandRecord current, ChainCommandStatus target,
            String resultTaskId, String resultEventId,
            String resultInstructionId,
            String failureCode) {
        return current.status() == target
                && java.util.Objects.equals(
                        current.resultTaskId(), resultTaskId)
                && java.util.Objects.equals(
                        current.resultEventId(), resultEventId)
                && java.util.Objects.equals(
                        current.resultInstructionId(), resultInstructionId)
                && java.util.Objects.equals(current.resultCode(), failureCode);
    }

    private static boolean sameCommandRequest(
            CommandRecord stored, CommandRecord requested) {
        return stored.commandId().equals(requested.commandId())
                && stored.userId() == requested.userId()
                && stored.sessionId() == requested.sessionId()
                && stored.clientRequestId().equals(
                        requested.clientRequestId())
                && stored.commandKind() == requested.commandKind()
                && java.util.Objects.equals(stored.targetTaskId(),
                        requested.targetTaskId())
                && java.util.Objects.equals(stored.targetClientRequestId(),
                        requested.targetClientRequestId())
                && java.util.Objects.equals(stored.gapId(), requested.gapId())
                && stored.requestSha256().equals(requested.requestSha256())
                && java.util.Objects.equals(stored.turnId(), requested.turnId())
                && java.util.Objects.equals(stored.userMessageId(),
                        requested.userMessageId());
    }

    private static boolean sameTaskCreation(
            TaskRecord stored, TaskRecord requested) {
        return stored.taskId().equals(requested.taskId())
                && stored.createdByCommandId().equals(
                        requested.createdByCommandId())
                && stored.sourceInstructionId().equals(
                        requested.sourceInstructionId())
                && java.util.Objects.equals(stored.predecessorTaskId(),
                        requested.predecessorTaskId())
                && stored.userId() == requested.userId()
                && stored.sessionId() == requested.sessionId()
                && stored.turnId() == requested.turnId()
                && java.util.Objects.equals(stored.requestMessageId(),
                        requested.requestMessageId())
                && stored.rootClientRequestId().equals(
                        requested.rootClientRequestId())
                && stored.rootRequestSha256().equals(
                        requested.rootRequestSha256())
                && java.util.Objects.equals(stored.projectId(),
                        requested.projectId())
                && java.util.Objects.equals(stored.initialProjectVersion(),
                        requested.initialProjectVersion());
    }

    private static Map<String, Object> ordered(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }
}
