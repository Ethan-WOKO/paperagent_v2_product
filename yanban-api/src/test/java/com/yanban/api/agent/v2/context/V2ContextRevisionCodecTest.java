package com.yanban.api.agent.v2.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class V2ContextRevisionCodecTest {
    private final V2ContextRevisionCodec codec =
            new V2ContextRevisionCodec(new ObjectMapper());

    @Test
    void canonicalizesSectionAndObjectOrderToLowercaseSha256() {
        V2ContextRevisionDraft first = draft(List.of(
                section(ContextSectionType.RAG_EVIDENCE, "{\"b\":2,\"a\":1}"),
                section(ContextSectionType.CORE_AUTHORITY, "{\"z\":0}")));
        V2ContextRevisionDraft second = draft(List.of(
                section(ContextSectionType.CORE_AUTHORITY, "{\"z\":0}"),
                section(ContextSectionType.RAG_EVIDENCE, "{\"a\":1,\"b\":2}")));

        var left = codec.encode(first);
        var right = codec.encode(second);

        assertThat(left.canonicalJson()).isEqualTo(right.canonicalJson());
        assertThat(left.digest()).isEqualTo(right.digest())
                .matches("[a-f0-9]{64}");
        assertThat(left.sections()).extracting(value -> value.draft().type())
                .containsExactly(ContextSectionType.CORE_AUTHORITY,
                        ContextSectionType.RAG_EVIDENCE);
    }

    @Test
    void rejectsSensitiveRawPayloadsAndAbsoluteHostPaths() {
        assertThatThrownBy(() -> codec.encode(draft(List.of(
                section(ContextSectionType.CORE_AUTHORITY,
                        "{\"apiKey\":\"hidden\"}")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.encode(draft(List.of(
                section(ContextSectionType.CORE_AUTHORITY,
                        "{\"source\":\"C:\\\\Users\\\\name\\\\paper.tex\"}")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.encode(draft(List.of(
                section(ContextSectionType.CORE_AUTHORITY,
                        "{\"rawToolOutput\":\"full body\"}")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recentConversationProjectionUsesItsTokenDerivedLimit() {
        String projection = "{\"conversation\":\""
                + "a".repeat(200_000) + "\"}";
        V2ContextSectionDraft recent = new V2ContextSectionDraft(
                ContextSectionType.RECENT_CONVERSATION, 20, 200_000,
                200_000, 200_000, V2ContextSectionStatus.READY,
                "[]", projection, null);

        assertThat(codec.encode(draft(List.of(recent))).canonicalJson())
                .contains("conversation");
    }

    @Test
    void sourceRefsKeepTheirIndependentSmallLimit() {
        V2ContextSectionDraft recent = new V2ContextSectionDraft(
                ContextSectionType.RECENT_CONVERSATION, 20, 200_000,
                10, 10, V2ContextSectionStatus.READY,
                "[\"" + "r".repeat(70_000) + "\"]", "{}", null);

        assertThatThrownBy(() -> codec.encode(draft(List.of(recent))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceRefsJson");
    }

    private static V2ContextRevisionDraft draft(
            List<V2ContextSectionDraft> sections) {
        return new V2ContextRevisionDraft(
                1L, 2L, 3L, 1, null, null,
                V2ContextStage.PLANNER, "planner:1",
                V2ContextRevisionStatus.READY,
                "deepseek", "deepseek-v4-flash",
                1_000_000, 384_000, "utf8-byte-v1", "layered-v1",
                10, 50_000, sections);
    }

    private static V2ContextSectionDraft section(
            ContextSectionType type, String projection) {
        return new V2ContextSectionDraft(
                type, type.percentage(), 100_000, 10, 10,
                V2ContextSectionStatus.READY, "[]", projection, null);
    }
}
