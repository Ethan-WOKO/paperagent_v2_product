package io.paperagent.v2.chain.model;

import io.paperagent.v2.chain.AnswerPayload;
import io.paperagent.v2.chain.ChainContentKind;
import io.paperagent.v2.chain.ChainProposalPayload;
import io.paperagent.v2.chain.ExecutorPayload;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class ChainModelCanonical {
    private ChainModelCanonical() {
    }

    static MaterializedPayload materialize(ChainProposalPayload payload, String bodyRef) {
        Body body = body(payload);
        if ((body == null) != (bodyRef == null)) {
            throw new IllegalArgumentException("inline body and authority ref must be paired");
        }
        Object tree = toTree(payload, bodyRef);
        String canonicalPayload = json(tree);
        List<String> sourceRefs = ChainProposalSourceRefs.extract(payload);
        return new MaterializedPayload(
                canonicalPayload,
                sourceRefs,
                body == null ? null : body.kind().name(),
                bodyRef);
    }

    static Body body(ChainProposalPayload payload) {
        if (payload instanceof ExecutorPayload.WorkspaceChange value) {
            return new Body(ChainContentKind.WORKSPACE_CHANGE_BODY,
                    value.inlineCanonicalChangeBody(), "application/json");
        }
        if (payload instanceof ExecutorPayload.StepResult value) {
            return new Body(ChainContentKind.CANDIDATE_STEP_RESULT,
                    value.inlineCandidateResultBody(), "text/plain");
        }
        if (payload instanceof AnswerPayload.DirectAnswer value) {
            return new Body(ChainContentKind.ANSWER_BODY, value.inlineAnswerBody(), "text/plain");
        }
        if (payload instanceof AnswerPayload.UserQuestion value) {
            return new Body(ChainContentKind.ANSWER_BODY, value.inlineAnswerBody(), "text/plain");
        }
        if (payload instanceof AnswerPayload.StatusOrFailure value) {
            return new Body(ChainContentKind.ANSWER_BODY, value.inlineAnswerBody(), "text/plain");
        }
        if (payload instanceof AnswerPayload.FinalDelivery value) {
            return new Body(ChainContentKind.ANSWER_BODY, value.inlineAnswerBody(), "text/plain");
        }
        return null;
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte element : digest) {
                result.append(String.format("%02x", element & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static String json(Object value) {
        StringBuilder output = new StringBuilder();
        appendJson(output, value);
        return output.toString();
    }

    private static Object toTree(Object value, String bodyRef) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Collection<?> collection) {
            List<Object> elements = new ArrayList<>();
            for (Object element : collection) elements.add(toTree(element, bodyRef));
            return elements;
        }
        if (value.getClass().isArray()) {
            List<Object> elements = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                elements.add(toTree(Array.get(value, index), bodyRef));
            }
            return elements;
        }
        if (value.getClass().isRecord()) {
            TreeMap<String, Object> fields = new TreeMap<>();
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                try {
                    Object componentValue = component.getAccessor().invoke(value);
                    String fieldName = component.getName();
                    String refName = inlineRefName(fieldName);
                    if (refName != null) {
                        fields.put(refName, bodyRef);
                    } else {
                        fields.put(fieldName, toTree(componentValue, bodyRef));
                    }
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("cannot canonicalize proposal payload", failure);
                }
            }
            return fields;
        }
        throw new IllegalArgumentException("unsupported payload value " + value.getClass().getName());
    }

    private static String inlineRefName(String name) {
        return switch (name) {
            case "inlineAnswerBody" -> "answerBodyRef";
            case "inlineCandidateResultBody" -> "candidateResultBodyRef";
            case "inlineCanonicalChangeBody" -> "workspaceChangeBodyRef";
            default -> null;
        };
    }

    private static void appendJson(StringBuilder output, Object value) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String text) {
            quote(output, text);
        } else if (value instanceof Boolean || value instanceof Number) {
            output.append(value);
        } else if (value instanceof List<?> list) {
            output.append('[');
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) output.append(',');
                appendJson(output, list.get(index));
            }
            output.append(']');
        } else if (value instanceof Map<?, ?> map) {
            output.append('{');
            var entries = map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> entry.getKey().toString())).toList();
            for (int index = 0; index < entries.size(); index++) {
                if (index > 0) output.append(',');
                quote(output, entries.get(index).getKey().toString());
                output.append(':');
                appendJson(output, entries.get(index).getValue());
            }
            output.append('}');
        } else {
            throw new IllegalArgumentException("unsupported canonical JSON value");
        }
    }

    private static void quote(StringBuilder output, String text) {
        output.append('"');
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) output.append(String.format("\\u%04x", (int) character));
                    else output.append(character);
                }
            }
        }
        output.append('"');
    }

    record Body(ChainContentKind kind, String value, String mediaType) {
    }

    record MaterializedPayload(
            String canonicalPayload,
            List<String> sourceRefs,
            String bodyAuthorityType,
            String bodyAuthorityRef) {
    }
}
