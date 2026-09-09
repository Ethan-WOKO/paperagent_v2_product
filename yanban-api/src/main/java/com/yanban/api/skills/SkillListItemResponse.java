package com.yanban.api.skills;

public record SkillListItemResponse(
        String id,
        String name,
        String description,
        boolean builtin,
        boolean enabled,
        String path
) {
    public String getSource() { return builtin ? "builtin" : "user"; }
    public boolean isManaged() { return id.startsWith("user-") && path.isEmpty(); }
}
