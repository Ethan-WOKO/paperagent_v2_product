package com.yanban.api.agent.v2.chain.persistence;

import io.paperagent.v2.chain.ChainPersistenceRecords.CanonicalJson;
import io.paperagent.v2.chain.ChainPersistenceRecords.FormattedJson;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainProposalKind;
import io.paperagent.v2.chain.ChainRole;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Explicit record/column codec shared by the five chain persistence authorities. */
@Component
final class ProductChainRecordCodec {

    Map<String, Object> encode(Record value) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                String prefix = column(component.getName());
                Object field = component.getAccessor().invoke(value);
                if (component.getType() == CanonicalJson.class) {
                    CanonicalJson json = (CanonicalJson) field;
                    if (json != null && !sha256(json.json()).equals(
                            json.sha256())) {
                        throw new ProductChainPersistenceException(
                                "CHAIN_CANONICAL_JSON_DIGEST_MISMATCH");
                    }
                    result.put(prefix + "_format_version",
                            json == null ? null : json.formatVersion());
                    result.put(hashColumn(prefix),
                            json == null ? null : json.sha256());
                    result.put(prefix + "_json",
                            json == null ? null : json.json());
                } else if (component.getType() == FormattedJson.class) {
                    FormattedJson json = (FormattedJson) field;
                    result.put(prefix + "_format_version",
                            json == null ? null : json.formatVersion());
                    result.put(prefix + "_json",
                            json == null ? null : json.json());
                } else if (component.getName().equals("granted")) {
                    result.put("decision", Boolean.TRUE.equals(field)
                            ? "GRANTED" : "DENIED");
                } else if (field instanceof ChainContextModule module) {
                    result.put(prefix, module.wireName());
                } else if (field instanceof ChainProposalKind kind) {
                    result.put(prefix, kind.wireName());
                } else if (field instanceof Enum<?> enumeration) {
                    result.put(prefix, enumeration.name());
                } else if (field instanceof Boolean flag) {
                    result.put(prefix, flag ? 1 : 0);
                } else if (field instanceof Instant instant) {
                    result.put(prefix, Timestamp.from(
                            instant.truncatedTo(ChronoUnit.MICROS)));
                } else {
                    result.put(prefix, field);
                }
            }
            return result;
        } catch (ReflectiveOperationException exception) {
            throw new ProductChainPersistenceException(
                    "CHAIN_RECORD_ENCODING_FAILED", exception);
        }
    }

    <T extends Record> T decode(Class<T> type, Map<String, Object> source) {
        Map<String, Object> row = normalize(source);
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];
        try {
            for (int index = 0; index < components.length; index++) {
                RecordComponent component = components[index];
                parameterTypes[index] = component.getType();
                String prefix = column(component.getName());
                if (component.getType() == CanonicalJson.class) {
                    Number format = number(row.get(prefix + "_format_version"));
                    if (format == null) {
                        arguments[index] = null;
                    } else {
                        String storedHash = string(row.get(hashColumn(prefix)));
                        String storedJson = string(row.get(prefix + "_json"));
                        if (!sha256(storedJson).equals(storedHash)) {
                            throw new ProductChainPersistenceException(
                                    "CHAIN_CANONICAL_JSON_DIGEST_MISMATCH");
                        }
                        arguments[index] = new CanonicalJson(
                                format.intValue(), storedHash, storedJson);
                    }
                } else if (component.getType() == FormattedJson.class) {
                    Number format = number(row.get(prefix + "_format_version"));
                    arguments[index] = format == null ? null : new FormattedJson(
                            format.intValue(), string(row.get(prefix + "_json")));
                } else if (component.getName().equals("granted")) {
                    arguments[index] = "GRANTED".equals(string(row.get("decision")));
                } else {
                    if (component.getType() == ChainProposalKind.class) {
                        ChainRole role = row.containsKey("role")
                                ? ChainRole.valueOf(string(row.get("role")))
                                : ChainRole.REFLECTOR;
                        arguments[index] = ChainProposalKind.resolve(
                                role, string(row.get(prefix)));
                    } else {
                        arguments[index] = scalar(
                                component.getType(), row.get(prefix));
                    }
                }
            }
            Constructor<T> constructor = type.getDeclaredConstructor(parameterTypes);
            return constructor.newInstance(arguments);
        } catch (ReflectiveOperationException | SQLException exception) {
            throw new ProductChainPersistenceException(
                    "CHAIN_RECORD_DECODING_FAILED", exception);
        }
    }

    private static Object scalar(Class<?> type, Object raw) throws SQLException {
        if (raw == null) {
            return null;
        }
        if (type == String.class) {
            return string(raw);
        }
        if (type == long.class || type == Long.class) {
            return number(raw).longValue();
        }
        if (type == int.class || type == Integer.class) {
            return number(raw).intValue();
        }
        if (type == boolean.class || type == Boolean.class) {
            return raw instanceof Number number
                    ? number.intValue() != 0
                    : Boolean.parseBoolean(raw.toString());
        }
        if (type == Instant.class) {
            if (raw instanceof Timestamp timestamp) {
                return timestamp.toInstant();
            }
            if (raw instanceof LocalDateTime local) {
                return local.toInstant(ZoneOffset.UTC);
            }
            if (raw instanceof Instant instant) {
                return instant;
            }
        }
        if (type == ChainContextModule.class) {
            String wire = string(raw);
            return java.util.Arrays.stream(ChainContextModule.values())
                    .filter(module -> module.wireName().equals(wire))
                    .findFirst().orElseThrow(() ->
                            new ProductChainPersistenceException(
                                    "CHAIN_CONTEXT_MODULE_KIND_UNKNOWN"));
        }
        if (type.isEnum()) {
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object enumeration = Enum.valueOf((Class<? extends Enum>) type,
                    string(raw));
            return enumeration;
        }
        return raw;
    }

    private static Map<String, Object> normalize(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(
                key.toLowerCase(Locale.ROOT), value));
        return result;
    }

    private static Number number(Object value) {
        return value == null ? null : (Number) value;
    }

    private static String string(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof Clob clob) {
            return clob.getSubString(1, Math.toIntExact(clob.length()));
        }
        return value.toString();
    }

    static String column(String javaName) {
        if (javaName.equals("module")) {
            return "module_kind";
        }
        if (javaName.equals("directTaskSpecification")) {
            return "direct_task_spec";
        }
        StringBuilder result = new StringBuilder(javaName.length() + 8);
        for (int index = 0; index < javaName.length(); index++) {
            char current = javaName.charAt(index);
            if (Character.isUpperCase(current)) {
                result.append('_').append(Character.toLowerCase(current));
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String hashColumn(String prefix) {
        return prefix.equals("projection")
                ? "projection_digest" : prefix + "_sha256";
    }
}
