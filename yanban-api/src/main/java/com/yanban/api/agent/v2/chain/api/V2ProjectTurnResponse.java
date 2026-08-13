package com.yanban.api.agent.v2.chain.api;

import java.util.List;

public record V2ProjectTurnResponse(
        String clientRequestId,
        String workState,
        String taskOutcomeStatus,
        String deliveryStatus,
        String route,
        String planId,
        String baseProjectVersion,
        String publishedProjectVersion,
        Long revisionId,
        String publishReceiptId,
        List<Step> steps,
        PendingItem pendingItem,
        Validation validation,
        String finalText,
        Long candidateArtifactId,
        List<String> outputPaths,
        String failureCategory,
        String failureCode,
        String deliveryErrorCode) {

    public V2ProjectTurnResponse {
        steps = steps == null ? List.of() : List.copyOf(steps);
        outputPaths = outputPaths == null ? List.of() : List.copyOf(outputPaths);
    }

    public record Step(
            String stepId,
            int index,
            String title,
            String status,
            String detail) {
    }

    public record PendingItem(
            String gapId,
            String type,
            String status,
            String question,
            String expectedFormat) {
    }

    public record Validation(
            String validationId,
            String status,
            String requestDigest,
            String receiptDigest,
            List<ValidationReceipt> receipts) {
        public Validation {
            receipts = receipts == null ? List.of() : List.copyOf(receipts);
        }

        public Validation requireComplete() {
            if (blank(validationId) || blank(status)
                    || blank(requestDigest) || blank(receiptDigest)
                    || receipts.isEmpty()) {
                throw new IllegalStateException(
                        "formal validation identity is incomplete");
            }
            return this;
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    public record ValidationReceipt(
            String requirementId,
            String subject,
            String receiptId,
            String actionId,
            Long candidateArtifactId,
            String candidateFingerprint,
            String projectVersion) {
    }
}
