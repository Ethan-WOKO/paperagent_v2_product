package io.paperagent.v2.chain;

public interface ChainDeliveryWriter {
    ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.DeliveryRecord> appendDelivery(
            ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.DeliveryRecord> delivery);

    ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.DeliveryEventRecord> appendDeliveryEvent(
            ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.DeliveryEventRecord> event);
}
