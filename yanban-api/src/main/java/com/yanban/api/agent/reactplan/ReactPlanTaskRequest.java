package com.yanban.api.agent.reactplan;

public record ReactPlanTaskRequest(String instruction, String provider, String model, String skillId) {
    public ReactPlanTaskRequest {
        if (instruction == null || instruction.isBlank() || instruction.length() > 16_000) {
            throw new IllegalArgumentException("instruction must contain 1 to 16000 characters");
        }
        instruction = instruction.trim();
        provider = optional(provider, 64, "provider");
        model = optional(model, 128, "model");
        skillId = optional(skillId, 128, "skillId");
    }

    public ReactPlanTaskRequest(String instruction, String provider, String model) {
        this(instruction, provider, model, null);
    }

    private static String optional(String value, int max, String name) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim();
        if (result.length() > max) throw new IllegalArgumentException(name + " is too long");
        return result;
    }
}
