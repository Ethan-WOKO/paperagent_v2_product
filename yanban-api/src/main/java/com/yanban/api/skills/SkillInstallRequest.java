package com.yanban.api.skills;

/** File contents are transported as bounded JSON, never as server filesystem paths. */
public record SkillInstallRequest(String name, Upload markdown, Upload metadata) {
    public record Upload(String filename, String content) {}
}
