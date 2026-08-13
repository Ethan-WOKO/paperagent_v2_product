package io.paperagent.v2.chain.model;

import io.paperagent.v2.chain.AnswerPayload;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainProposalPayload;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ExecutorPayload;
import io.paperagent.v2.chain.PlannerPayload;
import io.paperagent.v2.chain.ProviderRoleOutput;
import io.paperagent.v2.chain.ReflectorPayload;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dependency-free strict JSON parser for the chain Provider schema.
 *
 * <p>The parser accepts exactly {@code schemaVersion/kind/payload}, rejects
 * unknown fields at every record level, maps the payload to one of the 25
 * public typed contract records, and then runs the role/work-state checks.
 * Raw text is never retained by this object.</p>
 */
public final class StrictChainProviderOutputParser {
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "kind", "payload");

    public ProviderRoleOutput parse(
            String rawOutput,
            ChainRole expectedRole,
            ChainWorkState workState,
            String boundGapId) {
        validateRoleWorkState(expectedRole, workState);
        Object decoded = decodeJson(rawOutput);
        Map<String, Object> root = object(decoded, "$", true);
        rejectUnknown(root, ROOT_FIELDS, "$");
        requirePresent(root, ROOT_FIELDS, "$");

        String schemaVersion = string(root.get("schemaVersion"), "$.schemaVersion");
        if (!ProviderRoleOutput.SCHEMA_VERSION.equals(schemaVersion)) {
            fail(ChainProviderProtocolCode.UNSUPPORTED_SCHEMA_VERSION,
                    "$.schemaVersion", "unsupported schemaVersion");
        }
        String wireKind = string(root.get("kind"), "$.kind");
        ChainProposalKind kind;
        try {
            kind = ChainProposalKind.resolve(expectedRole, wireKind);
        } catch (RuntimeException failure) {
            throw new ChainProviderProtocolException(
                    ChainProviderProtocolCode.UNKNOWN_KIND, "$.kind", failure.getMessage());
        }

        Class<? extends ChainProposalPayload> payloadType = payloadType(kind);
        ChainProposalPayload payload;
        try {
            payload = payloadType.cast(convert(root.get("payload"), payloadType, "$.payload"));
            if (payload instanceof ExecutorPayload.WorkspaceChange workspaceChange) {
                validateCanonicalWorkspaceBody(workspaceChange);
            }
        } catch (ChainProviderProtocolException failure) {
            throw failure;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new ChainProviderProtocolException(
                    ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED,
                    "$.payload",
                    safeMessage(failure));
        }

        try {
            ProviderRoleOutput output = new ProviderRoleOutput(schemaVersion, wireKind, payload);
            output.validateFor(expectedRole, workState, boundGapId);
            return output;
        } catch (RuntimeException failure) {
            throw new ChainProviderProtocolException(
                    ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED,
                    "$.payload",
                    safeMessage(failure));
        }
    }

    private static Object decodeJson(String rawOutput) {
        try {
            return new JsonReader(rawOutput).read();
        } catch (ChainProviderProtocolException original) {
            String closed = original.code() == ChainProviderProtocolCode.INVALID_JSON
                    && original.getMessage().endsWith("before EOF")
                    ? closeCompleteContainers(rawOutput) : null;
            if (closed == null || closed.equals(rawOutput)) {
                throw original;
            }
            try {
                return new JsonReader(closed).read();
            } catch (ChainProviderProtocolException stillInvalid) {
                throw original;
            }
        }
    }

    /**
     * Restores only missing terminal object/array delimiters. It never edits a
     * token, string, member, value, or internal delimiter; the fully restored
     * text must still pass the same strict parser and typed contract.
     */
    private static String closeCompleteContainers(String input) {
        if (input == null || input.isBlank()) return null;
        ArrayDeque<Character> expected = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < input.length(); index++) {
            char value = input.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    inString = false;
                }
                continue;
            }
            if (value == '"') {
                inString = true;
            } else if (value == '{') {
                expected.push('}');
            } else if (value == '[') {
                expected.push(']');
            } else if (value == '}' || value == ']') {
                if (expected.isEmpty() || expected.pop() != value) return null;
            }
        }
        if (inString || escaped || expected.isEmpty()) return null;
        StringBuilder closed = new StringBuilder(input);
        expected.forEach(closed::append);
        return closed.toString();
    }

    private static Class<? extends ChainProposalPayload> payloadType(ChainProposalKind kind) {
        return switch (kind) {
            case PLANNER_DIRECT_ROUTE -> PlannerPayload.DirectRoute.class;
            case PLANNER_PERSISTENT_PLAN -> PlannerPayload.PersistentPlan.class;
            case PLANNER_PLAN_REVISION -> PlannerPayload.PlanRevision.class;
            case PLANNER_NEED_USER_INPUT -> PlannerPayload.NeedUserInput.class;
            case PLANNER_NEED_PERMISSION -> PlannerPayload.NeedPermission.class;
            case PLANNER_USER_INSTRUCTION_DISPOSITION -> PlannerPayload.UserInstructionDisposition.class;
            case PLANNER_PLANNING_BLOCKED -> PlannerPayload.PlanningBlocked.class;
            case EXECUTOR_TOOL_ACTION -> ExecutorPayload.ToolAction.class;
            case EXECUTOR_WORKSPACE_CHANGE -> ExecutorPayload.WorkspaceChange.class;
            case EXECUTOR_STEP_RESULT -> ExecutorPayload.StepResult.class;
            case EXECUTOR_STEP_BLOCKED -> ExecutorPayload.StepBlocked.class;
            case REFLECTOR_CONTINUE_STEP -> ReflectorPayload.ContinueStep.class;
            case REFLECTOR_ACCEPT_STEP -> ReflectorPayload.AcceptStep.class;
            case REFLECTOR_ACCEPT_STEP_AND_READY_TO_FINALIZE ->
                    ReflectorPayload.AcceptStepAndReadyToFinalize.class;
            case REFLECTOR_REPLAN_REQUIRED -> ReflectorPayload.ReplanRequired.class;
            case REFLECTOR_NEED_USER_INPUT -> ReflectorPayload.NeedUserInput.class;
            case REFLECTOR_NEED_PERMISSION -> ReflectorPayload.NeedPermission.class;
            case REFLECTOR_READY_TO_FINALIZE -> ReflectorPayload.ReadyToFinalize.class;
            case REFLECTOR_TASK_FAILED -> ReflectorPayload.TaskFailed.class;
            case ANSWER_DIRECT_ANSWER -> AnswerPayload.DirectAnswer.class;
            case ANSWER_ESCALATE_TO_PERSISTENT -> AnswerPayload.EscalateToPersistent.class;
            case ANSWER_USER_QUESTION -> AnswerPayload.UserQuestion.class;
            case ANSWER_STATUS_OR_FAILURE -> AnswerPayload.StatusOrFailure.class;
            case ANSWER_FINAL_DELIVERY -> AnswerPayload.FinalDelivery.class;
            case ANSWER_DELIVERY_BLOCKED -> AnswerPayload.DeliveryBlocked.class;
        };
    }

    private static Object convert(Object value, Type type, String path)
            throws ReflectiveOperationException {
        if (type instanceof ParameterizedType parameterized) {
            if (parameterized.getRawType() == List.class) {
                if (!(value instanceof List<?> values)) {
                    typeMismatch(path, "array");
                }
                Type elementType = parameterized.getActualTypeArguments()[0];
                List<Object> converted = new ArrayList<>();
                for (int index = 0; index < ((List<?>) value).size(); index++) {
                    converted.add(convert(((List<?>) value).get(index), elementType, path + "[" + index + "]"));
                }
                return List.copyOf(converted);
            }
            typeMismatch(path, "supported generic type");
        }
        if (!(type instanceof Class<?>)) {
            typeMismatch(path, "supported Java type");
        }
        Class<?> target = (Class<?>) type;
        if (target == String.class) {
            return string(value, path);
        }
        if (target == boolean.class || target == Boolean.class) {
            if (!(value instanceof Boolean)) {
                typeMismatch(path, "boolean");
            }
            return value;
        }
        if (target == int.class || target == Integer.class) {
            if (!(value instanceof Long number)
                    || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                typeMismatch(path, "integer");
            }
            return ((Long) value).intValue();
        }
        if (target == long.class || target == Long.class) {
            if (!(value instanceof Long)) {
                typeMismatch(path, "integer");
            }
            return value;
        }
        if (target.isEnum()) {
            String enumName = string(value, path);
            try {
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object enumValue = Enum.valueOf((Class) target, enumName);
                return enumValue;
            } catch (IllegalArgumentException failure) {
                typeMismatch(path, "known " + target.getSimpleName() + " value");
            }
        }
        if (target.isRecord()) {
            if (value == null) {
                return null;
            }
            Map<String, Object> object = object(value, path, false);
            RecordComponent[] components = target.getRecordComponents();
            Set<String> names = new LinkedHashSet<>();
            for (RecordComponent component : components) {
                names.add(component.getName());
            }
            rejectUnknown(object, names, path);

            Class<?>[] constructorTypes = new Class<?>[components.length];
            Object[] arguments = new Object[components.length];
            for (int index = 0; index < components.length; index++) {
                RecordComponent component = components[index];
                constructorTypes[index] = component.getType();
                if (!object.containsKey(component.getName())) {
                    if (component.getType().isPrimitive()) {
                        fail(ChainProviderProtocolCode.MISSING_FIELD,
                                path + "." + component.getName(), "required field is missing");
                    }
                    arguments[index] = null;
                } else if (object.get(component.getName()) == null) {
                    if (component.getType().isPrimitive()) {
                        typeMismatch(path + "." + component.getName(), component.getType().getSimpleName());
                    }
                    arguments[index] = null;
                } else {
                    arguments[index] = convert(
                            object.get(component.getName()),
                            component.getGenericType(),
                            path + "." + component.getName());
                }
            }
            Constructor<?> constructor = target.getDeclaredConstructor(constructorTypes);
            return constructor.newInstance(arguments);
        }
        typeMismatch(path, target.getSimpleName());
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String path, boolean root) {
        if (!(value instanceof Map<?, ?>)) {
            typeMismatch(path, "object");
        }
        return (Map<String, Object>) value;
    }

    private static String string(Object value, String path) {
        if (!(value instanceof String)) {
            typeMismatch(path, "string");
        }
        if (((String) value).isBlank()) {
            typeMismatch(path, "nonblank string");
        }
        return (String) value;
    }

    private static void requirePresent(Map<String, Object> values, Set<String> fields, String path) {
        for (String field : fields) {
            if (!values.containsKey(field)) {
                fail(ChainProviderProtocolCode.MISSING_FIELD,
                        path + "." + field, "required field is missing");
            }
        }
    }

    private static void rejectUnknown(Map<String, Object> values, Set<String> fields, String path) {
        for (String field : values.keySet()) {
            if (!fields.contains(field)) {
                fail(ChainProviderProtocolCode.UNKNOWN_FIELD,
                        path + "." + field, "unknown field");
            }
        }
    }

    private static void typeMismatch(String path, String expected) {
        fail(ChainProviderProtocolCode.TYPE_MISMATCH, path, "expected " + expected);
    }

    private static void fail(ChainProviderProtocolCode code, String path, String message) {
        throw new ChainProviderProtocolException(code, path, message);
    }

    private static String safeMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static void validateRoleWorkState(ChainRole role, ChainWorkState state) {
        if (role == null || state == null) {
            fail(ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED,
                    "$", "role and workState are required");
        }
        boolean allowed = switch (role) {
            case PLANNER -> state == ChainWorkState.PLANNING
                    || state == ChainWorkState.CLASSIFYING_INSTRUCTION
                    || state == ChainWorkState.VALIDATING_PENDING_ITEM;
            case EXECUTOR -> state == ChainWorkState.EXECUTING
                    || state == ChainWorkState.VALIDATING_PENDING_ITEM;
            case REFLECTOR -> state == ChainWorkState.AWAITING_REVIEW
                    || state == ChainWorkState.FINALIZING;
            case ANSWER -> state == ChainWorkState.DIRECT_ANSWERING
                    || state == ChainWorkState.WAITING_USER
                    || state == ChainWorkState.WAITING_PERMISSION
                    || state == ChainWorkState.DELIVERING
                    || state == ChainWorkState.TERMINAL;
        };
        if (!allowed) {
            fail(ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED,
                    "$", "role cannot be invoked from the supplied workState");
        }
    }

    private static void validateCanonicalWorkspaceBody(
            ExecutorPayload.WorkspaceChange workspaceChange) {
        String rawBody = workspaceChange.inlineCanonicalChangeBody();
        String bodyPath = "$.payload.inlineCanonicalChangeBody";
        Object decoded;
        try {
            decoded = new JsonReader(rawBody).read();
        } catch (ChainProviderProtocolException invalid) {
            fail(ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED,
                    bodyPath,
                    "workspace change body must be canonical JSON");
            return;
        }
        if (!(decoded instanceof Map<?, ?>)) {
            fail(ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED,
                    bodyPath,
                    "workspace change body must be a JSON object bundle");
        }
        Map<String, Object> root = object(decoded, bodyPath, true);
        requireExactBundleFields(root, Set.of("changes"), bodyPath);
        if (!(root.get("changes") instanceof List<?>)) {
            fail(ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED,
                    bodyPath + ".changes", "changes must be a non-empty array");
        }
        List<?> changes = (List<?>) root.get("changes");
        if (changes.isEmpty()) {
            fail(ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED,
                    bodyPath + ".changes", "changes must be a non-empty array");
        }
        List<String> targetFiles = workspaceChange.targetFiles();
        if (changes.size() != targetFiles.size()) {
            fail(ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED,
                    bodyPath + ".changes",
                    "changes must exactly match targetFiles in the same order");
        }
        Set<String> foldedPaths = new LinkedHashSet<>();
        Set<String> foldedTargets = new LinkedHashSet<>();
        for (int index = 0; index < changes.size(); index++) {
            String itemPath = bodyPath + ".changes[" + index + "]";
            Map<String, Object> item = object(changes.get(index), itemPath, false);
            String type = requiredBundleString(item, "type", itemPath, false);
            Set<String> requiredFields = switch (type) {
                case "ADD", "MODIFY" -> Set.of(
                        "type", "path", "expectedBaselineSha256", "text");
                case "DELETE" -> Set.of(
                        "type", "path", "expectedBaselineSha256");
                default -> {
                    fail(ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED,
                            itemPath + ".type",
                            "type must be ADD, MODIFY, or DELETE");
                    yield Set.of();
                }
            };
            requireExactBundleFields(item, requiredFields, itemPath);
            String path = requiredBundleString(item, "path", itemPath, false);
            String baseline = requiredBundleString(
                    item, "expectedBaselineSha256", itemPath, false);
            if ((type.equals("ADD") && !baseline.equals("NONE"))
                    || (!type.equals("ADD")
                    && !baseline.matches("[0-9a-f]{64}"))) {
                fail(ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED,
                        itemPath + ".expectedBaselineSha256",
                        type.equals("ADD")
                                ? "ADD baseline must be the literal NONE"
                                : "MODIFY and DELETE baseline must be lowercase SHA-256");
            }
            if (!type.equals("DELETE")) {
                requiredBundleString(item, "text", itemPath, true);
            }
            String foldedPath = path.toLowerCase(Locale.ROOT);
            if (!foldedPaths.add(foldedPath)) {
                fail(ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED,
                        itemPath + ".path",
                        "change paths must be unique after case folding");
            }
            String targetFile = targetFiles.get(index);
            if (targetFile == null || targetFile.isBlank()
                    || !foldedTargets.add(targetFile.toLowerCase(Locale.ROOT))) {
                fail(ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED,
                        "$.payload.targetFiles[" + index + "]",
                        "targetFiles must be nonblank and unique after case folding");
            }
            if (!path.equals(targetFile)) {
                fail(ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED,
                        itemPath + ".path",
                        "changes must exactly match targetFiles in the same order");
            }
        }
        StringBuilder canonical = new StringBuilder();
        appendCanonicalJson(canonical, decoded);
        if (!rawBody.equals(canonical.toString())) {
            fail(ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED,
                    bodyPath,
                    "workspace change body must use canonical JSON bytes");
        }
    }

    private static void requireExactBundleFields(
            Map<String, Object> values, Set<String> expected, String path) {
        if (!values.keySet().equals(expected)) {
            fail(ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED, path,
                    "fields must be exactly " + expected);
        }
    }

    private static String requiredBundleString(
            Map<String, Object> values,
            String field,
            String path,
            boolean allowEmpty) {
        Object value = values.get(field);
        if (!(value instanceof String text)
                || (!allowEmpty && text.isBlank())) {
            fail(ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED,
                    path + "." + field,
                    allowEmpty ? "field must be a string"
                            : "field must be a nonblank string");
        }
        return (String) value;
    }

    private static void appendCanonicalJson(StringBuilder output, Object value) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String text) {
            appendQuoted(output, text);
        } else if (value instanceof Boolean || value instanceof Long) {
            output.append(value);
        } else if (value instanceof List<?> values) {
            output.append('[');
            for (int index = 0; index < values.size(); index++) {
                if (index > 0) output.append(',');
                appendCanonicalJson(output, values.get(index));
            }
            output.append(']');
        } else if (value instanceof Map<?, ?> values) {
            output.append('{');
            List<? extends Map.Entry<?, ?>> entries = values.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(entry -> entry.getKey().toString()))
                    .toList();
            for (int index = 0; index < entries.size(); index++) {
                if (index > 0) output.append(',');
                Map.Entry<?, ?> entry = entries.get(index);
                appendQuoted(output, entry.getKey().toString());
                output.append(':');
                appendCanonicalJson(output, entry.getValue());
            }
            output.append('}');
        } else {
            fail(ChainProviderProtocolCode.PAYLOAD_VALIDATION_FAILED,
                    "$.payload.inlineCanonicalChangeBody",
                    "unsupported canonical JSON value");
        }
    }

    private static void appendQuoted(StringBuilder output, String value) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }

    private static final class JsonReader {
        private final String input;
        private int offset;

        private JsonReader(String input) {
            if (input == null || input.isBlank()) {
                fail(ChainProviderProtocolCode.INVALID_JSON, "$", "provider output is blank");
            }
            this.input = input;
        }

        private Object read() {
            Object value = value("$");
            whitespace();
            if (offset != input.length()) {
                invalid("$", "unexpected trailing content");
            }
            return value;
        }

        private Object value(String path) {
            whitespace();
            if (offset >= input.length()) {
                invalid(path, "unexpected end of JSON");
            }
            return switch (input.charAt(offset)) {
                case '{' -> object(path);
                case '[' -> array(path);
                case '"' -> stringLiteral(path);
                case 't' -> literal("true", Boolean.TRUE, path);
                case 'f' -> literal("false", Boolean.FALSE, path);
                case 'n' -> literal("null", null, path);
                default -> number(path);
            };
        }

        private Map<String, Object> object(String path) {
            offset++;
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            whitespace();
            if (take('}')) {
                return result;
            }
            while (true) {
                whitespace();
                if (offset >= input.length() || input.charAt(offset) != '"') {
                    invalid(path, "object key must be a string");
                }
                String key = stringLiteral(path);
                if (result.containsKey(key)) {
                    invalid(path + "." + key, "duplicate object key");
                }
                whitespace();
                expect(':', path);
                result.put(key, value(path + "." + key));
                whitespace();
                if (take('}')) {
                    return result;
                }
                expect(',', path);
            }
        }

        private List<Object> array(String path) {
            offset++;
            ArrayList<Object> result = new ArrayList<>();
            whitespace();
            if (take(']')) {
                return result;
            }
            while (true) {
                result.add(value(path + "[" + result.size() + "]"));
                whitespace();
                if (take(']')) {
                    return result;
                }
                expect(',', path);
            }
        }

        private String stringLiteral(String path) {
            expect('"', path);
            StringBuilder result = new StringBuilder();
            while (offset < input.length()) {
                char character = input.charAt(offset++);
                if (character == '"') {
                    return result.toString();
                }
                if (character == '\\') {
                    if (offset >= input.length()) {
                        invalid(path, "unterminated escape");
                    }
                    char escaped = input.charAt(offset++);
                    switch (escaped) {
                        case '"', '\\', '/' -> result.append(escaped);
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> result.append(unicode(path));
                        default -> invalid(path, "unsupported escape");
                    }
                } else {
                    if (character < 0x20) {
                        invalid(path, "control character in string");
                    }
                    result.append(character);
                }
            }
            invalid(path, "unterminated string");
            return "";
        }

        private char unicode(String path) {
            if (offset + 4 > input.length()) {
                invalid(path, "incomplete unicode escape");
            }
            try {
                char value = (char) Integer.parseInt(input.substring(offset, offset + 4), 16);
                offset += 4;
                return value;
            } catch (NumberFormatException failure) {
                invalid(path, "invalid unicode escape");
                return 0;
            }
        }

        private Object number(String path) {
            int start = offset;
            if (take('-') && offset >= input.length()) {
                invalid(path, "invalid number");
            }
            while (offset < input.length() && Character.isDigit(input.charAt(offset))) {
                offset++;
            }
            if (start == offset || (input.charAt(start) == '-' && start + 1 == offset)) {
                invalid(path, "unexpected token");
            }
            if (offset < input.length()
                    && (input.charAt(offset) == '.' || input.charAt(offset) == 'e' || input.charAt(offset) == 'E')) {
                invalid(path, "only integral JSON numbers are supported by the chain schema");
            }
            try {
                return Long.parseLong(input.substring(start, offset));
            } catch (NumberFormatException failure) {
                invalid(path, "integer is out of range");
                return 0L;
            }
        }

        private Object literal(String literal, Object value, String path) {
            if (!input.startsWith(literal, offset)) {
                invalid(path, "unexpected token");
            }
            offset += literal.length();
            return value;
        }

        private void whitespace() {
            while (offset < input.length() && Character.isWhitespace(input.charAt(offset))) {
                offset++;
            }
        }

        private boolean take(char expected) {
            if (offset < input.length() && input.charAt(offset) == expected) {
                offset++;
                return true;
            }
            return false;
        }

        private void expect(char expected, String path) {
            if (!take(expected)) {
                invalid(path, "expected '" + expected + "'");
            }
        }

        private void invalid(String path, String message) {
            String next = offset >= input.length()
                    ? "EOF"
                    : String.format("U+%04X", (int) input.charAt(offset));
            fail(ChainProviderProtocolCode.INVALID_JSON, path,
                    message + " at offset " + offset + " before " + next);
        }
    }
}
