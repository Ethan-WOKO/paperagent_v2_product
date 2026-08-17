package com.yanban.api.agent.reactplan;

public record ReactPlanAnswerRequest(String questionId, String answer) {
    public ReactPlanAnswerRequest {
        questionId = required(questionId, 128, "questionId");
        answer = required(answer, 16_000, "answer");
    }

    private static String required(String value, int max, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String result = value.trim();
        if (result.length() > max) throw new IllegalArgumentException(name + " is too long");
        return result;
    }
}
