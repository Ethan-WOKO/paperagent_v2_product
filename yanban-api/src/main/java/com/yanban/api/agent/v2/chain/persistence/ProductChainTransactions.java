package com.yanban.api.agent.v2.chain.persistence;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthoritativeAppendResult;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthoritativeFact;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthorityEventRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.AuthorityEventRequest;
import io.paperagent.v2.chain.ChainPersistenceRecords.AppendResult;
import io.paperagent.v2.chain.ChainPersistenceRecords.ModelProposalRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ProposalOfficialAuthorityType;
import io.paperagent.v2.chain.ChainPersistenceRecords.ProposalStateEventRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TaskAuthorityFact;
import io.paperagent.v2.chain.ChainPersistenceRecords.TransitionRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.TransitionStageRecord;
import io.paperagent.v2.chain.ChainProposalState;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainValidationBundleRepository;
import io.paperagent.v2.chain.ChainValidationRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

/** Transaction and race boundary shared by module-specific chain adapters. */
@Repository
class ProductChainTransactions {
    private final NamedParameterJdbcTemplate jdbc;
    private final ProductChainRecordCodec codec;
    private final ProductChainTimeSource time;
    private final ProductChainRootReferenceValidator rootReferences;
    private final TransactionTemplate write;
    private final TransactionTemplate read;
    private final TransactionTemplate raceRead;

    ProductChainTransactions(
            NamedParameterJdbcTemplate jdbc, ProductChainRecordCodec codec,
            PlatformTransactionManager transactions,
            ProductChainTimeSource time) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.time = time;
        this.rootReferences = new ProductChainRootReferenceValidator(jdbc);
        this.write = new TransactionTemplate(transactions);
        this.read = new TransactionTemplate(transactions);
        this.read.setReadOnly(true);
        this.raceRead = new TransactionTemplate(transactions);
        this.raceRead.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.raceRead.setReadOnly(true);
    }

    <T extends Record> AppendResult<T> append(
            String table, Class<T> type, T requested,
            Map<String, Object> identity) {
        return append(table, type, requested, identity,
                this::canonicalEquals);
    }

    <T extends Record> AppendResult<T> appendTaskScoped(
            String table, Class<T> type, T requested,
            Map<String, Object> identity, String taskId) {
        return appendTaskScoped(table, type, requested, identity, taskId,
                this::canonicalEquals);
    }

    <T extends Record> AppendResult<T> appendTaskScoped(
            String table, Class<T> type, T requested,
            Map<String, Object> identity, String taskId,
            java.util.function.BiPredicate<T, T> replayIdentity) {
        try {
            return write.execute(status -> {
                lockTask(taskId);
                rootReferences.verify(requested);
                Optional<T> existing = find(
                        table, type, identity, false);
                if (existing.isPresent()) {
                    return replay(existing.get(), requested, replayIdentity);
                }
                insert(table, requested);
                return new AppendResult<>(find(table, type, identity, false)
                        .orElseThrow(), false);
            });
        } catch (DataIntegrityViolationException exception) {
            return raceRead.execute(status -> find(
                            table, type, identity, false)
                    .map(existing -> replay(
                            existing, requested, replayIdentity))
                    .orElseThrow(() -> exception));
        }
    }

    <T extends Record> AppendResult<T> append(
            String table, Class<T> type, T requested,
            Map<String, Object> identity,
            java.util.function.BiPredicate<T, T> replayIdentity) {
        try {
            return write.execute(status -> {
                Optional<T> existing = find(table, type, identity, false);
                if (existing.isPresent()) {
                    return replay(existing.get(), requested, replayIdentity);
                }
                insert(table, requested);
                T stored = find(table, type, identity, false).orElseThrow();
                return new AppendResult<>(stored, false);
            });
        } catch (DataIntegrityViolationException exception) {
            return raceRead.execute(status -> find(table, type, identity, false)
                    .map(existing -> replay(
                            existing, requested, replayIdentity))
                    .orElseThrow(() -> exception));
        }
    }

    <T extends Record & TaskAuthorityFact> AuthoritativeAppendResult<T>
            appendAuthoritative(
            String table, Class<T> type, AuthoritativeFact<T> requested,
            Map<String, Object> factIdentity) {
        verifyFactIdentity(requested);
        try {
            return write.execute(status -> appendAuthoritativeLocked(
                    table, type, requested, factIdentity));
        } catch (DataIntegrityViolationException exception) {
            return raceRead.execute(status -> authoritativeReplay(
                    table, type, requested, factIdentity)
                    .orElseThrow(() -> exception));
        }
    }

    ChainValidationRepository.ValidationAppendResult appendValidation(
            AuthoritativeFact<ChainPersistenceRecords.ValidationSetRecord>
                    requested,
            List<ChainPersistenceRecords.CandidateValidationItemRecord>
                    candidateItems,
            List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                    actionItems) {
        verifyFactIdentity(requested);
        candidateItems = List.copyOf(candidateItems);
        actionItems = List.copyOf(actionItems);
        try {
            List<ChainPersistenceRecords.CandidateValidationItemRecord>
                    finalCandidateItems = candidateItems;
            List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                    finalActionItems = actionItems;
            return write.execute(status -> appendValidationLocked(
                    requested, finalCandidateItems, finalActionItems));
        } catch (DataIntegrityViolationException exception) {
            List<ChainPersistenceRecords.CandidateValidationItemRecord>
                    finalCandidateItems = candidateItems;
            List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                    finalActionItems = actionItems;
            return raceRead.execute(status -> validationReplay(
                    requested, finalCandidateItems, finalActionItems)
                    .orElseThrow(() -> exception));
        }
    }

    ChainValidationBundleRepository.BundleAppendResult appendValidationBundle(
            AuthoritativeFact<ChainPersistenceRecords.ValidationBundleRecord>
                    requested,
            List<ChainPersistenceRecords.ValidationBundleSetRecord> sets) {
        verifyFactIdentity(requested);
        sets = List.copyOf(sets);
        try {
            List<ChainPersistenceRecords.ValidationBundleSetRecord> finalSets =
                    sets;
            return write.execute(status -> appendValidationBundleLocked(
                    requested, finalSets));
        } catch (DataIntegrityViolationException exception) {
            List<ChainPersistenceRecords.ValidationBundleSetRecord> finalSets =
                    sets;
            return raceRead.execute(status -> validationBundleReplay(
                    requested, finalSets).orElseThrow(() -> exception));
        }
    }

    private ChainValidationBundleRepository.BundleAppendResult
            appendValidationBundleLocked(
                    AuthoritativeFact<ChainPersistenceRecords
                            .ValidationBundleRecord> requested,
                    List<ChainPersistenceRecords.ValidationBundleSetRecord>
                            sets) {
        var appended = appendAuthoritativeLocked(
                "agent_v2_chain_validation_bundles",
                ChainPersistenceRecords.ValidationBundleRecord.class,
                requested, Map.of("validation_bundle_id",
                        requested.fact().validationBundleId()));
        if (!appended.replayed()) {
            sets.forEach(item -> insert(
                    "agent_v2_chain_validation_bundle_sets", item));
        }
        return requireValidationBundleSets(appended.event(),
                appended.fact(), sets, appended.replayed());
    }

    private Optional<ChainValidationBundleRepository.BundleAppendResult>
            validationBundleReplay(
                    AuthoritativeFact<ChainPersistenceRecords
                            .ValidationBundleRecord> requested,
                    List<ChainPersistenceRecords.ValidationBundleSetRecord>
                            sets) {
        var replay = authoritativeReplay(
                "agent_v2_chain_validation_bundles",
                ChainPersistenceRecords.ValidationBundleRecord.class,
                requested, Map.of("validation_bundle_id",
                        requested.fact().validationBundleId()));
        if (replay.isEmpty()) {
            var sameKey = find("agent_v2_chain_validation_bundles",
                    ChainPersistenceRecords.ValidationBundleRecord.class,
                    Map.of("task_id", requested.fact().taskId(),
                            "idempotency_key",
                            requested.fact().idempotencyKey()), false);
            if (sameKey.isPresent()) {
                throw new ProductChainPersistenceException(
                        "CHAIN_CONFLICTING_VALIDATION_BUNDLE_REPLAY");
            }
        }
        return replay.map(value -> requireValidationBundleSets(
                value.event(), value.fact(), sets, true));
    }

    private ChainValidationBundleRepository.BundleAppendResult
            requireValidationBundleSets(
                    AuthorityEventRecord event,
                    ChainPersistenceRecords.ValidationBundleRecord bundle,
                    List<ChainPersistenceRecords.ValidationBundleSetRecord>
                            expected,
                    boolean replayed) {
        var stored = findAll(
                "agent_v2_chain_validation_bundle_sets",
                ChainPersistenceRecords.ValidationBundleSetRecord.class,
                Map.of("validation_bundle_id",
                        bundle.validationBundleId()), "step_id");
        var sorted = expected.stream().sorted(Comparator.comparing(
                ChainPersistenceRecords.ValidationBundleSetRecord::stepId))
                .toList();
        if (!stored.equals(sorted)) {
            throw new ProductChainPersistenceException(
                    "CHAIN_CONFLICTING_VALIDATION_BUNDLE_REPLAY");
        }
        return new ChainValidationBundleRepository.BundleAppendResult(
                event, bundle, stored, replayed);
    }

    private ChainValidationRepository.ValidationAppendResult
            appendValidationLocked(
                    AuthoritativeFact<ChainPersistenceRecords.ValidationSetRecord>
                            requested,
                    List<ChainPersistenceRecords.CandidateValidationItemRecord>
                            candidateItems,
                    List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                            actionItems) {
        var appended = appendAuthoritativeLocked(
                "agent_v2_chain_validation_sets",
                ChainPersistenceRecords.ValidationSetRecord.class,
                requested, Map.of("validation_id",
                        requested.fact().validationId()));
        if (!appended.replayed()) {
            candidateItems.forEach(item -> insert(
                    "agent_v2_chain_candidate_validation_items", item));
            actionItems.forEach(item -> insert(
                    "agent_v2_chain_action_receipt_validation_items", item));
        }
        return requireValidationItems(appended.event(), appended.fact(),
                candidateItems, actionItems, appended.replayed());
    }

    private Optional<ChainValidationRepository.ValidationAppendResult>
            validationReplay(
                    AuthoritativeFact<ChainPersistenceRecords.ValidationSetRecord>
                            requested,
                    List<ChainPersistenceRecords.CandidateValidationItemRecord>
                            candidateItems,
                    List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                            actionItems) {
        var replay = authoritativeReplay(
                "agent_v2_chain_validation_sets",
                ChainPersistenceRecords.ValidationSetRecord.class,
                requested, Map.of("validation_id",
                        requested.fact().validationId()));
        if (replay.isEmpty()) {
            Optional<ChainPersistenceRecords.ValidationSetRecord> sameKey =
                    find("agent_v2_chain_validation_sets",
                            ChainPersistenceRecords.ValidationSetRecord.class,
                            Map.of("task_id", requested.fact().taskId(),
                                    "idempotency_key",
                                    requested.fact().idempotencyKey()), false);
            if (sameKey.isPresent()) {
                throw new ProductChainPersistenceException(
                        "CHAIN_CONFLICTING_VALIDATION_REPLAY");
            }
        }
        return replay.map(value -> requireValidationItems(
                value.event(), value.fact(), candidateItems, actionItems, true));
    }

    private ChainValidationRepository.ValidationAppendResult
            requireValidationItems(
                    AuthorityEventRecord event,
                    ChainPersistenceRecords.ValidationSetRecord validation,
                    List<ChainPersistenceRecords.CandidateValidationItemRecord>
                            expectedCandidateItems,
                    List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                            expectedActionItems,
                    boolean replayed) {
        var storedCandidateItems = findAll(
                "agent_v2_chain_candidate_validation_items",
                ChainPersistenceRecords.CandidateValidationItemRecord.class,
                Map.of("validation_id", validation.validationId()),
                "requirement_id");
        var storedActionItems = findAll(
                "agent_v2_chain_action_receipt_validation_items",
                ChainPersistenceRecords.ActionReceiptValidationItemRecord.class,
                Map.of("validation_id", validation.validationId()),
                "requirement_id");
        List<ChainPersistenceRecords.CandidateValidationItemRecord>
                sortedCandidates = expectedCandidateItems.stream()
                .sorted(java.util.Comparator.comparing(
                        ChainPersistenceRecords.CandidateValidationItemRecord
                                ::requirementId)).toList();
        List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                sortedActions = expectedActionItems.stream()
                .sorted(java.util.Comparator.comparing(
                        ChainPersistenceRecords.ActionReceiptValidationItemRecord
                                ::requirementId)).toList();
        if (!storedCandidateItems.equals(sortedCandidates)
                || !storedActionItems.equals(sortedActions)) {
            throw new ProductChainPersistenceException(
                    "CHAIN_CONFLICTING_VALIDATION_REPLAY");
        }
        return new ChainValidationRepository.ValidationAppendResult(
                event, validation, storedCandidateItems, storedActionItems,
                replayed);
    }

    <T extends Record> Optional<T> find(
            String table, Class<T> type, Map<String, Object> where) {
        return read.execute(status -> find(table, type, where, false));
    }

    <T extends Record> List<T> findAll(
            String table, Class<T> type, Map<String, Object> where,
            String orderBy) {
        String sql = "SELECT * FROM " + table + whereClause(where)
                + (orderBy == null ? "" : " ORDER BY " + orderBy);
        return jdbc.queryForList(sql, new MapSqlParameterSource(where)).stream()
                .map(row -> codec.decode(type, row)).toList();
    }

    long scalar(String sql, Map<String, Object> parameters) {
        Long value = jdbc.queryForObject(sql,
                new MapSqlParameterSource(parameters), Long.class);
        return value == null ? 0 : value;
    }

    int update(String sql, Map<String, Object> parameters) {
        return write.execute(status -> jdbc.update(
                sql, new MapSqlParameterSource(parameters)));
    }

    <T> T inWrite(java.util.function.Supplier<T> operation) {
        return write.execute(status -> operation.get());
    }

    NamedParameterJdbcTemplate jdbc() {
        return jdbc;
    }

    ProductChainRecordCodec codec() {
        return codec;
    }

    java.time.Instant auditTime() {
        return time.now();
    }

    <T extends Record> Optional<T> findCurrent(
            String table, Class<T> type, Map<String, Object> where,
            boolean lock) {
        return find(table, type, where, lock);
    }

    <T extends Record> List<T> findAllCurrent(
            String table, Class<T> type, Map<String, Object> where,
            String orderBy) {
        return findAll(table, type, where, orderBy);
    }

    <T extends Record> AppendResult<T> appendCurrent(
            String table, Class<T> type, T requested,
            Map<String, Object> identity) {
        Optional<T> existing = find(table, type, identity, false);
        if (existing.isPresent()) {
            return replay(existing.get(), requested);
        }
        insert(table, requested);
        return new AppendResult<>(
                find(table, type, identity, false).orElseThrow(), false);
    }

    private <T extends Record & TaskAuthorityFact> AuthoritativeAppendResult<T>
            appendAuthoritativeLocked(
                    String table, Class<T> type, AuthoritativeFact<T> requested,
                    Map<String, Object> factIdentity) {
        AuthorityEventRequest event = requested.event();
        List<Map<String, Object>> taskRows = jdbc.queryForList("""
                SELECT next_event_sequence
                  FROM agent_v2_chain_tasks
                 WHERE task_id = :taskId
                 FOR UPDATE
                """, new MapSqlParameterSource("taskId", event.taskId()));
        if (taskRows.size() != 1) {
            throw new ProductChainPersistenceException("CHAIN_TASK_NOT_FOUND");
        }
        rootReferences.verify(requested.fact());
        Optional<AuthoritativeAppendResult<T>> replay = authoritativeReplay(
                table, type, requested, factIdentity);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (requested.fact() instanceof TransitionStageRecord stage) {
            validateNextTransitionStage(stage);
        }
        if (requested.fact() instanceof ProposalStateEventRecord state) {
            validateNextProposalState(state);
        }
        long current = ((Number) taskRows.get(0).values().iterator().next())
                .longValue();
        long allocated = Math.addExact(current, 1);
        int advanced = jdbc.update("""
                UPDATE agent_v2_chain_tasks
                   SET next_event_sequence = :allocated
                 WHERE task_id = :taskId
                   AND next_event_sequence = :current
                """, new MapSqlParameterSource()
                .addValue("allocated", allocated)
                .addValue("taskId", event.taskId())
                .addValue("current", current));
        if (advanced != 1) {
            throw new ProductChainPersistenceException(
                    "CHAIN_EVENT_SEQUENCE_CAS_FAILED");
        }
        Instant authoritativeTime = time.now();
        AuthorityEventRecord storedEvent = new AuthorityEventRecord(
                event.eventId(), event.taskId(), allocated, event.eventType(),
                event.transitionId(), event.sourceIdentitySha256(),
                authoritativeTime);
        insert("agent_v2_chain_authority_events", storedEvent,
                authoritativeTime);
        insert(table, requested.fact(), authoritativeTime);
        T storedFact = find(table, type, factIdentity, false).orElseThrow();
        AuthorityEventRecord canonicalEvent = find(
                "agent_v2_chain_authority_events", AuthorityEventRecord.class,
                Map.of("event_id", event.eventId()), false).orElseThrow();
        return new AuthoritativeAppendResult<>(
                canonicalEvent, storedFact, false);
    }

    private void lockTask(String taskId) {
        List<String> tasks = jdbc.queryForList("""
                SELECT task_id FROM agent_v2_chain_tasks
                 WHERE task_id = :taskId
                 FOR UPDATE
                """, new MapSqlParameterSource("taskId", taskId),
                String.class);
        if (tasks.size() != 1) {
            throw new ProductChainPersistenceException("CHAIN_TASK_NOT_FOUND");
        }
    }

    private void validateNextTransitionStage(TransitionStageRecord requested) {
        TransitionRecord transition = find(
                "agent_v2_chain_transitions", TransitionRecord.class,
                Map.of("transition_id", requested.transitionId()), false)
                .orElseThrow(() -> new ProductChainPersistenceException(
                        "CHAIN_TRANSITION_NOT_FOUND"));
        if (!transition.taskId().equals(requested.taskId())) {
            throw new ProductChainPersistenceException(
                    "CHAIN_TRANSITION_TASK_MISMATCH");
        }
        List<TransitionStageRecord> committed = findAll(
                "agent_v2_chain_transition_stages",
                TransitionStageRecord.class,
                Map.of("transition_id", requested.transitionId()),
                "stage_ordinal");
        List<ChainTransitionStage> prefix = new ArrayList<>();
        for (int index = 0; index < committed.size(); index++) {
            TransitionStageRecord stage = committed.get(index);
            if (!stage.taskId().equals(transition.taskId())
                    || stage.stageOrdinal() != index) {
                throw new ProductChainPersistenceException(
                        "CHAIN_TRANSITION_STAGE_PREFIX_INVALID");
            }
            try {
                stage.validateNextFor(transition.transitionType(), prefix);
            } catch (IllegalArgumentException invalid) {
                throw new ProductChainPersistenceException(
                        "CHAIN_TRANSITION_STAGE_PREFIX_INVALID", invalid);
            }
            prefix.add(stage.stageCode());
        }
        try {
            requested.validateNextFor(transition.transitionType(), prefix);
        } catch (IllegalArgumentException invalid) {
            throw new ProductChainPersistenceException(
                    "CHAIN_TRANSITION_STAGE_NOT_NEXT", invalid);
        }
    }

    private void validateNextProposalState(
            ProposalStateEventRecord requested) {
        ModelProposalRecord proposal = find(
                "agent_v2_chain_model_proposals", ModelProposalRecord.class,
                Map.of("proposal_id", requested.proposalId()), false)
                .orElseThrow(() -> new ProductChainPersistenceException(
                        "CHAIN_PROPOSAL_NOT_FOUND"));
        if (!proposal.taskId().equals(requested.taskId())) {
            throw new ProductChainPersistenceException(
                    "CHAIN_PROPOSAL_TASK_MISMATCH");
        }
        List<ProposalStateEventRecord> committed = findAll(
                "agent_v2_chain_proposal_state_events",
                ProposalStateEventRecord.class,
                Map.of("proposal_id", requested.proposalId()),
                "state_sequence");
        List<ChainProposalState> prefix = new ArrayList<>();
        for (int index = 0; index < committed.size(); index++) {
            ProposalStateEventRecord state = committed.get(index);
            if (!state.taskId().equals(proposal.taskId())
                    || !state.proposalId().equals(proposal.proposalId())
                    || state.stateSequence() != index + 1L) {
                throw new ProductChainPersistenceException(
                        "CHAIN_PROPOSAL_STATE_PREFIX_INVALID");
            }
            try {
                state.validateNextFor(prefix);
            } catch (IllegalArgumentException invalid) {
                throw new ProductChainPersistenceException(
                        "CHAIN_PROPOSAL_STATE_PREFIX_INVALID", invalid);
            }
            prefix.add(state.stateKind());
        }
        try {
            requested.validateNextFor(prefix);
        } catch (IllegalArgumentException invalid) {
            throw new ProductChainPersistenceException(
                    "CHAIN_PROPOSAL_STATE_NOT_NEXT", invalid);
        }
        if (requested.stateKind()
                == ChainProposalState.REPLACED_BY_OFFICIAL_RESULT) {
            validateProposalOfficialAuthority(requested);
        }
    }

    private void validateProposalOfficialAuthority(
            ProposalStateEventRecord requested) {
        ProposalOfficialAuthorityType authorityType;
        try {
            authorityType = ProposalOfficialAuthorityType.valueOf(
                    requested.officialAuthorityType());
        } catch (IllegalArgumentException invalid) {
            throw new ProductChainPersistenceException(
                    "CHAIN_PROPOSAL_OFFICIAL_AUTHORITY_TYPE_UNSUPPORTED",
                    invalid);
        }
        if (authorityType == ProposalOfficialAuthorityType.PLAN_BINDING) {
            if (!proposalOwnsPlanBinding(
                    requested.taskId(), requested.proposalId(),
                    requested.officialAuthorityRef())) {
                throw new ProductChainPersistenceException(
                        "CHAIN_PROPOSAL_OFFICIAL_AUTHORITY_INVALID");
            }
            return;
        }
        if (authorityType == ProposalOfficialAuthorityType.PLAN) {
            List<ChainPersistenceRecords.PlanBindingRecord> bindings = findAll(
                    "agent_v2_chain_plan_bindings",
                    ChainPersistenceRecords.PlanBindingRecord.class,
                    Map.of("task_id", requested.taskId(),
                            "plan_id", requested.officialAuthorityRef()),
                    "plan_revision_number");
            if (bindings.stream().noneMatch(binding ->
                    proposalOwnsPlanBinding(requested.proposalId(), binding))) {
                throw new ProductChainPersistenceException(
                        "CHAIN_PROPOSAL_OFFICIAL_AUTHORITY_INVALID");
            }
            return;
        }
        Map<String, Object> parameters = Map.of(
                "taskId", requested.taskId(),
                "proposalId", requested.proposalId(),
                "authorityRef", requested.officialAuthorityRef());
        String sql = switch (authorityType) {
            case ROUTE_DECISION -> """
                    SELECT COUNT(*)
                      FROM agent_v2_chain_route_decisions
                     WHERE task_id = :taskId
                       AND proposal_id = :proposalId
                       AND route_decision_id = :authorityRef
                    """;
            case PLAN_BINDING, PLAN -> throw new IllegalStateException(
                    "Plan authorities are validated before SQL dispatch");
            case INSTRUCTION_DISPOSITION -> """
                    SELECT COUNT(*)
                      FROM agent_v2_chain_instruction_dispositions
                     WHERE task_id = :taskId
                       AND proposal_id = :proposalId
                       AND disposition_id = :authorityRef
                    """;
            case ACTION_BINDING -> """
                    SELECT COUNT(*)
                      FROM agent_v2_chain_action_bindings
                     WHERE task_id = :taskId
                       AND proposal_id = :proposalId
                       AND action_id = :authorityRef
                    """;
            case ACTION_RECEIPT_STEP_BLOCK -> """
                    SELECT COUNT(*)
                      FROM agent_v2_chain_action_receipt_step_blocks block
                     WHERE block.task_id = :taskId
                       AND block.repair_proposal_id = :proposalId
                       AND block.step_block_id = :authorityRef
                    """;
            case CANDIDATE_STEP_RESULT -> """
                    SELECT COUNT(*)
                      FROM agent_v2_chain_candidate_step_results
                     WHERE task_id = :taskId
                       AND proposal_id = :proposalId
                       AND candidate_result_id = :authorityRef
                    """;
            case REVIEW_DECISION -> """
                    SELECT COUNT(*)
                      FROM agent_v2_chain_review_decisions
                     WHERE task_id = :taskId
                       AND proposal_id = :proposalId
                       AND review_decision_id = :authorityRef
                    """;
            case PENDING_ITEM -> """
                    SELECT COUNT(*)
                      FROM agent_v2_chain_pending_items
                     WHERE task_id = :taskId
                       AND source_proposal_id = :proposalId
                       AND gap_id = :authorityRef
                    """;
            case ACCEPTED_RESULT -> """
                    SELECT COUNT(*)
                      FROM agent_v2_chain_accepted_results accepted
                      JOIN agent_v2_chain_candidate_step_results candidate
                        ON candidate.task_id = accepted.task_id
                       AND candidate.candidate_result_id =
                           accepted.candidate_result_id
                      JOIN agent_v2_chain_review_decisions review
                        ON review.task_id = accepted.task_id
                       AND review.review_decision_id =
                           accepted.review_decision_id
                     WHERE accepted.task_id = :taskId
                       AND accepted.accepted_result_id = :authorityRef
                       AND (candidate.proposal_id = :proposalId
                            OR review.proposal_id = :proposalId)
                    """;
            case TASK_OUTCOME -> """
                    SELECT COUNT(*)
                      FROM agent_v2_chain_task_outcomes
                     WHERE task_id = :taskId
                       AND outcome_id = :authorityRef
                    """;
            case DELIVERY -> """
                    SELECT COUNT(*)
                      FROM agent_v2_chain_deliveries delivery
                      JOIN agent_v2_chain_model_proposals proposal
                        ON proposal.task_id = delivery.task_id
                       AND proposal.proposal_id = :proposalId
                     WHERE delivery.task_id = :taskId
                       AND delivery.delivery_id = :authorityRef
                       AND (proposal.body_authority_ref IS NULL
                            OR proposal.body_authority_ref =
                               delivery.answer_content_id)
                    """;
            case ANSWER -> """
                    SELECT COUNT(*)
                      FROM agent_v2_chain_contents content
                      JOIN agent_v2_chain_model_proposals proposal
                        ON proposal.task_id = content.task_id
                       AND proposal.proposal_id = :proposalId
                       AND proposal.body_authority_ref = content.content_id
                     WHERE content.task_id = :taskId
                       AND content.content_id = :authorityRef
                       AND content.content_kind = 'ANSWER_BODY'
                    """;
        };
        if (scalar(sql, parameters) != 1) {
            throw new ProductChainPersistenceException(
                    "CHAIN_PROPOSAL_OFFICIAL_AUTHORITY_INVALID");
        }
    }

    private boolean proposalOwnsPlanBinding(
            String taskId, String proposalId, String planBindingId) {
        return find("agent_v2_chain_plan_bindings",
                ChainPersistenceRecords.PlanBindingRecord.class,
                Map.of("task_id", taskId,
                        "plan_binding_id", planBindingId), false)
                .map(binding -> proposalOwnsPlanBinding(proposalId, binding))
                .orElse(false);
    }

    private boolean proposalOwnsPlanBinding(
            String proposalId,
            ChainPersistenceRecords.PlanBindingRecord binding) {
        String source = binding.taskId() + "\0" + binding.instructionId()
                + "\0" + proposalId + "\0" + binding.taskFrameId()
                + "\0" + binding.planId() + "\0"
                + binding.planRevisionId() + "\0"
                + Objects.toString(binding.transitionId(), "NONE");
        return binding.planBindingId().equals(
                "plan-binding." + ProductChainRecordCodec.sha256(source));
    }

    private <T extends Record & TaskAuthorityFact>
            Optional<AuthoritativeAppendResult<T>>
            authoritativeReplay(
                    String table, Class<T> type, AuthoritativeFact<T> requested,
                    Map<String, Object> factIdentity) {
        Optional<AuthorityEventRecord> event = find(
                "agent_v2_chain_authority_events", AuthorityEventRecord.class,
                Map.of("event_id", requested.event().eventId()), false);
        Optional<T> fact = find(table, type, factIdentity, false);
        if (event.isEmpty() && fact.isEmpty()) {
            return Optional.empty();
        }
        if (event.isEmpty() || fact.isEmpty()
                || !sameEvent(event.get(), requested.event())
                || !canonicalEquals(fact.get(), requested.fact())) {
            throw new ProductChainPersistenceException(
                    "CHAIN_CONFLICTING_REPLAY");
        }
        return Optional.of(new AuthoritativeAppendResult<>(
                event.get(), fact.get(), true));
    }

    private <T extends Record> Optional<T> find(
            String table, Class<T> type, Map<String, Object> where,
            boolean lock) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM " + table + whereClause(where)
                        + (lock ? " FOR UPDATE" : ""),
                new MapSqlParameterSource(where));
        if (rows.size() > 1) {
            throw new ProductChainPersistenceException(
                    "CHAIN_AUTHORITY_AMBIGUOUS");
        }
        return rows.stream().findFirst().map(row -> codec.decode(type, row));
    }

    private void insert(String table, Record value) {
        insert(table, value, time.now());
    }

    private void insert(
            String table, Record value, Instant authoritativeTime) {
        Map<String, Object> columns = codec.encode(value);
        java.sql.Timestamp auditTime = java.sql.Timestamp.from(
                authoritativeTime);
        if (columns.containsKey("created_at")) {
            columns.put("created_at", auditTime);
        }
        if (columns.get("committed_at") != null) {
            columns.put("committed_at", auditTime);
        }
        StringJoiner names = new StringJoiner(",");
        StringJoiner values = new StringJoiner(",");
        columns.keySet().forEach(column -> {
            names.add(column);
            values.add(":" + column);
        });
        jdbc.update("INSERT INTO " + table + "(" + names + ") VALUES ("
                        + values + ")",
                new MapSqlParameterSource(columns));
    }

    private static String whereClause(Map<String, Object> where) {
        if (where.isEmpty()) {
            return "";
        }
        List<String> predicates = new ArrayList<>();
        where.keySet().forEach(column -> predicates.add(
                column + " = :" + column));
        return " WHERE " + String.join(" AND ", predicates);
    }

    private <T extends Record> AppendResult<T> replay(
            T existing, T requested) {
        return replay(existing, requested, this::canonicalEquals);
    }

    private static <T extends Record> AppendResult<T> replay(
            T existing, T requested,
            java.util.function.BiPredicate<T, T> replayIdentity) {
        if (!replayIdentity.test(existing, requested)) {
            throw new ProductChainPersistenceException(
                    "CHAIN_CONFLICTING_REPLAY");
        }
        return new AppendResult<>(existing, true);
    }

    private boolean canonicalEquals(Record existing, Record requested) {
        Map<String, Object> expected = new java.util.LinkedHashMap<>(
                codec.encode(requested));
        Map<String, Object> actual = new java.util.LinkedHashMap<>(
                codec.encode(existing));
        removeAuditTimes(expected);
        removeAuditTimes(actual);
        return Objects.equals(actual, expected);
    }

    private static boolean sameEvent(
            AuthorityEventRecord stored, AuthorityEventRequest requested) {
        return stored.eventId().equals(requested.eventId())
                && stored.taskId().equals(requested.taskId())
                && stored.eventType().equals(requested.eventType())
                && Objects.equals(stored.transitionId(), requested.transitionId())
                && stored.sourceIdentitySha256().equals(
                        requested.sourceIdentitySha256());
    }

    private static void removeAuditTimes(Map<String, Object> fields) {
        fields.remove("created_at");
        fields.remove("committed_at");
        fields.remove("completed_at");
    }

    private static void verifyFactIdentity(
            AuthoritativeFact<? extends TaskAuthorityFact> requested) {
        TaskAuthorityFact fact = requested.fact();
        if (!Objects.equals(fact.eventId(), requested.event().eventId())
                || !Objects.equals(fact.taskId(), requested.event().taskId())) {
            throw new ProductChainPersistenceException(
                    "CHAIN_AUTHORITY_EVENT_FACT_MISMATCH");
        }
        ProductChainAuthorityEventPolicy.verify(requested.event(), fact);
    }
}
