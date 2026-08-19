package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentSessionTitleGeneratorTest {
    @Test
    void recognizesOnlyProductDefaultTitles() {
        assertThat(AgentSessionTitleGenerator.isDefaultTitle(null)).isTrue();
        assertThat(AgentSessionTitleGenerator.isDefaultTitle("新会话")).isTrue();
        assertThat(AgentSessionTitleGenerator.isDefaultTitle("研伴对话")).isTrue();
        assertThat(AgentSessionTitleGenerator.isDefaultTitle("用户手动标题")).isFalse();
    }

    @Test
    void sanitizesModelDecorationAndFallsBackToFirstQuestion() {
        assertThat(AgentSessionTitleGenerator.sanitize("“检查 Sort 编译！”", "ignored"))
                .isEqualTo("检查 Sort 编译");
        assertThat(AgentSessionTitleGenerator.sanitize("", "这是用户的第一条测试问题，应该截断"))
                .isEqualTo("这是用户的第一条测试问题，应该截");
    }
}
