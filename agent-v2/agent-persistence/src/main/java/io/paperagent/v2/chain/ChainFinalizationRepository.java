package io.paperagent.v2.chain;

import java.util.List;
import java.util.Optional;

public interface ChainFinalizationRepository {
    Optional<ChainPersistenceRecords.FinalizationReadinessRecord> findReadinessById(String readinessId);

    Optional<ChainPersistenceRecords.FinalizationReadinessRecord> findReadinessByScope(String readinessScopeKey);

    List<ChainPersistenceRecords.FinalizationReadinessRecord> findReadiness(String taskId);

    List<ChainPersistenceRecords.FinalizationCheckRecord> findFinalizationChecks(String readinessId);

    Optional<ChainPersistenceRecords.TaskOutcomeRecord> findTaskOutcome(String taskId);

    List<ChainPersistenceRecords.DeliveryRecord> findDeliveries(String taskId);

    List<ChainPersistenceRecords.DeliveryRecord> findIncompleteDeliveries(String taskId);

    List<ChainPersistenceRecords.DeliveryEventRecord> findDeliveryEvents(String deliveryId);
}
