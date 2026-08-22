package com.yanban.knowledge.service;

import com.yanban.knowledge.config.KnowledgeChunkingProperties;
import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.io.Reader;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeTextChunker {

    private static final double MIN_BOUNDARY_RATIO = 0.6d;

    private final KnowledgeChunkingProperties properties;

    public KnowledgeTextChunker(KnowledgeChunkingProperties properties) {
        this.properties = properties;
    }

    public List<String> split(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return List.of("");
        }

        int maxCharacters = properties.getMaxCharacters();
        int overlapCharacters = properties.getOverlapCharacters();
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int hardEnd = safeBoundary(normalized, Math.min(normalized.length(), start + maxCharacters));
            int end = hardEnd == normalized.length()
                    ? hardEnd
                    : preferredEnd(normalized, start, hardEnd, maxCharacters);
            if (end <= start) {
                end = hardEnd;
            }

            String chunk = normalized.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= normalized.length()) {
                break;
            }

            int nextStart = Math.max(start + 1, end - overlapCharacters);
            start = safeStart(normalized, nextStart);
        }
        return chunks.isEmpty() ? List.of("") : List.copyOf(chunks);
    }

    public void forEachChunk(Reader reader, Consumer<String> consumer) throws IOException {
        int maxCharacters = properties.getMaxCharacters();
        int overlapCharacters = properties.getOverlapCharacters();
        StringBuilder pending = new StringBuilder(maxCharacters * 2);
        char[] input = new char[Math.max(1024, maxCharacters)];
        boolean previousCarriageReturn = false;
        int read;
        while ((read = reader.read(input)) >= 0) {
            for (int i = 0; i < read; i++) {
                char value = input[i];
                if (value == '\r') {
                    pending.append('\n');
                    previousCarriageReturn = true;
                } else {
                    if (value == '\n' && previousCarriageReturn) {
                        previousCarriageReturn = false;
                        continue;
                    }
                    previousCarriageReturn = false;
                    pending.append(value);
                }
            }
            emitAvailable(pending, maxCharacters, overlapCharacters, consumer, false);
        }
        emitAvailable(pending, maxCharacters, overlapCharacters, consumer, true);
    }

    private void emitAvailable(StringBuilder pending, int maxCharacters, int overlapCharacters,
                               Consumer<String> consumer, boolean endOfInput) {
        while (pending.length() >= maxCharacters || endOfInput && !pending.isEmpty()) {
            int hardEnd = safeBoundary(pending.toString(), Math.min(pending.length(), maxCharacters));
            int end = hardEnd == pending.length() ? hardEnd
                    : preferredEnd(pending.toString(), 0, hardEnd, maxCharacters);
            if (end <= 0) end = hardEnd;
            String chunk = pending.substring(0, end).trim();
            if (!chunk.isEmpty()) consumer.accept(chunk.startsWith("\uFEFF") ? chunk.substring(1) : chunk);
            if (end >= pending.length()) {
                pending.setLength(0);
                break;
            }
            int retainFrom = safeStart(pending.toString(), Math.max(0, end - overlapCharacters));
            pending.delete(0, retainFrom);
            if (!endOfInput && pending.length() < maxCharacters) break;
        }
    }

    private int preferredEnd(String text, int start, int hardEnd, int maxCharacters) {
        int minimumEnd = Math.min(hardEnd,
                start + Math.max(1, (int) Math.floor(maxCharacters * MIN_BOUNDARY_RATIO)));

        int boundary = lastParagraphBoundary(text, minimumEnd, hardEnd);
        if (boundary < 0) {
            boundary = lastLineBoundary(text, minimumEnd, hardEnd);
        }
        if (boundary < 0) {
            boundary = lastSentenceBoundary(text, minimumEnd, hardEnd);
        }
        if (boundary < 0) {
            boundary = lastWhitespaceBoundary(text, minimumEnd, hardEnd);
        }
        return boundary < 0 ? hardEnd : safeBoundary(text, boundary);
    }

    private int lastParagraphBoundary(String text, int minimumEnd, int hardEnd) {
        int index = text.lastIndexOf("\n\n", hardEnd - 1);
        return index >= minimumEnd ? index + 2 : -1;
    }

    private int lastLineBoundary(String text, int minimumEnd, int hardEnd) {
        int index = text.lastIndexOf('\n', hardEnd - 1);
        return index >= minimumEnd ? index + 1 : -1;
    }

    private int lastSentenceBoundary(String text, int minimumEnd, int hardEnd) {
        for (int index = hardEnd - 1; index >= minimumEnd; index--) {
            if (isSentenceTerminator(text.charAt(index))) {
                return index + 1;
            }
        }
        return -1;
    }

    private int lastWhitespaceBoundary(String text, int minimumEnd, int hardEnd) {
        for (int index = hardEnd - 1; index >= minimumEnd; index--) {
            if (Character.isWhitespace(text.charAt(index))) {
                return index + 1;
            }
        }
        return -1;
    }

    private boolean isSentenceTerminator(char value) {
        return value == '.' || value == '?' || value == '!'
                || value == ';' || value == '\u3002' || value == '\uff1f'
                || value == '\uff01' || value == '\uff1b';
    }

    private int safeBoundary(String text, int boundary) {
        if (boundary > 0 && boundary < text.length()
                && Character.isHighSurrogate(text.charAt(boundary - 1))
                && Character.isLowSurrogate(text.charAt(boundary))) {
            return boundary - 1;
        }
        return boundary;
    }

    private int safeStart(String text, int start) {
        if (start > 0 && start < text.length() && Character.isLowSurrogate(text.charAt(start))) {
            return start - 1;
        }
        return start;
    }

    private String normalize(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }
}
