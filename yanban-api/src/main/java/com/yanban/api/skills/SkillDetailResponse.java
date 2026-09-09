package com.yanban.api.skills;

import java.util.Set;

public record SkillDetailResponse(String id, String name, String description, boolean builtin,
        boolean enabled, boolean managed, String prompt, String metadata, Set<String> allowedTools) {}
