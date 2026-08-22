package com.yanban.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.yanban.knowledge.config.KnowledgeChunkingProperties;
import java.util.List;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

class KnowledgeTextChunkerTest {

    @Test
    void prefersParagraphBoundaryAndCarriesBoundedOverlap() {
        KnowledgeTextChunker chunker = chunker(80, 15);
        String firstParagraph = "A".repeat(52);
        String text = firstParagraph + "\n\n" + "B".repeat(52);

        List<String> chunks = chunker.split(text);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).isEqualTo(firstParagraph);
        assertThat(chunks.get(1)).startsWith("A".repeat(13) + "\n\n").endsWith("B".repeat(52));
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(80));
    }

    @Test
    void prefersSentenceBoundaryWhenParagraphBoundaryIsUnavailable() {
        KnowledgeTextChunker chunker = chunker(80, 15);
        String text = "甲".repeat(55) + "。" + "乙".repeat(55);

        List<String> chunks = chunker.split(text);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).endsWith("。");
        assertThat(chunks.get(1)).startsWith("甲".repeat(14) + "。");
        assertThat(chunks.get(1)).endsWith("乙".repeat(55));
    }

    @Test
    void hardSplitNeverBreaksUnicodeSurrogatePairs() {
        KnowledgeTextChunker chunker = chunker(64, 8);
        String text = "x".repeat(63) + "😀" + "y".repeat(70);

        List<String> chunks = chunker.split(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.length()).isLessThanOrEqualTo(64);
            assertThat(hasUnpairedSurrogate(chunk)).isFalse();
        });
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk).contains("😀"));
    }

    @Test
    void normalizesLineEndingsAndKeepsOneEmptyChunk() {
        KnowledgeTextChunker chunker = chunker(80, 15);

        assertThat(chunker.split("  first\r\nsecond  ")).containsExactly("first\nsecond");
        assertThat(chunker.split(" \r\n ")).containsExactly("");
        assertThat(chunker.split(null)).containsExactly("");
    }

    @Test
    void productionDefaultsUseEvaluatedChunkAndOverlapSizes() {
        KnowledgeChunkingProperties properties = new KnowledgeChunkingProperties();

        assertThat(properties.getMaxCharacters()).isEqualTo(800);
        assertThat(properties.getOverlapCharacters()).isEqualTo(120);
        assertThat(properties.isOverlapValid()).isTrue();
    }

    @Test
    void streamsLargeInputWithoutChangingChunkLimits() throws Exception {
        KnowledgeTextChunker chunker = chunker(80, 15);
        List<String> chunks = new java.util.ArrayList<>();

        chunker.forEachChunk(new StringReader(("一段较长文本。" + "x".repeat(90) + "\r\n").repeat(200)),
                chunks::add);

        assertThat(chunks).hasSizeGreaterThan(200);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(80));
        assertThat(chunks).noneMatch(String::isBlank);
    }

    private KnowledgeTextChunker chunker(int maxCharacters, int overlapCharacters) {
        KnowledgeChunkingProperties properties = new KnowledgeChunkingProperties();
        properties.setMaxCharacters(maxCharacters);
        properties.setOverlapCharacters(overlapCharacters);
        return new KnowledgeTextChunker(properties);
    }

    private boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return true;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }
}
