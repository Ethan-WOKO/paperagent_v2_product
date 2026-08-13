package com.yanban.api.agent.v2.chain.persistence;

import io.paperagent.v2.chain.ChainDeliveryWriter;
import io.paperagent.v2.chain.ChainFinalizationCheckWriter;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainReadinessWriter;
import io.paperagent.v2.chain.ChainTaskOutcomeWriter;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthoritativeAppendResult;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthoritativeFact;
import io.paperagent.v2.chain.ChainPersistenceRecords.DeliveryEventRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.DeliveryRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.FinalizationCheckRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.FinalizationReadinessRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskAuthorityFact;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskOutcomeRecord;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ProductChainFinalizationRepositoryAdapter
        implements ChainFinalizationRepository, ChainReadinessWriter,
        ChainFinalizationCheckWriter, ChainTaskOutcomeWriter,
        ChainDeliveryWriter {
    private final ProductChainTransactions transactions;

    public ProductChainFinalizationRepositoryAdapter(
            ProductChainTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public AuthoritativeAppendResult<FinalizationReadinessRecord>
            appendReadiness(
                    AuthoritativeFact<FinalizationReadinessRecord> readiness) {
        return append("agent_v2_chain_finalization_readiness",
                FinalizationReadinessRecord.class, readiness,
                Map.of("readiness_id", readiness.fact().readinessId()));
    }

    @Override
    public AuthoritativeAppendResult<FinalizationCheckRecord>
            appendFinalizationCheck(
                    AuthoritativeFact<FinalizationCheckRecord> check) {
        return append("agent_v2_chain_finalization_checks",
                FinalizationCheckRecord.class, check,
                Map.of("finalization_check_id",
                        check.fact().finalizationCheckId()));
    }

    @Override
    public AuthoritativeAppendResult<TaskOutcomeRecord> appendTaskOutcome(
            AuthoritativeFact<TaskOutcomeRecord> outcome) {
        return append("agent_v2_chain_task_outcomes",
                TaskOutcomeRecord.class, outcome,
                Map.of("outcome_id", outcome.fact().outcomeId()));
    }

    @Override
    public AuthoritativeAppendResult<DeliveryRecord> appendDelivery(
            AuthoritativeFact<DeliveryRecord> delivery) {
        return append("agent_v2_chain_deliveries", DeliveryRecord.class,
                delivery,
                Map.of("delivery_id", delivery.fact().deliveryId()));
    }

    @Override
    public AuthoritativeAppendResult<DeliveryEventRecord> appendDeliveryEvent(
            AuthoritativeFact<DeliveryEventRecord> event) {
        return append("agent_v2_chain_delivery_events",
                DeliveryEventRecord.class, event,
                ordered("delivery_id", event.fact().deliveryId(),
                        "event_sequence", event.fact().eventSequence()));
    }

    @Override
    public Optional<FinalizationReadinessRecord> findReadinessById(
            String readinessId) {
        return transactions.find("agent_v2_chain_finalization_readiness",
                FinalizationReadinessRecord.class,
                Map.of("readiness_id", readinessId));
    }

    @Override
    public Optional<FinalizationReadinessRecord> findReadinessByScope(
            String readinessScopeKey) {
        return transactions.find("agent_v2_chain_finalization_readiness",
                FinalizationReadinessRecord.class,
                Map.of("readiness_scope_key", readinessScopeKey));
    }

    @Override
    public List<FinalizationReadinessRecord> findReadiness(String taskId) {
        return authorityOrdered("agent_v2_chain_finalization_readiness",
                "readiness", taskId, FinalizationReadinessRecord.class);
    }

    @Override
    public List<FinalizationCheckRecord> findFinalizationChecks(
            String readinessId) {
        return transactions.findAll("agent_v2_chain_finalization_checks",
                FinalizationCheckRecord.class,
                Map.of("readiness_id", readinessId), "attempt_no");
    }

    @Override
    public Optional<TaskOutcomeRecord> findTaskOutcome(String taskId) {
        return transactions.find("agent_v2_chain_task_outcomes",
                TaskOutcomeRecord.class, Map.of("task_id", taskId));
    }

    @Override
    public List<DeliveryRecord> findDeliveries(String taskId) {
        return authorityOrdered("agent_v2_chain_deliveries",
                "delivery", taskId, DeliveryRecord.class);
    }

    @Override
    public List<DeliveryRecord> findIncompleteDeliveries(String taskId) {
        return query("""
                SELECT delivery.*
                  FROM agent_v2_chain_deliveries delivery
                 WHERE delivery.task_id = :taskId
                   AND NOT EXISTS (
                       SELECT 1
                         FROM agent_v2_chain_delivery_events state
                        WHERE state.delivery_id = delivery.delivery_id
                          AND state.event_kind IN
                              ('SUCCEEDED','DELIVERY_FAILED'))
                 ORDER BY delivery.created_at, delivery.delivery_id
                """, Map.of("taskId", taskId), DeliveryRecord.class);
    }

    @Override
    public List<DeliveryEventRecord> findDeliveryEvents(String deliveryId) {
        return transactions.findAll("agent_v2_chain_delivery_events",
                DeliveryEventRecord.class,
                Map.of("delivery_id", deliveryId), "event_sequence");
    }

    private <T extends Record & TaskAuthorityFact>
            AuthoritativeAppendResult<T> append(
            String table, Class<T> type, AuthoritativeFact<T> fact,
            Map<String, Object> identity) {
        return transactions.appendAuthoritative(table, type, fact, identity);
    }

    private <T extends Record> List<T> query(
            String sql, Map<String, Object> parameters, Class<T> type) {
        return transactions.jdbc().queryForList(sql,
                        new MapSqlParameterSource(parameters)).stream()
                .map(row -> transactions.codec().decode(type, row)).toList();
    }

    private <T extends Record> List<T> authorityOrdered(
            String table, String alias, String taskId, Class<T> type) {
        return query("SELECT " + alias + ".* FROM " + table + " " + alias
                        + " JOIN agent_v2_chain_authority_events authority"
                        + " ON authority.event_id = " + alias + ".event_id"
                        + " AND authority.task_id = " + alias + ".task_id"
                        + " WHERE " + alias + ".task_id = :taskId"
                        + " ORDER BY authority.event_sequence",
                Map.of("taskId", taskId), type);
    }

    private static Map<String, Object> ordered(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }
}
