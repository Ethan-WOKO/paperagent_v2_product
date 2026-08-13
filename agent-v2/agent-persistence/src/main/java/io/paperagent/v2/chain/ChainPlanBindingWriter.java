package io.paperagent.v2.chain;

public interface ChainPlanBindingWriter {
    ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.PlanBindingRecord> appendPlanBinding(
            ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.PlanBindingRecord> binding);
}
