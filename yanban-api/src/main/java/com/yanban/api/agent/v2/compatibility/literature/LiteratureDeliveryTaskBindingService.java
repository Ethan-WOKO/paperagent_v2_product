package com.yanban.api.agent.v2.compatibility.literature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureSearchTask;
import com.yanban.paper.domain.LiteratureSearchTaskRepository;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ReceiptStatus;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LiteratureDeliveryTaskBindingService {
    private static final int MAX_RECEIPT_OUTPUT = 2_048;

    private final LiteratureDeliveryJpaRepository deliveries;
    private final LiteratureSearchTaskRepository tasks;
    private final ObjectMapper json;

    public LiteratureDeliveryTaskBindingService(
            LiteratureDeliveryJpaRepository deliveries,
            LiteratureSearchTaskRepository tasks,
            ObjectMapper json) {
        this.deliveries = deliveries;
        this.tasks = tasks;
        this.json = json;
    }

    @Transactional
    public Long bindSuccessfulReceipt(
            Long userId, Long turnId, ExecutionReceipt receipt) {
        ReceiptAuthority receiptAuthority = authoritativeReceipt(receipt);
        long taskId = receiptAuthority.taskId();
        LiteratureDeliveryEntity delivery = deliveries
                .findLockedByUserIdAndTurnId(userId, turnId)
                .orElseThrow(() -> failed("delivery"));
        LiteratureSearchTask task = tasks.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> failed("task"));
        requireSameRequest(
                delivery, task, receiptAuthority.clientRequestId());
        delivery.bindLiteratureTask(taskId);
        deliveries.saveAndFlush(delivery);
        return taskId;
    }

    private ReceiptAuthority authoritativeReceipt(ExecutionReceipt receipt) {
        if (receipt == null || receipt.status() != ReceiptStatus.SUCCESS
                || receipt.standardOutput() == null
                || receipt.standardOutput().inlineText().isEmpty()
                || receipt.standardOutput().truncated()) {
            throw failed("receipt");
        }
        String output = receipt.standardOutput().inlineText().orElseThrow();
        if (output.length() > MAX_RECEIPT_OUTPUT) {
            throw failed("receipt.output");
        }
        try {
            JsonNode root = json.readTree(output);
            if (root == null || !root.isObject()
                    || !root.path("taskId").canConvertToLong()
                    || root.path("taskId").longValue() <= 0
                    || !root.path("clientRequestId").isTextual()) {
                throw failed("receipt.taskId");
            }
            String expectedRequestId = deterministic(
                    "v2-literature-request",
                    receipt.toolCallId().value());
            if (!expectedRequestId.equals(
                    root.path("clientRequestId").textValue())) {
                throw failed("receipt.clientRequestId");
            }
            return new ReceiptAuthority(
                    root.path("taskId").longValue(), expectedRequestId);
        } catch (LiteratureDeliveryTaskBindingException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failed("receipt.output");
        }
    }

    private static void requireSameRequest(
            LiteratureDeliveryEntity delivery, LiteratureSearchTask task,
            String expectedTaskRequestId) {
        String taskQuery = normalize(task.getQuery());
        if (!delivery.id().userId().equals(task.getUserId())
                || task.getProjectId() != null
                || !normalize(delivery.query()).equals(taskQuery)
                || !delivery.topK().equals(task.getTopK())
                || !java.util.Objects.equals(
                        delivery.yearFrom(), task.getYearFrom())
                || !delivery.includeBibtex().equals(task.getIncludeBibtex())) {
            throw failed("task.authority");
        }
        if (!expectedTaskRequestId.equals(task.getClientRequestId())) {
            throw failed("task.requestAuthority");
        }
    }

    private static String normalize(String value) {
        return Optional.ofNullable(value).orElse("")
                .replaceAll("\\s+", " ").trim();
    }

    private static LiteratureDeliveryTaskBindingException failed(
            String path) {
        return new LiteratureDeliveryTaskBindingException(path);
    }

    private static String deterministic(String domain, String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (domain + "\0" + source)
                            .getBytes(StandardCharsets.UTF_8));
            return domain + "."
                    + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private record ReceiptAuthority(
            long taskId, String clientRequestId) {
    }
}
