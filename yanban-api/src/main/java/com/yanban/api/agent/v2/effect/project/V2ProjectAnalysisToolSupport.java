package com.yanban.api.agent.v2.effect.project;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.workspace.WorkspacePort;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/** Shared deterministic validation and encoding for read-only Project tools. */
final class V2ProjectAnalysisToolSupport {
    static final int MAX_PATH_CHARACTERS = 1_024;
    static final int MAX_SNIPPET_CHARACTERS = 500;

    private V2ProjectAnalysisToolSupport() {
    }

    static void requireAllowedFields(
            ObjectNode arguments, Set<String> allowed) {
        if (arguments == null) {
            throw failed("arguments");
        }
        var fields = arguments.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) {
                throw failed("arguments");
            }
        }
    }

    static List<ProjectPath> paths(
            ObjectNode arguments,
            String field,
            int maximum,
            boolean required,
            Predicate<String> supported) {
        if (!arguments.has(field)) {
            if (required) {
                throw failed("arguments");
            }
            return List.of();
        }
        if (!arguments.path(field).isArray()
                || arguments.path(field).size() < 1
                || arguments.path(field).size() > maximum) {
            throw failed("arguments");
        }
        LinkedHashSet<ProjectPath> result = new LinkedHashSet<>();
        for (var item : arguments.path(field)) {
            if (!item.isTextual()
                    || item.textValue().isEmpty()
                    || item.textValue().length() > MAX_PATH_CHARACTERS) {
                throw failed("arguments");
            }
            ProjectPath path;
            try {
                path = new ProjectPath(item.textValue());
            } catch (RuntimeException invalid) {
                throw failed("arguments");
            }
            if (!path.value().equals(item.textValue())
                    || !supported.test(path.value())
                    || !result.add(path)) {
                throw failed("arguments");
            }
        }
        return List.copyOf(result);
    }

    static ReadResult read(
            WorkspacePort workspace,
            WorkspaceRef ref,
            List<ProjectPath> paths,
            long maximumBytes) {
        List<Source> sources = new ArrayList<>();
        long bytesInspected = 0;
        for (ProjectPath path : paths) {
            byte[] bytes = workspace.read(ref, path);
            bytesInspected += bytes.length;
            if (bytesInspected > maximumBytes) {
                throw failed("input_budget");
            }
            sources.add(new Source(
                    path.value(), utf8(bytes), bytes.length));
        }
        return new ReadResult(List.copyOf(sources), bytesInspected);
    }

    static String requiredText(
            ObjectNode arguments, String field, int maximum) {
        if (!arguments.path(field).isTextual()) {
            throw failed("arguments");
        }
        String value = arguments.path(field).textValue();
        if (value.isBlank() || value.length() > maximum) {
            throw failed("arguments");
        }
        return value;
    }

    static boolean optionalBoolean(
            ObjectNode arguments, String field, boolean fallback) {
        if (!arguments.has(field)) {
            return fallback;
        }
        if (!arguments.path(field).isBoolean()) {
            throw failed("arguments");
        }
        return arguments.path(field).booleanValue();
    }

    static int optionalInteger(
            ObjectNode arguments,
            String field,
            int fallback,
            int minimum,
            int maximum) {
        if (!arguments.has(field)) {
            return fallback;
        }
        if (!arguments.path(field).isIntegralNumber()) {
            throw failed("arguments");
        }
        int value = arguments.path(field).intValue();
        if (value < minimum || value > maximum) {
            throw failed("arguments");
        }
        return value;
    }

    static String encode(ObjectMapper json, ObjectNode output) {
        try {
            String value = json.writeValueAsString(output);
            if (value.length() > OutputCapture.MAX_INLINE_CHARACTERS) {
                throw failed("output_budget");
            }
            return value;
        } catch (JsonProcessingException exception) {
            throw failed("encoding");
        }
    }

    static String snippet(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() <= MAX_SNIPPET_CHARACTERS) {
            return normalized;
        }
        int end = MAX_SNIPPET_CHARACTERS;
        if (Character.isHighSurrogate(normalized.charAt(end - 1))
                && Character.isLowSurrogate(normalized.charAt(end))) {
            end--;
        }
        return normalized.substring(0, end);
    }

    static String extension(String path) {
        int separator = path.lastIndexOf('.');
        return separator < 0
                ? ""
                : path.substring(separator + 1).toLowerCase(Locale.ROOT);
    }

    static String utf8(byte[] bytes) {
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (value.codePoints().anyMatch(character ->
                    character < 0x20
                            && character != '\t'
                            && character != '\n'
                            && character != '\r')) {
                throw failed("malformed_utf8");
            }
            return value;
        } catch (CharacterCodingException exception) {
            throw failed("malformed_utf8");
        }
    }

    static ToolException failed(String stage) {
        return new ToolException(stage);
    }

    static final class ToolException extends RuntimeException {
        private final String stage;

        ToolException(String stage) {
            super("PROJECT_ANALYSIS_TOOL_FAILED");
            this.stage = stage;
        }

        String stage() {
            return stage;
        }
    }

    record Source(String path, String content, long bytes) {
    }

    record ReadResult(List<Source> sources, long bytesInspected) {
    }
}
