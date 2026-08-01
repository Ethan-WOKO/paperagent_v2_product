package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResolvedToolPolicyTest {

    @Test
    void nullDuringMergeInheritsButEmptyListExplicitlyDeniesAll() {
        ResolvedToolPolicy inherited = ResolvedToolPolicy.resolve(List.of("search_web"), null, 2, 1, "inherit");
        ResolvedToolPolicy denied = ResolvedToolPolicy.resolve(List.of("search_web"), List.of(), 0, 1, "deny");

        assertThat(inherited.allowedTools()).containsExactly("search_web");
        assertThat(denied.allowedTools()).isEmpty();
    }

    @Test
    void finalPolicyCannotContainNullAllowlist() {
        assertThatThrownBy(() -> new ResolvedToolPolicy(null, 0, 0, "invalid"))
                .isInstanceOf(NullPointerException.class);
    }
}
