package com.yanban.api.agent.v2.chain.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ChainMigrationParityTest {
    private static final List<String> APPLICABILITY_TUPLE = List.of(
            "accepted_result_id", "source_type", "source_decision_id",
            "target_task_frame_id", "target_plan_id",
            "target_plan_revision_id", "target_candidate_key",
            "target_instruction_version_id");

    @Test
    void mysqlAndH2MigrationsHaveEquivalentPhysicalContracts()
            throws Exception {
        for (int version = 70; version <= 79; version++) {
            String file = ChainMigrationTestSupport.fileName(version);
            String mysql = ChainMigrationTestSupport.read(false, file);
            String h2 = ChainMigrationTestSupport.read(true, file);

            assertEquals(objects(mysql, "CREATE TABLE"),
                    objects(h2, "CREATE TABLE"), file + " tables");
            assertEquals(objects(mysql, "CONSTRAINT"),
                    objects(h2, "CONSTRAINT"), file + " constraints");
            assertEquals(objects(mysql, "CREATE INDEX"),
                    objects(h2, "CREATE INDEX"), file + " indexes");
            assertEquals(canonical(mysql), canonical(h2), file + " DDL");
        }
    }

    @Test
    void v80MysqlAndH2ProduceTheSameFinalConstraintContract()
            throws Exception {
        String v79 = ChainMigrationTestSupport.fileName(79);
        String v80 = ChainMigrationTestSupport.fileName(80);
        String mysql79 = ChainMigrationTestSupport.read(false, v79);
        String h279 = ChainMigrationTestSupport.read(true, v79);
        String mysql80 = ChainMigrationTestSupport.read(false, v80);
        String h280 = ChainMigrationTestSupport.read(true, v80);

        Set<String> mysqlFinal = new HashSet<>(
                objects(mysql79, "CONSTRAINT"));
        mysqlFinal.removeAll(droppedConstraints(mysql80));
        mysqlFinal.addAll(addedConstraints(mysql80));
        Set<String> h2Final = new HashSet<>(objects(h279, "CONSTRAINT"));
        h2Final.removeAll(droppedConstraints(h280));
        h2Final.addAll(addedConstraints(h280));

        assertEquals(mysqlFinal, h2Final, "V80 final constraints");
        assertTrue(mysqlFinal.contains(
                "ck_chain_action_failure_step_block_source"));
        assertTrue(!mysqlFinal.contains(
                "ck_chain_action_receipt_step_block_status"));
        assertTrue(!mysqlFinal.contains(
                "ck_chain_action_receipt_step_block_category"));
        assertEquals(checkBody(mysql80,
                        "ck_chain_action_failure_step_block_source"),
                checkBody(h280,
                        "ck_chain_action_failure_step_block_source"),
                "V80 polymorphic failure-source check");
    }

    @Test
    void v81MysqlAndH2HaveEquivalentTypedValidationContracts()
            throws Exception {
        String file = ChainMigrationTestSupport.fileName(81);
        String mysql = ChainMigrationTestSupport.read(false, file);
        String h2 = ChainMigrationTestSupport.read(true, file);

        assertEquals(objects(mysql, "CREATE TABLE"),
                objects(h2, "CREATE TABLE"), file + " tables");
        assertEquals(objects(mysql, "CONSTRAINT"),
                objects(h2, "CONSTRAINT"), file + " constraints");
        assertEquals(objects(mysql, "CREATE INDEX"),
                objects(h2, "CREATE INDEX"), file + " indexes");
        assertEquals(canonical(mysql), canonical(h2), file + " DDL");
    }

    @Test
    void v82MysqlAndH2HaveEquivalentValidationBundleContracts()
            throws Exception {
        String file = ChainMigrationTestSupport.fileName(82);
        String mysql = ChainMigrationTestSupport.read(false, file);
        String h2 = ChainMigrationTestSupport.read(true, file);

        assertEquals(objects(mysql, "CREATE TABLE"),
                objects(h2, "CREATE TABLE"), file + " tables");
        assertEquals(objects(mysql, "CONSTRAINT"),
                objects(h2, "CONSTRAINT"), file + " constraints");
        assertEquals(objects(mysql, "CREATE INDEX"),
                objects(h2, "CREATE INDEX"), file + " indexes");
        assertEquals(canonical(mysql), canonical(h2), file + " DDL");
    }

    @Test
    void v83MysqlAndH2HaveEquivalentTaskOutcomeAuthorityContracts()
            throws Exception {
        String file = ChainMigrationTestSupport.fileName(83);
        String mysql = ChainMigrationTestSupport.read(false, file);
        String h2 = ChainMigrationTestSupport.read(true, file);

        assertEquals(addedColumns(mysql), addedColumns(h2),
                file + " columns");
        assertEquals(addedConstraints(mysql), addedConstraints(h2),
                file + " constraints");
    }

    @Test
    void v84MysqlAndH2KeepOneBindingPerPlanRevision()
            throws Exception {
        String file = ChainMigrationTestSupport.fileName(84);
        String mysql = ChainMigrationTestSupport.read(false, file);
        String h2 = ChainMigrationTestSupport.read(true, file);

        assertTrue(mysql.contains("uk_chain_plan_binding_plan"));
        assertTrue(h2.contains("uk_chain_plan_binding_plan"));
        assertEquals(addedConstraints(mysql), addedConstraints(h2),
                file + " constraints");
        assertTrue(addedConstraints(mysql).contains(
                "uk_chain_plan_binding_revision"));
        assertEquals(Set.of("plan_id", "plan_revision_id"),
                uniqueColumns(mysql,
                        "uk_chain_plan_binding_revision"));
        assertEquals(uniqueColumns(mysql,
                        "uk_chain_plan_binding_revision"),
                uniqueColumns(h2,
                        "uk_chain_plan_binding_revision"));
    }

    @Test
    void v85MysqlAndH2AllowOnlyCompleteKnownStepCompletionFormats()
            throws Exception {
        String file = ChainMigrationTestSupport.fileName(85);
        String mysql = ChainMigrationTestSupport.read(false, file);
        String h2 = ChainMigrationTestSupport.read(true, file);

        assertEquals(droppedConstraints(mysql), droppedConstraints(h2),
                file + " dropped constraints");
        assertEquals(addedConstraints(mysql), addedConstraints(h2),
                file + " constraints");
        assertEquals(checkBody(mysql,
                        "ck_agent_v2_step_completion_formats"),
                checkBody(h2,
                        "ck_agent_v2_step_completion_formats"),
                file + " format check");
    }

    @Test
    void v86MysqlAndH2HaveEquivalentProgressionGuardContracts()
            throws Exception {
        String file = ChainMigrationTestSupport.fileName(86);
        String mysql = ChainMigrationTestSupport.read(false, file);
        String h2 = ChainMigrationTestSupport.read(true, file);

        assertEquals(objects(mysql, "CREATE TABLE"),
                objects(h2, "CREATE TABLE"), file + " tables");
        assertEquals(objects(mysql, "CONSTRAINT"),
                objects(h2, "CONSTRAINT"), file + " constraints");
        assertEquals(objects(mysql, "CREATE INDEX"),
                objects(h2, "CREATE INDEX"), file + " indexes");
        assertEquals(canonical(mysql), canonical(h2), file + " DDL");
    }

    @Test
    void mysqlApplicabilityIdentityKeepsTheCompleteTupleUnderIndexLimit()
            throws Exception {
        String sql = ChainMigrationTestSupport.read(false,
                ChainMigrationTestSupport.fileName(72));
        Matcher table = Pattern.compile(
                "(?is)CREATE TABLE agent_v2_chain_result_applicability\\s*"
                        + "\\((.*?)\\);").matcher(sql);
        assertTrue(table.find(), "applicability table");
        String ddl = table.group(1);

        Matcher unique = Pattern.compile(
                "(?is)CONSTRAINT\\s+uk_chain_applicability_tuple\\s+"
                        + "UNIQUE\\s*\\((.*?)\\)").matcher(ddl);
        assertTrue(unique.find(), "complete applicability tuple UK");
        List<String> columns = List.of(unique.group(1)
                .replaceAll("\\s+", "").split(","));
        assertEquals(APPLICABILITY_TUPLE, columns);

        int worstCaseBytes = 0;
        for (String column : APPLICABILITY_TUPLE) {
            Matcher definition = Pattern.compile(
                    "(?is)\\b" + Pattern.quote(column)
                            + "\\s+VARCHAR\\((\\d+)\\)\\s+"
                            + "CHARACTER SET ascii COLLATE ascii_bin\\s+"
                            + "NOT NULL").matcher(ddl);
            assertTrue(definition.find(), column + " must use one-byte ASCII");
            int maxCharacters = Integer.parseInt(definition.group(1));
            worstCaseBytes += maxCharacters + 2;
        }
        assertTrue(worstCaseBytes < 3072,
                "applicability tuple worst-case key bytes=" + worstCaseBytes);
        assertTrue(Pattern.compile(
                "(?is)CREATE TABLE agent_v2_chain_accepted_results\\s*\\(.*?"
                        + "accepted_result_id\\s+VARCHAR\\(128\\)\\s+"
                        + "CHARACTER SET ascii COLLATE ascii_bin\\s+NOT NULL"
        ).matcher(sql).find(),
                "accepted-result parent key must use the same ASCII collation");
    }

    private static Set<String> objects(String sql, String keyword) {
        Pattern pattern = Pattern.compile("(?i)\\b"
                + Pattern.quote(keyword) + "\\s+([a-z0-9_]+)");
        Set<String> result = new HashSet<>();
        Matcher matcher = pattern.matcher(sql);
        while (matcher.find()) {
            result.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private static Set<String> droppedConstraints(String sql) {
        Pattern pattern = Pattern.compile(
                "(?i)\\bDROP\\s+(?:CHECK|CONSTRAINT)\\s+([a-z0-9_]+)");
        Set<String> result = new HashSet<>();
        Matcher matcher = pattern.matcher(sql);
        while (matcher.find()) {
            result.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private static Set<String> addedConstraints(String sql) {
        Pattern pattern = Pattern.compile(
                "(?i)\\bADD\\s+CONSTRAINT\\s+([a-z0-9_]+)");
        Set<String> result = new HashSet<>();
        Matcher matcher = pattern.matcher(sql);
        while (matcher.find()) {
            result.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private static Set<String> addedColumns(String sql) {
        Pattern pattern = Pattern.compile(
                "(?i)\\bADD\\s+COLUMN\\s+([a-z0-9_]+)");
        Set<String> result = new HashSet<>();
        Matcher matcher = pattern.matcher(sql);
        while (matcher.find()) {
            result.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private static Set<String> uniqueColumns(
            String sql, String constraint) {
        Matcher matcher = Pattern.compile(
                "(?is)ADD\\s+CONSTRAINT\\s+" + Pattern.quote(constraint)
                        + "\\s+UNIQUE\\s*\\(([^)]+)\\)")
                .matcher(sql);
        assertTrue(matcher.find(), constraint + " unique columns");
        return Set.of(matcher.group(1).replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT).split(","));
    }

    private static String checkBody(String sql, String constraint) {
        Matcher matcher = Pattern.compile(
                "(?is)ADD\\s+CONSTRAINT\\s+" + Pattern.quote(constraint)
                        + "\\s+CHECK\\s*\\((.*)\\)\\s*;")
                .matcher(sql);
        assertTrue(matcher.find(), constraint + " check body");
        return matcher.group(1).replaceAll("\\s+", "")
                .toUpperCase(Locale.ROOT);
    }

    private static String canonical(String sql) {
        String value = sql.replace("\ufeff", "")
                .replace("\r", "")
                .replace("LONGTEXT", "CHAIN_JSON_TEXT")
                .replace("CLOB", "CHAIN_JSON_TEXT")
                .replace("DATETIME(6)", "CHAIN_TIME_6")
                .replace("TIMESTAMP(6)", "CHAIN_TIME_6");
        value = value.replaceAll(
                "(?i)\\s+CHARACTER SET ascii COLLATE ascii_bin", "");
        value = value.replaceAll(
                "(?i)REGEXP_LIKE\\(([_a-z0-9]+),\\s*('[^']+')\\)",
                "$1 REGEXP $2");
        List<String> lines = new ArrayList<>();
        for (String line : value.split("\\n")) {
            String normalized = line.trim();
            if (normalized.matches("(?i)^[_a-z0-9]+\\s+.+\\s+NULL,$")) {
                normalized = normalized.replaceFirst("(?i)\\s+NULL,$", ",");
            }
            if (!normalized.isEmpty()) {
                lines.add(normalized.replaceAll("\\s+", " "));
            }
        }
        return String.join(" ", lines).toUpperCase(Locale.ROOT);
    }
}

final class ChainMigrationTestSupport {
    static final String HASH = "0".repeat(64);
    private static final String MAIN = "src/main/resources/db/migration/";
    private static final String H2 = "src/test/resources/db/migration-h2/";
    private static final List<String> FILES = List.of(
            "V70__create_agent_v2_chain_foundations_and_plan_replans.sql",
            "V71__create_agent_v2_chain_context_and_model_records.sql",
            "V72__create_agent_v2_chain_workflow_records.sql",
            "V73__create_agent_v2_chain_finalization_and_delivery_records.sql",
            "V74__create_agent_v2_chain_task_skill_snapshots.sql",
            "V75__create_agent_v2_chain_progression_claims.sql",
            "V76__create_agent_v2_chain_context_build_failures.sql",
            "V77__create_agent_v2_chain_candidate_materialization_failures.sql",
            "V78__create_agent_v2_chain_model_failure_step_blocks.sql",
            "V79__create_agent_v2_chain_action_receipt_step_blocks.sql",
            "V80__generalize_agent_v2_chain_action_failure_step_blocks.sql",
            "V81__create_agent_v2_chain_typed_validations.sql",
            "V82__create_agent_v2_chain_validation_bundles.sql",
            "V83__bind_task_outcomes_to_terminal_authorities.sql",
            "V84__allow_plan_revision_bindings.sql",
            "V85__allow_current_step_completion_format.sql",
            "V86__add_agent_v2_progression_guards.sql");

    private ChainMigrationTestSupport() {
    }

    static String fileName(int version) {
        return FILES.get(version - 70);
    }

    static String read(boolean h2, String file) throws Exception {
        return Files.readString(Path.of(h2 ? H2 : MAIN, file));
    }

    static Connection database(String label) throws Exception {
        String url = "jdbc:h2:mem:" + label + "-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Connection connection = DriverManager.getConnection(url, "sa", "");
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE agent_v2_plan_bootstraps (
                      plan_id VARCHAR(128) PRIMARY KEY)
                    """);
            statement.execute("""
                    CREATE TABLE agent_v2_effect_intents (
                      tool_call_id VARCHAR(128) PRIMARY KEY)
                    """);
            statement.execute("""
                    CREATE TABLE agent_v2_receipts (
                      receipt_id VARCHAR(128) PRIMARY KEY,
                      tool_call_id VARCHAR(128) NOT NULL,
                      payload_sha256 CHAR(64) NOT NULL,
                      payload_json CLOB NOT NULL,
                      UNIQUE (receipt_id, tool_call_id))
                    """);
            // V79 deliberately binds a failed chain action to the preserved
            // V50/V51 effect-result authority. Chain migration tests start at
            // V70, so provide only that pre-V70 foreign-key contract here.
            statement.execute("""
                    CREATE TABLE agent_v2_effect_results (
                      tool_call_id VARCHAR(128) NOT NULL,
                      receipt_id VARCHAR(128) NOT NULL,
                      PRIMARY KEY (tool_call_id),
                      UNIQUE (tool_call_id, receipt_id))
                    """);
        }
        return connection;
    }

    static void migrateThrough(Connection connection, int target)
            throws Exception {
        for (int version = 70; version <= target; version++) {
            execute(connection, read(true, fileName(version)));
        }
    }

    static void execute(Connection connection, String sql) throws Exception {
        String withoutBom = sql.replace("\ufeff", "");
        for (String fragment : withoutBom.split(";")) {
            if (!fragment.isBlank()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(fragment);
                }
            }
        }
    }

    static Set<String> chainTables(Connection connection) throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getTables(
                null, "PUBLIC", null, new String[]{"TABLE"})) {
            while (rows.next()) {
                String name = rows.getString("TABLE_NAME");
                if (name.startsWith("AGENT_V2_CHAIN_")
                        || name.equals("AGENT_V2_PLAN_REPLANS")) {
                    result.add(name);
                }
            }
        }
        return result;
    }

    static Set<String> columns(Connection connection, String table)
            throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getColumns(
                null, "PUBLIC", table, null)) {
            while (rows.next()) {
                result.add(rows.getString("COLUMN_NAME"));
            }
        }
        return result;
    }

    static Set<String> constraints(Connection connection, String table)
            throws Exception {
        Set<String> result = new HashSet<>();
        try (var statement = connection.prepareStatement("""
                SELECT constraint_name
                  FROM information_schema.table_constraints
                 WHERE table_schema = 'PUBLIC' AND table_name = ?
                """)) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(rows.getString(1).toUpperCase(Locale.ROOT));
                }
            }
        }
        return result;
    }

    static Set<String> indexes(Connection connection, String table)
            throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = connection.getMetaData().getIndexInfo(
                null, "PUBLIC", table, false, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                if (name != null) {
                    result.add(name.toUpperCase(Locale.ROOT));
                }
            }
        }
        return result;
    }

    static void seedFoundation(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO agent_v2_plan_bootstraps"
                    + "(plan_id) VALUES ('plan-1')");
            statement.execute("""
                    INSERT INTO agent_v2_chain_commands(
                      command_id,user_id,session_id,client_request_id,
                      command_kind,request_sha256,status,created_at)
                    VALUES ('command-1',7,8,'request-1','INITIAL',
                      REPEAT('0',64),'RECEIVED',CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO agent_v2_chain_tasks(
                      task_id,created_by_command_id,source_instruction_id,
                      user_id,session_id,turn_id,root_client_request_id,
                      root_request_sha256,next_event_sequence,created_at)
                    VALUES ('task-1','command-1','instruction-1',7,8,9,
                      'request-1',REPEAT('0',64),0,CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO agent_v2_chain_instructions(
                      instruction_id,command_id,session_id,origin_task_id,
                      message_id,body_sha256,message_identity_key,relation_kind,
                      effective_boundary_digest,created_at)
                    VALUES ('instruction-1','command-1',8,'task-1',10,
                      REPEAT('0',64),'MESSAGE:10','INITIAL',REPEAT('0',64),
                      CURRENT_TIMESTAMP)
                    """);
            authorityEvent(statement, "event-1", 1, "INSTRUCTION_BOUND");
            statement.execute("""
                    INSERT INTO agent_v2_chain_task_instruction_bindings(
                      task_id,event_id,instruction_id,
                      task_instruction_sequence,relation_role,created_at)
                    VALUES ('task-1','event-1','instruction-1',1,'ORIGIN',
                      CURRENT_TIMESTAMP)
                    """);
        }
    }

    static void seedSecondFoundation(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO agent_v2_plan_bootstraps"
                    + "(plan_id) VALUES ('plan-2')");
            statement.execute("""
                    INSERT INTO agent_v2_chain_commands(
                      command_id,user_id,session_id,client_request_id,
                      command_kind,request_sha256,status,created_at)
                    VALUES ('command-2',17,18,'request-2','INITIAL',
                      REPEAT('0',64),'RECEIVED',CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO agent_v2_chain_tasks(
                      task_id,created_by_command_id,source_instruction_id,
                      user_id,session_id,turn_id,root_client_request_id,
                      root_request_sha256,next_event_sequence,created_at)
                    VALUES ('task-2','command-2','instruction-2',17,18,19,
                      'request-2',REPEAT('0',64),0,CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO agent_v2_chain_instructions(
                      instruction_id,command_id,session_id,origin_task_id,
                      message_id,body_sha256,message_identity_key,relation_kind,
                      effective_boundary_digest,created_at)
                    VALUES ('instruction-2','command-2',18,'task-2',20,
                      REPEAT('0',64),'MESSAGE:20','INITIAL',REPEAT('0',64),
                      CURRENT_TIMESTAMP)
                    """);
            authorityEvent(statement, "task-2", "task-2-event-1", 1,
                    "INSTRUCTION_BOUND");
            statement.execute("""
                    INSERT INTO agent_v2_chain_task_instruction_bindings(
                      task_id,event_id,instruction_id,
                      task_instruction_sequence,relation_role,created_at)
                    VALUES ('task-2','task-2-event-1','instruction-2',1,
                      'ORIGIN',CURRENT_TIMESTAMP)
                    """);
        }
    }

    static void seedProposal(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO agent_v2_chain_context_revisions(
                      context_revision_id,task_id,role,work_state,call_reason,
                      instruction_id,projector_set_version,pagination_version,
                      runtime_policy_version,status,module_count,
                      request_manifest_format_version,request_manifest_json,
                      request_digest,completion_token,created_at,completed_at)
                    VALUES ('context-1','task-1','PLANNER','PLANNING','INITIAL',
                      'instruction-1','projectors-v1','pagination-v1',
                      'chain-runtime-policy-v1','COMPLETE',13,1,'{}',
                      REPEAT('0',64),'completion-1',CURRENT_TIMESTAMP,
                      CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO agent_v2_chain_model_invocations(
                      invocation_id,task_id,context_revision_id,completion_token,
                      role,work_state,call_reason,provider,model,
                      invocation_ordinal,runtime_policy_version,created_at)
                    VALUES ('invocation-1','task-1','context-1','completion-1',
                      'PLANNER','PLANNING','INITIAL','fake','fake',1,
                      'chain-runtime-policy-v1',CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO agent_v2_chain_model_proposals(
                      proposal_id,task_id,invocation_id,schema_version,role,
                      proposal_kind,payload_format_version,payload_sha256,
                      payload_json,source_refs_format_version,source_refs_sha256,
                      source_refs_json,created_at)
                    VALUES ('proposal-1','task-1','invocation-1',1,'PLANNER',
                      'DIRECT_ROUTE',1,REPEAT('0',64),'{}',1,REPEAT('0',64),
                      '{}',CURRENT_TIMESTAMP)
                    """);
        }
    }

    static void seedSecondProposal(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO agent_v2_chain_context_revisions(
                      context_revision_id,task_id,role,work_state,call_reason,
                      instruction_id,projector_set_version,pagination_version,
                      runtime_policy_version,status,module_count,
                      request_manifest_format_version,request_manifest_json,
                      request_digest,completion_token,created_at,completed_at)
                    VALUES ('context-2','task-2','PLANNER','PLANNING','INITIAL',
                      'instruction-2','projectors-v1','pagination-v1',
                      'chain-runtime-policy-v1','COMPLETE',13,1,'{}',
                      REPEAT('0',64),'completion-2',CURRENT_TIMESTAMP,
                      CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO agent_v2_chain_model_invocations(
                      invocation_id,task_id,context_revision_id,completion_token,
                      role,work_state,call_reason,provider,model,
                      invocation_ordinal,runtime_policy_version,created_at)
                    VALUES ('invocation-2','task-2','context-2','completion-2',
                      'PLANNER','PLANNING','INITIAL','fake','fake',1,
                      'chain-runtime-policy-v1',CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO agent_v2_chain_model_proposals(
                      proposal_id,task_id,invocation_id,schema_version,role,
                      proposal_kind,payload_format_version,payload_sha256,
                      payload_json,source_refs_format_version,source_refs_sha256,
                      source_refs_json,created_at)
                    VALUES ('proposal-2','task-2','invocation-2',1,'PLANNER',
                      'DIRECT_ROUTE',1,REPEAT('0',64),'{}',1,REPEAT('0',64),
                      '{}',CURRENT_TIMESTAMP)
                    """);
        }
    }

    static void seedCompleteTaskGraph(Connection connection) throws Exception {
        seedFoundation(connection);
        seedProposal(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO agent_v2_chain_context_modules(
                      context_revision_id,task_id,module_ordinal,module_kind,
                      presence_kind,source_version_format_version,
                      source_version_sha256,source_version_json,
                      read_boundary_format_version,read_boundary_sha256,
                      read_boundary_json,projection_version,pagination_version,
                      projection_parameters_format_version,
                      projection_parameters_sha256,projection_parameters_json,
                      projection_format_version,projection_digest,
                      projection_json,created_at)
                    VALUES ('context-1','task-1',1,'INSTRUCTION_CHAIN','PRESENT',
                      1,REPEAT('0',64),'{}',1,REPEAT('0',64),'{}','v1','v1',1,
                      REPEAT('0',64),'{}',1,REPEAT('0',64),'{}',
                      CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO agent_v2_chain_provider_attempts(
                      invocation_id,attempt_no,task_id,duration_ms,
                      schema_validation_status,proposal_validation_status,
                      created_at)
                    VALUES ('invocation-1',1,'task-1',1,'PASSED','PASSED',
                      CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO agent_v2_chain_contents(
                      content_id,task_id,invocation_id,content_kind,body,
                      body_sha256,media_type,created_at)
                    VALUES ('content-1','task-1','invocation-1',
                      'CANDIDATE_STEP_RESULT','body',REPEAT('0',64),
                      'text/plain',CURRENT_TIMESTAMP)
                    """);
            authorityEvent(statement, "event-2", 2, "PROPOSAL_ACCEPTED");
            statement.execute("""
                    INSERT INTO agent_v2_chain_proposal_state_events(
                      proposal_id,state_sequence,task_id,event_id,state_kind,
                      committed_at)
                    VALUES ('proposal-1',1,'task-1','event-2','ACCEPTED',
                      CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    INSERT INTO agent_v2_plan_replans(
                      replan_event_id,task_id,plan_id,source_event_sequence,
                      source_revision_id,source_revision_number,
                      result_revision_id,result_revision_number,
                      source_checkpoint_version,result_checkpoint_version,
                      result_event_sequence,lease_owner,fence_token,
                      request_format_version,request_sha256,request_json,
                      result_format_version,result_sha256,result_json,
                      committed_at)
                    VALUES ('replan-1','task-1','plan-1',1,'revision-1',1,
                      'revision-2',2,1,2,2,'owner',1,1,REPEAT('0',64),'{}',
                      1,REPEAT('0',64),'{}',CURRENT_TIMESTAMP)
                    """);

            authorityEvent(statement, "event-3", 3, "TRANSITION_OPENED");
            statement.execute("""
                    INSERT INTO agent_v2_chain_transitions(
                      transition_id,task_id,event_id,transition_type,
                      source_decision_id,target_identity_digest,created_at)
                    VALUES ('transition-1','task-1','event-3','ACCEPT_STEP',
                      'decision-1',REPEAT('0',64),CURRENT_TIMESTAMP)
                    """);
            authorityEvent(statement, "event-4", 4, "TRANSITION_STAGE");
            statement.execute("""
                    INSERT INTO agent_v2_chain_transition_stages(
                      transition_id,stage_code,task_id,event_id,stage_ordinal,
                      committed_at)
                    VALUES ('transition-1','OPEN','task-1','event-4',0,
                      CURRENT_TIMESTAMP)
                    """);
            authorityEvent(statement, "event-5", 5, "ROUTE_DECIDED");
            statement.execute("""
                    INSERT INTO agent_v2_chain_route_decisions(
                      route_decision_id,task_id,event_id,instruction_id,
                      proposal_id,decision_kind,decision_ordinal,route,
                      route_reason,needs_tool,needs_network,needs_project,
                      needs_persistent_progress,transition_id,created_at)
                    VALUES ('route-1','task-1','event-5','instruction-1',
                      'proposal-1','INITIAL',0,'PERSISTENT_PLAN_EXECUTE',
                      'persistent',1,0,1,1,'transition-1',CURRENT_TIMESTAMP)
                    """);
            authorityEvent(statement, "event-6", 6, "PLAN_BOUND");
            statement.execute("""
                    INSERT INTO agent_v2_chain_plan_bindings(
                      plan_binding_id,task_id,event_id,instruction_id,
                      route_decision_id,task_frame_id,plan_id,plan_revision_id,
                      plan_revision_number,authority_type,authority_id,
                      authority_sha256,transition_id,created_at)
                    VALUES ('binding-1','task-1','event-6','instruction-1',
                      'route-1','task-frame-1','plan-1','revision-2',2,
                      'PLAN_BOOTSTRAP','plan-1',REPEAT('0',64),'transition-1',
                      CURRENT_TIMESTAMP)
                    """);
            authorityEvent(statement, "event-7", 7, "CANDIDATE_RESULT");
            statement.execute("""
                    INSERT INTO agent_v2_chain_candidate_step_results(
                      candidate_result_id,task_id,event_id,proposal_id,
                      content_id,instruction_id,task_frame_id,plan_id,
                      plan_revision_id,plan_revision_number,step_id,
                      activation_event_id,artifact_id,candidate_fingerprint,
                      diff_digest,receipt_refs_format_version,
                      receipt_refs_sha256,receipt_refs_json,validation_id,
                      validation_request_digest,validation_receipt_digest,
                      evidence_refs_format_version,evidence_refs_sha256,
                      evidence_refs_json,version_fence_sha256,created_at)
                    VALUES ('candidate-result-1','task-1','event-7','proposal-1',
                      'content-1','instruction-1','task-frame-1','plan-1',
                      'revision-2',2,'step-1','activation-1',501,
                      REPEAT('0',64),REPEAT('0',64),1,REPEAT('0',64),'{}',
                      'validation-1',REPEAT('0',64),REPEAT('0',64),1,
                      REPEAT('0',64),'{}',REPEAT('0',64),CURRENT_TIMESTAMP)
                    """);
            authorityEvent(statement, "event-8", 8, "REVIEW_DECIDED");
            statement.execute("""
                    INSERT INTO agent_v2_chain_review_decisions(
                      review_decision_id,task_id,event_id,proposal_id,
                      review_object_type,review_object_id,decision_kind,reason,
                      fact_refs_format_version,fact_refs_sha256,fact_refs_json,
                      version_fence_sha256,created_at)
                    VALUES ('review-1','task-1','event-8','proposal-1',
                      'CANDIDATE_STEP_RESULT','candidate-result-1','ACCEPT_STEP',
                      'accepted',1,REPEAT('0',64),'{}',REPEAT('0',64),
                      CURRENT_TIMESTAMP)
                    """);
            authorityEvent(statement, "event-9", 9, "RESULT_ACCEPTED");
            statement.execute("""
                    INSERT INTO agent_v2_chain_accepted_results(
                      accepted_result_id,task_id,event_id,candidate_result_id,
                      review_decision_id,transition_id,content_id,
                      accepted_identity_sha256,created_at)
                    VALUES ('accepted-1','task-1','event-9',
                      'candidate-result-1','review-1','transition-1','content-1',
                      REPEAT('0',64),CURRENT_TIMESTAMP)
                    """);
            authorityEvent(statement, "event-10", 10, "APPLICABILITY");
            statement.execute("""
                    INSERT INTO agent_v2_chain_result_applicability(
                      applicability_id,task_id,event_id,accepted_result_id,
                      source_type,source_decision_id,target_task_frame_id,
                      target_plan_id,target_plan_revision_id,
                      target_candidate_key,target_instruction_version_id,
                      conclusion,reason,created_at)
                    VALUES ('applicability-1','task-1','event-10','accepted-1',
                      'ACCEPT_STEP','transition-1','task-frame-1','plan-1',
                      'revision-2','candidate-1','instruction-1','APPLICABLE',
                      'same target',CURRENT_TIMESTAMP)
                    """);
            authorityEvent(statement, "event-11", 11, "PENDING_CREATED");
            statement.execute("""
                    INSERT INTO agent_v2_chain_pending_items(
                      gap_id,task_id,event_id,source_proposal_id,pending_type,
                      gap_identity_sha256,missing_fields_format_version,
                      missing_fields_sha256,missing_fields_json,
                      permission_scope,question,expected_format,validation_role,
                      resume_role,resume_position_format_version,
                      resume_position_sha256,resume_position_json,
                      version_fence_sha256,created_at)
                    VALUES ('gap-1','task-1','event-11','proposal-1','PERMISSION',
                      REPEAT('0',64),1,REPEAT('0',64),'{}','PROJECT_WRITE',
                      'Allow?', 'boolean','PLANNER','EXECUTOR',1,
                      REPEAT('0',64),'{}',REPEAT('0',64),CURRENT_TIMESTAMP)
                    """);
            authorityEvent(statement, "event-12", 12, "PENDING_STATE");
            statement.execute("""
                    INSERT INTO agent_v2_chain_pending_item_events(
                      gap_id,response_round,event_kind,task_id,event_id,
                      validation_invocation_id,detail_format_version,
                      detail_sha256,detail_json,committed_at)
                    VALUES ('gap-1',0,'PENDING','task-1','event-12',
                      'invocation-1',1,REPEAT('0',64),'{}',CURRENT_TIMESTAMP)
                    """);
            authorityEvent(statement, "event-13", 13, "PERMISSION_DECIDED");
            statement.execute("""
                    INSERT INTO agent_v2_chain_permission_decisions(
                      permission_decision_id,task_id,event_id,gap_id,
                      permission_scope,product_authority_type,
                      product_authority_ref,decision,reason,created_at)
                    VALUES ('permission-1','task-1','event-13','gap-1',
                      'PROJECT_WRITE','AUTHENTICATED_USER','user-7','GRANTED',
                      'allowed',CURRENT_TIMESTAMP)
                    """);
            authorityEvent(statement, "event-14", 14, "ACTION_BOUND");
            statement.execute("""
                    INSERT INTO agent_v2_chain_action_bindings(
                      action_id,task_id,event_id,proposal_id,attempt_no,
                      action_signature_sha256,idempotency_key,instruction_id,
                      task_frame_id,plan_id,plan_revision_id,step_id,
                      activation_event_id,workspace_id,base_candidate_key,
                      version_fence_sha256,created_at)
                    VALUES ('action-1','task-1','event-14','proposal-1',1,
                      REPEAT('0',64),'action-key-1','instruction-1',
                      'task-frame-1','plan-1','revision-2','step-1',
                      'activation-1','workspace-1','NONE',REPEAT('0',64),
                      CURRENT_TIMESTAMP)
                    """);
            authorityEvent(statement, "event-15", 15, "CANDIDATE_BOUND");
            statement.execute("""
                    INSERT INTO agent_v2_chain_workspace_candidates(
                      workspace_candidate_id,task_id,event_id,action_id,
                      workspace_id,base_project_version,artifact_id,
                      candidate_fingerprint,diff_digest,version_fence_sha256,
                      created_at)
                    VALUES ('workspace-candidate-1','task-1','event-15',
                      'action-1','workspace-1','project-v1',501,
                      REPEAT('0',64),REPEAT('0',64),REPEAT('0',64),
                      CURRENT_TIMESTAMP)
                    """);

            authorityEvent(statement, "event-16", 16, "READINESS");
            statement.execute("""
                    INSERT INTO agent_v2_chain_finalization_readiness(
                      readiness_id,task_id,event_id,transition_id,
                      readiness_scope_key,task_frame_id,final_plan_id,
                      final_plan_revision_id,final_plan_revision_number,
                      final_step_id,review_decision_id,
                      accepted_set_format_version,accepted_set_sha256,
                      accepted_set_json,applicability_cut_event_sequence,
                      artifact_id,candidate_key,workspace_id,validation_id,
                      validation_request_digest,validation_receipt_digest,
                      coverage_format_version,coverage_sha256,coverage_json,
                      publish_requirement,publish_requirement_digest,
                      instruction_id,project_version,created_at)
                    VALUES ('readiness-1','task-1','event-16','transition-1',
                      REPEAT('0',64),'task-frame-1','plan-1','revision-2',2,
                      'step-1','review-1',1,REPEAT('0',64),'{}',10,501,
                      'candidate-1','workspace-1','validation-1',
                      REPEAT('0',64),REPEAT('0',64),1,REPEAT('0',64),'{}',
                      'REQUIRED',REPEAT('0',64),'instruction-1','project-v1',
                      CURRENT_TIMESTAMP)
                    """);
            authorityEvent(statement, "event-17", 17, "FINALIZATION_CHECK");
            statement.execute("""
                    INSERT INTO agent_v2_chain_finalization_checks(
                      finalization_check_id,task_id,event_id,readiness_id,
                      transition_id,attempt_no,task_frame_id,
                      final_plan_revision_id,accepted_set_sha256,candidate_key,
                      workspace_id,validation_id,validation_request_digest,
                      validation_receipt_digest,publish_requirement_digest,
                      instruction_id,project_version,input_digest,
                      content_digest,publish_digest,result_status,error_code,
                      failure_disposition,runtime_policy_version,created_at)
                    VALUES ('check-1','task-1','event-17','readiness-1',
                      'transition-1',1,'task-frame-1','revision-2',
                      REPEAT('0',64),'candidate-1','workspace-1','validation-1',
                      REPEAT('0',64),REPEAT('0',64),REPEAT('0',64),
                      'instruction-1','project-v1',REPEAT('0',64),
                      REPEAT('0',64),REPEAT('0',64),'PASSED',NULL,'NONE',
                      'chain-runtime-policy-v1',CURRENT_TIMESTAMP)
                    """);
            authorityEvent(statement, "event-18", 18, "TASK_OUTCOME");
            statement.execute("""
                    INSERT INTO agent_v2_chain_task_outcomes(
                      outcome_id,task_id,event_id,source_command_id,
                      outcome_type,instruction_id,task_frame_id,final_plan_id,
                      final_plan_revision_id,coverage_format_version,
                      coverage_sha256,coverage_json,accepted_set_format_version,
                      accepted_set_sha256,accepted_set_json,final_artifact_id,
                      candidate_key,validation_id,incomplete_items_format_version,
                      incomplete_items_sha256,incomplete_items_json,
                      limitations_format_version,limitations_sha256,
                      limitations_json,risks_format_version,risks_sha256,
                      risks_json,source_decision_id,created_at)
                    VALUES ('outcome-1','task-1','event-18','command-1',
                      'COMPLETED','instruction-1','task-frame-1','plan-1',
                      'revision-2',1,REPEAT('0',64),'{}',1,REPEAT('0',64),'{}',
                      501,'candidate-1','validation-1',1,REPEAT('0',64),'{}',1,
                      REPEAT('0',64),'{}',1,REPEAT('0',64),'{}','transition-1',
                      CURRENT_TIMESTAMP)
                    """);
            authorityEvent(statement, "event-19", 19, "DELIVERY_CREATED");
            statement.execute("""
                    INSERT INTO agent_v2_chain_deliveries(
                      delivery_id,task_id,event_id,source_command_id,
                      route_decision_id,task_outcome_id,gap_id,decision_id,
                      answer_content_id,assistant_message_id,created_at)
                    VALUES ('delivery-1','task-1','event-19','command-1',
                      'route-1','outcome-1','gap-1','decision-1','content-1',77,
                      CURRENT_TIMESTAMP)
                    """);
            authorityEvent(statement, "event-20", 20, "DELIVERY_STATE");
            statement.execute("""
                    INSERT INTO agent_v2_chain_delivery_events(
                      delivery_id,event_sequence,task_id,event_id,event_kind,
                      attempt_no,runtime_policy_version,committed_at)
                    VALUES ('delivery-1',1,'task-1','event-20','PENDING',0,
                      'chain-runtime-policy-v1',CURRENT_TIMESTAMP)
                    """);
        }
    }

    static void authorityEvent(
            Statement statement, String id, long sequence, String type)
            throws SQLException {
        authorityEvent(statement, "task-1", id, sequence, type);
    }

    static void authorityEvent(
            Statement statement, String taskId, String id, long sequence,
            String type) throws SQLException {
        statement.execute("""
                INSERT INTO agent_v2_chain_authority_events(
                  event_id,task_id,event_sequence,event_type,
                  source_identity_sha256,committed_at)
                VALUES ('%s','%s',%d,'%s',REPEAT('0',64),CURRENT_TIMESTAMP)
                """.formatted(id, taskId, sequence, type));
    }
}
