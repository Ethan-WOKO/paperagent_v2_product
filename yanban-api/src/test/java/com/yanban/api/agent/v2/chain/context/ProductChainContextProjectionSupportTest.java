package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.context.ChainContextErrorCode;
import io.paperagent.v2.chain.context.ChainContextException;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.chain.context.ChainContextVersionMatrix;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductChainContextProjectionSupportTest {
    @Test
    void constructsPresentProjectionOnlyWhenRequiredFieldsExist() {
        var projection = ProductChainContextProjectionSupport.present(
                ChainContextModule.TASK_CONTRACT,
                Map.of("version", ChainContextValue.text("v1")),
                Map.of("cut", ChainContextValue.number(1)),
                "projection-v1",
                "pagination-v1",
                Map.of("task", ChainContextValue.text("task-1")),
                Map.of(
                        "taskFrame", ChainContextValue.text("body"),
                        "digest", ChainContextValue.text("abc")),
                "taskFrame", "digest");

        assertEquals(ChainContextModuleStatus.PRESENT,
                projection.presenceKind());
        assertEquals(2, projection.projectionFields().size());
        assertEquals(null, projection.emptyWatermark());
    }

    @Test
    void missingRequiredFieldIsTypedContextInputBlocked() {
        ChainContextException failure = assertThrows(
                ChainContextException.class,
                () -> ProductChainContextProjectionSupport.present(
                        ChainContextModule.PLAN_AND_STEP_CONTRACT,
                        Map.of(), Map.of(), "projection-v1", "pagination-v1",
                        Map.of(), Map.of("plan", ChainContextValue.text("p")),
                        "plan", "currentStep"));

        assertEquals(ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                failure.code());
        assertEquals(ChainContextException.FailureDisposition.PROPAGATE,
                failure.failureDisposition());
        assertTrue(failure.getMessage().contains("currentStep"));
    }

    @Test
    void constructsOnlyContractLegalEmptyProjection() {
        ChainContextModule module = ChainContextModule.CONVERSATION_CONTEXT;
        String watermark = ChainContextVersionMatrix.requirement(module)
                .emptyWatermark();
        var projection = ProductChainContextProjectionSupport.empty(
                module, Map.of(), Map.of(), "projection-v1", "pagination-v1",
                Map.of(), watermark);

        assertEquals(ChainContextModuleStatus.EMPTY,
                projection.presenceKind());
        assertTrue(projection.projectionFields().isEmpty());
        assertEquals(watermark, projection.emptyWatermark());

        ChainContextException failure = assertThrows(
                ChainContextException.class,
                () -> ProductChainContextProjectionSupport.empty(
                        ChainContextModule
                                .RUNTIME_RULES_CAPABILITIES_AND_PERMISSIONS,
                        Map.of(), Map.of(), "projection-v1", "pagination-v1",
                        Map.of(), "EMPTY_ILLEGAL"));
        assertEquals(ChainContextErrorCode.CONTEXT_INPUT_BLOCKED,
                failure.code());
    }
}
