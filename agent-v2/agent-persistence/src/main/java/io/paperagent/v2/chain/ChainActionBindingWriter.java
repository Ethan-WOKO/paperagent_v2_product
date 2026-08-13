package io.paperagent.v2.chain;

public interface ChainActionBindingWriter {
    ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.ActionBindingRecord>
            appendActionBinding(
                    ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.ActionBindingRecord> binding);
}
