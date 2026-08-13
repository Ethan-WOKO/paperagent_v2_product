package io.paperagent.v2.chain;

public interface ChainInstructionWriter {
    ChainPersistenceRecords.AppendResult<ChainPersistenceRecords.InstructionRecord> appendInstruction(
            ChainPersistenceRecords.InstructionRecord instruction);

    ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.TaskInstructionBindingRecord>
            appendTaskInstructionBinding(
                    ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.TaskInstructionBindingRecord> binding);
}
