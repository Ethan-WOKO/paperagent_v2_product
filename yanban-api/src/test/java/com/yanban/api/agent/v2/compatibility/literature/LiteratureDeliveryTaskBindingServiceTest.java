package com.yanban.api.agent.v2.compatibility.literature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.domain.LiteratureSearchTaskRepository;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.ToolCallId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LiteratureDeliveryTaskBindingServiceTest {
    private final LiteratureDeliveryJpaRepository deliveries =
            mock(LiteratureDeliveryJpaRepository.class);
    private final LiteratureSearchTaskRepository tasks =
            mock(LiteratureSearchTaskRepository.class);
    private final LiteratureDeliveryTaskBindingService service =
            new LiteratureDeliveryTaskBindingService(
                    deliveries, tasks, new ObjectMapper());

    @Test
    void successfulReceiptBindsExactOwnerTurnAndTask() {
        LiteratureDeliveryEntity delivery = delivery();
        LiteratureSearchTask task = task(99L);
        when(deliveries.findLockedByUserIdAndTurnId(7L, 42L))
                .thenReturn(Optional.of(delivery));
        when(tasks.findByIdAndUserId(99L, 7L))
                .thenReturn(Optional.of(task));

        assertEquals(99L, service.bindSuccessfulReceipt(
                7L, 42L, receipt(ReceiptStatus.SUCCESS, 99L)));
        assertEquals(99L, delivery.literatureTaskId());
        verify(deliveries).saveAndFlush(delivery);
    }

    @Test
    void failedOrMissingTaskReceiptCannotPolluteBinding() {
        LiteratureDeliveryEntity delivery = delivery();
        assertEquals("receipt", assertThrows(
                LiteratureDeliveryTaskBindingException.class,
                () -> service.bindSuccessfulReceipt(
                        7L, 42L,
                        receipt(ReceiptStatus.FAILURE, null))).path());
        assertNull(delivery.literatureTaskId());
        verify(deliveries, never()).saveAndFlush(delivery);

        when(deliveries.findLockedByUserIdAndTurnId(7L, 42L))
                .thenReturn(Optional.of(delivery));
        assertEquals("receipt.taskId", assertThrows(
                LiteratureDeliveryTaskBindingException.class,
                () -> service.bindSuccessfulReceipt(
                        7L, 42L,
                        receipt(ReceiptStatus.SUCCESS, null))).path());
        assertNull(delivery.literatureTaskId());
    }

    @Test
    void crossTurnAndDifferentTaskReplayFailClosed() {
        assertEquals("delivery", assertThrows(
                LiteratureDeliveryTaskBindingException.class,
                () -> service.bindSuccessfulReceipt(
                        7L, 404L,
                        receipt(ReceiptStatus.SUCCESS, 99L))).path());

        LiteratureDeliveryEntity delivery = delivery();
        delivery.bindLiteratureTask(98L);
        when(deliveries.findLockedByUserIdAndTurnId(7L, 42L))
                .thenReturn(Optional.of(delivery));
        LiteratureSearchTask different = task(99L);
        when(tasks.findByIdAndUserId(99L, 7L))
                .thenReturn(Optional.of(different));
        assertThrows(IllegalStateException.class,
                () -> service.bindSuccessfulReceipt(
                        7L, 42L,
                        receipt(ReceiptStatus.SUCCESS, 99L)));
        assertEquals(98L, delivery.literatureTaskId());
    }

    private static LiteratureDeliveryEntity delivery() {
        return new LiteratureDeliveryEntity(
                new LiteratureDeliveryKey(7L, 9L, "request"),
                "a".repeat(64), "graph retrieval", 10, null, false,
                11L, 42L, "owner", "token",
                Instant.now().plusSeconds(60), Instant.now());
    }

    private static LiteratureSearchTask task(Long id) {
        LiteratureSearchTask task = mock(LiteratureSearchTask.class);
        when(task.getId()).thenReturn(id);
        when(task.getUserId()).thenReturn(7L);
        when(task.getProjectId()).thenReturn(null);
        when(task.getQuery()).thenReturn("graph retrieval");
        when(task.getTopK()).thenReturn(10);
        when(task.getYearFrom()).thenReturn(null);
        when(task.getIncludeBibtex()).thenReturn(false);
        when(task.getClientRequestId()).thenReturn(requestId("tool"));
        return task;
    }

    private static ExecutionReceipt receipt(
            ReceiptStatus status, Long taskId) {
        String output = taskId == null ? "{}"
                : "{\"taskId\":" + taskId
                + ",\"clientRequestId\":\"" + requestId("tool") + "\"}";
        boolean success = status == ReceiptStatus.SUCCESS;
        return new ExecutionReceipt(
                new ReceiptId("receipt"), new ToolCallId("tool"),
                status, Instant.parse("2026-07-28T00:00:00Z"),
                Instant.parse("2026-07-28T00:00:01Z"),
                Optional.of(success ? 0 : 1),
                success ? Optional.empty() : Optional.of("FAILED"),
                OutputCapture.inline(output, false),
                OutputCapture.empty(), List.of(), Optional.empty(),
                List.of());
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
