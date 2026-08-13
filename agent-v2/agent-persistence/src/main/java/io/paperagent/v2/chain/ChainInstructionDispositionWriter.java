package io.paperagent.v2.chain;

public interface ChainInstructionDispositionWriter {
    ChainPersistenceRecords.AuthoritativeAppendResult<
            ChainPersistenceRecords.InstructionDispositionRecord>
            appendInstructionDisposition(
                    ChainPersistenceRecords.AuthoritativeFact<
                            ChainPersistenceRecords.InstructionDispositionRecord>
                            disposition);
}
