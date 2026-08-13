package io.paperagent.v2.chain;

import java.util.List;
import java.util.Optional;

public interface ChainFoundationRepository {
    Optional<ChainPersistenceRecords.CommandRecord> findCommand(
            long userId, long sessionId, String clientRequestId);

    Optional<ChainPersistenceRecords.CommandRecord> findCommand(
            String commandId);

    Optional<ChainPersistenceRecords.TaskRecord> findTask(String taskId);

    Optional<ChainPersistenceRecords.InstructionRecord> findInstruction(
            String instructionId);

    List<ChainPersistenceRecords.TaskInstructionBindingRecord> findTaskInstructions(
            String taskId, long sequenceCut);

    List<ChainPersistenceRecords.AuthorityEventRecord> findAuthorityEvents(
            String taskId, long sequenceCut);

    long highestAuthorityEventSequence(String taskId);

}
