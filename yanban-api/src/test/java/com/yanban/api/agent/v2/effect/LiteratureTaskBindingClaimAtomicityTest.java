package com.yanban.api.agent.v2.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yanban.api.agent.v2.compatibility.literature.LiteratureDeliveryTaskBindingService;
import com.yanban.paper.domain.LiteratureSearchTask;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2effect_binding_atomicity;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        ProductEffectExecutionClaimRepository.class,
        ProductEffectExecutionClaimTransactions.class,
        ProductEffectOutcomeCodec.class,
        ProductEffectOutcomeMarkerReader.class,
        ProductEffectIntentRepositoryAdapter.class,
        ProductEffectIntentTransactions.class,
        ProductEffectIntentCodec.class,
        ProductReceiptCodec.class,
        ProductReceiptMarkerReader.class,
        ProductStepRecoveryTransactions.class,
        ProductActiveStepReplanMarkerReader.class,
        ProductActiveStepReplanCodec.class,
        ProductStepInterruptionMarkerReader.class,
        ProductStepInterruptionCodec.class,
        ProductStepCompletionMarkerReader.class,
        ProductStepCompletionCodec.class,
        ProductPlanExecutionContextCodec.class,
        ProductPlanBootstrapCodec.class,
        ProductExecutionStartCodec.class,
        ProductStepActivationCodec.class,
        ProductEffectExecutionClaimRepositoryTest.Configuration.class,
        LiteratureDeliveryTaskBindingService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LiteratureTaskBindingClaimAtomicityTest {
    @jakarta.annotation.Resource
    private org.springframework.context.ApplicationContext context;
    @jakarta.annotation.Resource
    private LiteratureDeliveryTaskBindingService bindings;
    @jakarta.annotation.Resource
    private JdbcTemplate jdbc;

    @Test
    void postCallbackLeaseFailureRollsBackTaskBindingClaimReceiptAndOutcome() {
        ProductEffectExecutionClaimRepositoryTest harness =
                ProductEffectExecutionClaimRepositoryTest.harness(context);
        harness.clearDatabase();
        var scenario = harness.scenario("binding-rollback");
        jdbc.update("""
                insert into agent_v2_literature_deliveries
                (user_id,session_id,client_request_id,request_sha256,
                 query_text,top_k,year_from,include_bibtex,
                 user_message_id,turn_id,lease_owner_id,lease_token,
                 lease_expires_at,status,created_at,updated_at)
                values (7,9,'request',
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        'graph retrieval',8,null,true,
                        11,42,'owner','token',current_timestamp,
                        'RUNNING',current_timestamp,current_timestamp)
                """);
        var request = new ProductEffectExecutionClaimRequest(
                scenario.recovery(), scenario.lease(), scenario.intent(),
                scenario.lease().leaseToken(),
                scenario.lease().fencingToken(),
                ProductStepActivationTestFixtures.NOW.plusSeconds(2),
                () -> {
                    LiteratureSearchTask task =
                            harness.literatureTasks.saveAndFlush(
                                    new LiteratureSearchTask(
                                            7L, null,
                                            "graph retrieval",
                                            "graph retrieval", 8,
                                            null, true, "PENDING", "QUEUED",
                                            requestId(scenario.intent().intent()
                                                    .toolCallId().value()),
                                            "task-idem"));
                    ExecutionReceipt receipt = new ExecutionReceipt(
                            new ReceiptId("receipt-binding-rollback"),
                            scenario.intent().intent().toolCallId(),
                            ReceiptStatus.SUCCESS,
                            ProductStepActivationTestFixtures.NOW
                                    .plusSeconds(3),
                            scenario.lease().expiresAt(),
                            Optional.of(0), Optional.empty(),
                            OutputCapture.inline(
                                    "{\"taskId\":" + task.getId()
                                            + ",\"clientRequestId\":\""
                                            + requestId(scenario.intent()
                                                    .intent().toolCallId()
                                                    .value())
                                            + "\"}",
                                    false),
                            OutputCapture.empty(), List.of(),
                            Optional.empty(), List.of());
                    bindings.bindSuccessfulReceipt(7L, 42L, receipt);
                    return receipt;
                });

        ProductEffectExecutionClaimException failure = assertThrows(
                ProductEffectExecutionClaimException.class,
                () -> harness.repository.execute(request));

        assertEquals("authority.leaseAfterExecution", failure.path());
        assertEquals(0, harness.literatureTasks.count());
        assertEquals(0, harness.claimRows.count());
        assertEquals(0, harness.receiptRows.count());
        assertEquals(0, harness.resultRows.count());
        assertNull(jdbc.queryForObject("""
                select literature_task_id
                from agent_v2_literature_deliveries
                where user_id=7 and session_id=9
                  and client_request_id='request'
                """, Long.class));
    }

    private static String requestId(String toolCallId) {
        try {
            byte[] digest = java.security.MessageDigest
                    .getInstance("SHA-256").digest(
                            ("v2-literature-request\0" + toolCallId)
                                    .getBytes(java.nio.charset.StandardCharsets
                                            .UTF_8));
            return "v2-literature-request."
                    + java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
