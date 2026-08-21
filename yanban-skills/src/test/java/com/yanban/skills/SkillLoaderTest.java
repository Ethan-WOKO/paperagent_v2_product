package com.yanban.skills;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SkillLoaderTest {

    @Test
    void loadsBuiltinCodeReviewSkill() {
        SkillLoader loader = new SkillLoader();
        SkillDefinition skill = loader.loadAll().stream()
                .filter(item -> item.id().equals("code-review"))
                .findFirst()
                .orElseThrow();

        assertThat(skill.prompt())
                .contains("专注于代码审查")
                .contains("search_tools")
                .contains("不得调用任何 `mcp_fs__*` 工具");
        assertThat(skill.allowedTools()).containsExactly(
                "list_project_files", "read_project_file", "execute_in_sandbox");
        assertThat(skill.builtin()).isTrue();
    }
}
