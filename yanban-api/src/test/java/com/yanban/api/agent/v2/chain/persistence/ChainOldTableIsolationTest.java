package com.yanban.api.agent.v2.chain.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ChainOldTableIsolationTest {
    private static final Set<String> ROOT_ORDER_FOREIGN_KEYS = Set.of(
            "fk_chain_task_command",
            "fk_chain_instruction_command",
            "fk_chain_task_instruction_instruction",
            "fk_chain_context_instruction",
            "fk_chain_route_instruction",
            "fk_chain_disposition_instruction",
            "fk_chain_plan_binding_instruction",
            "fk_chain_candidate_result_instruction",
            "fk_chain_pending_item_instruction",
            "fk_chain_action_instruction",
            "fk_chain_readiness_instruction",
            "fk_chain_finalization_check_instruction",
            "fk_chain_task_outcome_command",
            "fk_chain_task_outcome_instruction",
            "fk_chain_delivery_command");
    private static final Set<String> OLD_AUTHORITIES = Set.of(
            "agent_v2_final_syntheses",
            "agent_v2_literature_deliveries",
            "agent_v2_project_analysis_deliveries",
            "agent_v2_project_analysis_steps",
            "agent_v2_project_candidate_deliveries",
            "agent_v2_project_candidate_steps",
            "candidate_validation_repairs",
            "agent_v2_turn_intakes",
            "agent_v2_adaptive_turns",
            "agent_v2_step_results",
            "agent_context_snapshots",
            "agent_context_snapshot_sections",
            "agent_v2_natural_candidate_authorities");
    private static final Set<String> OLD_AUTHORITY_TYPES = Set.of(
            "ProductFinalSynthesisEntity",
            "ProductFinalSynthesisJpaRepository",
            "ProductFinalSynthesisRepositoryAdapter",
            "LiteratureDeliveryEntity",
            "LiteratureDeliveryJpaRepository",
            "ProjectAnalysisDeliveryEntity",
            "ProjectAnalysisDeliveryJpaRepository",
            "ProjectAnalysisStepAuthorityEntity",
            "ProjectAnalysisStepAuthorityJpaRepository",
            "ProjectCandidateDeliveryEntity",
            "ProjectCandidateDeliveryJpaRepository",
            "ProjectCandidateStepAuthorityEntity",
            "ProjectCandidateStepAuthorityJpaRepository",
            "CandidateValidationRepair",
            "V2TurnIntakeEntity",
            "V2TurnIntakeJpaRepository",
            "V2AdaptiveTurnEntity",
            "V2AdaptiveTurnRepository",
            "V2StepResultEntity",
            "V2StepResultJpaRepository",
            "AgentContextSnapshot",
            "AgentContextSnapshotRepository",
            "AgentContextSnapshotService",
            "NaturalLanguageCandidateAuthorityStore");

    @Test
    void newMigrationsAndChainSourcesNeverReadOrBackfillV55ToV69()
            throws Exception {
        Pattern dataMutation = Pattern.compile(
                "(?im)^\\s*(INSERT|UPDATE|DELETE|ALTER|DROP|RENAME)\\b");
        for (int version = 70; version <= 73; version++) {
            for (boolean h2 : List.of(false, true)) {
                String file = ChainMigrationTestSupport.fileName(version);
                String sql = ChainMigrationTestSupport.read(h2, file);
                String lower = sql.toLowerCase(Locale.ROOT);
                for (String old : OLD_AUTHORITIES) {
                    assertFalse(lower.contains(old), file + " references " + old);
                }
                assertFalse(dataMutation.matcher(sql).find(),
                        file + " contains data migration/backfill");
            }
        }

        for (Path source : chainProductionSources()) {
            String contents = Files.readString(source);
            String lower = contents.toLowerCase(Locale.ROOT);
            for (String old : OLD_AUTHORITIES) {
                assertFalse(lower.contains(old), source + " reads " + old);
            }
            for (String oldType : OLD_AUTHORITY_TYPES) {
                assertFalse(contents.contains(oldType),
                        source + " imports or reads " + oldType);
            }
        }
    }

    @Test
    void chainTablesDoNotBlockStableAuthorityDeletionOrder()
            throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "chain-delete-order")) {
            ChainMigrationTestSupport.migrateThrough(connection, 73);
            assertEquals(Set.of("AGENT_V2_PLAN_REPLANS"),
                    exportedKeyTables(connection, "AGENT_V2_PLAN_BOOTSTRAPS"));
            assertEquals(Set.of(),
                    exportedKeyTables(connection, "AGENT_V2_EFFECT_INTENTS"));
        }
    }

    @Test
    void everyTaskOwnedChainLocalForeignKeyUsesDeleteCascade()
            throws Exception {
        Pattern foreignKey = Pattern.compile(
                "(?ms)^\\s*CONSTRAINT\\s+(fk_[a-z0-9_]+)\\s+"
                        + "FOREIGN KEY.*?(?=^\\s*CONSTRAINT|^\\);)");
        Pattern chainTarget = Pattern.compile(
                "REFERENCES\\s+(agent_v2_chain_[a-z0-9_]+)",
                Pattern.CASE_INSENSITIVE);
        for (boolean h2 : List.of(false, true)) {
            Set<String> nonCascading = new HashSet<>();
            for (int version = 70; version <= 73; version++) {
                String sql = ChainMigrationTestSupport.read(
                        h2, ChainMigrationTestSupport.fileName(version));
                var foreignKeys = foreignKey.matcher(sql);
                while (foreignKeys.find()) {
                    String clause = foreignKeys.group();
                    if (chainTarget.matcher(clause).find()
                            && !clause.toUpperCase(Locale.ROOT)
                                    .contains("ON DELETE CASCADE")) {
                        nonCascading.add(foreignKeys.group(1));
                    }
                }
            }
            assertEquals(ROOT_ORDER_FOREIGN_KEYS, nonCascading,
                    (h2 ? "H2" : "MySQL") + " non-cascading chain FKs");
        }
    }

    @Test
    void everyTaskOwnedCrossTableReferenceCarriesTaskIdentityFirst()
            throws Exception {
        Pattern foreignKey = Pattern.compile(
                "(?ims)CONSTRAINT\\s+(fk_[a-z0-9_]+)\\s+"
                        + "FOREIGN KEY\\s*\\(([^)]*)\\)\\s*"
                        + "REFERENCES\\s+(agent_v2_chain_[a-z0-9_]+)"
                        + "\\s*\\(([^)]*)\\)");
        Set<String> globalOrRoot = Set.of(
                "agent_v2_chain_tasks",
                "agent_v2_chain_commands",
                "agent_v2_chain_instructions");
        for (boolean h2 : List.of(false, true)) {
            for (int version = 70; version <= 73; version++) {
                String sql = ChainMigrationTestSupport.read(
                        h2, ChainMigrationTestSupport.fileName(version));
                var references = foreignKey.matcher(sql);
                while (references.find()) {
                    if (globalOrRoot.contains(
                            references.group(3).toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    List<String> localColumns = columns(references.group(2));
                    List<String> targetColumns = columns(references.group(4));
                    String label = (h2 ? "H2 " : "MySQL ")
                            + references.group(1);
                    assertTrue(localColumns.size() >= 2, label);
                    assertTrue(targetColumns.size() >= 2, label);
                    assertEquals("task_id", localColumns.get(0), label);
                    assertEquals("task_id", targetColumns.get(0), label);
                }
            }
        }
    }

    @Test
    void deletingTaskClosesTheCompleteTaskOwnedChainGraph()
            throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "chain-task-cascade")) {
            ChainMigrationTestSupport.migrateThrough(connection, 73);
            ChainMigrationTestSupport.seedCompleteTaskGraph(connection);

            try (Statement statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate(
                        "DELETE FROM agent_v2_chain_tasks "
                                + "WHERE task_id = 'task-1'"));
            }
            for (String table : ChainMigrationTestSupport.chainTables(connection)) {
                if (ChainMigrationTestSupport.columns(connection, table)
                        .contains("TASK_ID")) {
                    assertEquals(0, rowCount(connection, table), table);
                }
            }

            try (Statement statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate(
                        "DELETE FROM agent_v2_chain_instructions "
                                + "WHERE instruction_id = 'instruction-1'"));
                assertEquals(1, statement.executeUpdate(
                        "DELETE FROM agent_v2_chain_commands "
                                + "WHERE command_id = 'command-1'"));
            }
            assertEquals(0, rowCount(connection, "AGENT_V2_CHAIN_INSTRUCTIONS"));
            assertEquals(0, rowCount(connection, "AGENT_V2_CHAIN_COMMANDS"));
        }
    }

    @Test
    void deletingOneTaskNeverCascadesIntoAnotherSessionTaskGraph()
            throws Exception {
        try (Connection connection = ChainMigrationTestSupport.database(
                "chain-task-session-isolation")) {
            ChainMigrationTestSupport.migrateThrough(connection, 73);
            ChainMigrationTestSupport.seedCompleteTaskGraph(connection);
            ChainMigrationTestSupport.seedSecondFoundation(connection);
            ChainMigrationTestSupport.seedSecondProposal(connection);

            try (Statement statement = connection.createStatement()) {
                ChainMigrationTestSupport.authorityEvent(statement, "task-2",
                        "task-2-event-2", 2, "ROUTE_DECISION");
                statement.execute("""
                        INSERT INTO agent_v2_chain_route_decisions(
                          route_decision_id,task_id,event_id,instruction_id,
                          proposal_id,decision_kind,decision_ordinal,route,
                          route_reason,direct_task_spec_format_version,
                          direct_task_spec_sha256,direct_task_spec_json,
                          user_constraints_format_version,
                          user_constraints_sha256,user_constraints_json,
                          answer_required_refs_format_version,
                          answer_required_refs_sha256,answer_required_refs_json,
                          needs_tool,needs_network,needs_project,
                          needs_persistent_progress,created_at)
                        VALUES ('route-2','task-2','task-2-event-2',
                          'instruction-2','proposal-2','INITIAL',0,'DIRECT',
                          'simple',1,REPEAT('0',64),'{}',1,REPEAT('0',64),'{}',
                          1,REPEAT('0',64),'{}',0,0,0,0,CURRENT_TIMESTAMP)
                        """);
                ChainMigrationTestSupport.authorityEvent(statement, "task-2",
                        "task-2-event-3", 3, "DELIVERY");
                statement.execute("""
                        INSERT INTO agent_v2_chain_deliveries(
                          delivery_id,task_id,event_id,source_command_id,
                          route_decision_id,created_at)
                        VALUES ('delivery-2','task-2','task-2-event-3',
                          'command-2','route-2',CURRENT_TIMESTAMP)
                        """);

                assertEquals(1, statement.executeUpdate(
                        "DELETE FROM agent_v2_chain_tasks "
                                + "WHERE task_id = 'task-1'"));
            }

            for (String table : List.of(
                    "AGENT_V2_CHAIN_TASKS",
                    "AGENT_V2_CHAIN_CONTEXT_REVISIONS",
                    "AGENT_V2_CHAIN_MODEL_INVOCATIONS",
                    "AGENT_V2_CHAIN_MODEL_PROPOSALS",
                    "AGENT_V2_CHAIN_ROUTE_DECISIONS",
                    "AGENT_V2_CHAIN_DELIVERIES")) {
                assertEquals(1, rowCount(connection, table, "task-2"), table);
            }
        }
    }

    private static int rowCount(Connection connection, String table)
            throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static List<String> columns(String value) {
        return Stream.of(value.split(","))
                .map(column -> column.trim().toLowerCase(Locale.ROOT))
                .toList();
    }

    private static int rowCount(
            Connection connection, String table, String taskId)
            throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE task_id = ?")) {
            statement.setString(1, taskId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static Set<String> exportedKeyTables(
            Connection connection, String table) throws Exception {
        java.util.HashSet<String> result = new java.util.HashSet<>();
        try (ResultSet rows = connection.getMetaData().getExportedKeys(
                null, "PUBLIC", table)) {
            while (rows.next()) {
                result.add(rows.getString("FKTABLE_NAME"));
            }
        }
        return result;
    }

    private static List<Path> chainProductionSources() throws Exception {
        List<Path> result = new ArrayList<>();
        for (Path root : List.of(
                Path.of("../agent-v2/agent-contracts/src/main/java/io/paperagent/v2/chain"),
                Path.of("../agent-v2/agent-persistence/src/main/java/io/paperagent/v2/chain"),
                Path.of("src/main/java/com/yanban/api/agent/v2/chain"))) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .forEach(result::add);
            }
        }
        return result;
    }
}
