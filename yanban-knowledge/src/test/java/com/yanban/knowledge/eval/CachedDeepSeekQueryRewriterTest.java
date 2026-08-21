package com.yanban.knowledge.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CachedDeepSeekQueryRewriterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsTwoFaithfulRewritesAndReloadsThemFromDisk() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test/chat");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://example.test/chat"))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.messages[1].content").value("Does drug X not reduce B12 by 20%?"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"{\\\"keywordQuery\\\":\\\"drug X not reduce B12 20%\\\",\\\"semanticQuery\\\":\\\"evidence that drug X does not reduce B12 by 20%\\\"}"}}],
                         "usage":{"prompt_tokens":31,"completion_tokens":12}}
                        """, MediaType.APPLICATION_JSON));
        Path cache = temporaryDirectory.resolve("rewrite.jsonl");
        CachedDeepSeekQueryRewriter rewriter = new CachedDeepSeekQueryRewriter(
                builder.build(), "test-key", "deepseek-chat", cache);

        assertThat(rewriter.rewrite("Does drug X not reduce B12 by 20%?"))
                .containsExactly("drug X not reduce B12 20%", "evidence that drug X does not reduce B12 by 20%");
        assertThat(rewriter.telemetry().inputTokens()).isEqualTo(31);
        assertThat(rewriter.telemetry().outputTokens()).isEqualTo(12);

        CachedDeepSeekQueryRewriter reloaded = new CachedDeepSeekQueryRewriter(
                builder.build(), "test-key", "deepseek-chat", cache);
        assertThat(reloaded.rewrite("Does drug X not reduce B12 by 20%?")).hasSize(2);
        assertThat(reloaded.telemetry().apiCalls()).isZero();
        assertThat(reloaded.telemetry().cacheHits()).isEqualTo(1);
        server.verify();
    }

    @Test
    void rejectsInventedNumbersAndChangedPolarity() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test/chat");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://example.test/chat"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"{\\\"keywordQuery\\\":\\\"drug X reduces B12 30%\\\",\\\"semanticQuery\\\":\\\"drug X improves B12\\\"}"}}],
                         "usage":{"prompt_tokens":20,"completion_tokens":8}}
                        """, MediaType.APPLICATION_JSON));
        CachedDeepSeekQueryRewriter rewriter = new CachedDeepSeekQueryRewriter(
                builder.build(), "test-key", "deepseek-chat", temporaryDirectory.resolve("invalid.jsonl"));

        assertThat(rewriter.rewrite("drug X does not reduce B12 by 20%")).isEmpty();
        assertThat(rewriter.telemetry().rejectedRewrites()).isEqualTo(2);
        assertThat(rewriter.telemetry().apiCalls()).isEqualTo(1);
        server.verify();
    }
}
