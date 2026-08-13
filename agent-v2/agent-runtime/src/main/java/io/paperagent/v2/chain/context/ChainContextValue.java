package io.paperagent.v2.chain.context;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strong, dependency-free canonical JSON value supplied by context projectors. */
public sealed interface ChainContextValue permits
        ChainContextValue.Text, ChainContextValue.NumberValue,
        ChainContextValue.BooleanValue, ChainContextValue.NullValue,
        ChainContextValue.ArrayValue, ChainContextValue.ObjectValue {

    Set<String> authorityRefs();

    static Text text(String value) {
        return new Text(value, Set.of());
    }

    static Text referencedText(String value, String authorityRef) {
        return new Text(value, Set.of(authorityRef));
    }

    static NumberValue number(long value) {
        return new NumberValue(value, Set.of());
    }

    static BooleanValue bool(boolean value) {
        return new BooleanValue(value, Set.of());
    }

    static NullValue nil() {
        return NullValue.INSTANCE;
    }

    static ArrayValue array(List<? extends ChainContextValue> values) {
        return new ArrayValue(List.copyOf(values));
    }

    static ObjectValue object(Map<String, ? extends ChainContextValue> values) {
        java.util.TreeMap<String, ChainContextValue> copy = new java.util.TreeMap<>();
        values.forEach((key, value) -> copy.put(key, value));
        return new ObjectValue(copy);
    }

    record Text(String value, Set<String> authorityRefs) implements ChainContextValue {
        public Text {
            value = Objects.requireNonNull(value, "value");
            authorityRefs = refs(authorityRefs);
        }

        public Text(String value) { this(value, Set.of()); }
    }

    record NumberValue(long value, Set<String> authorityRefs) implements ChainContextValue {
        public NumberValue { authorityRefs = refs(authorityRefs); }
        public NumberValue(long value) { this(value, Set.of()); }
    }

    record BooleanValue(boolean value, Set<String> authorityRefs) implements ChainContextValue {
        public BooleanValue { authorityRefs = refs(authorityRefs); }
        public BooleanValue(boolean value) { this(value, Set.of()); }
    }

    enum NullValue implements ChainContextValue {
        INSTANCE;

        @Override public Set<String> authorityRefs() { return Set.of(); }
    }

    record ArrayValue(List<ChainContextValue> values) implements ChainContextValue {
        public ArrayValue {
            values = List.copyOf(Objects.requireNonNull(values, "values"));
        }

        @Override
        public Set<String> authorityRefs() {
            return values.stream().flatMap(value -> value.authorityRefs().stream())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    record ObjectValue(Map<String, ChainContextValue> values) implements ChainContextValue {
        public ObjectValue {
            Objects.requireNonNull(values, "values");
            java.util.TreeMap<String, ChainContextValue> copy = new java.util.TreeMap<>();
            values.forEach((key, value) -> {
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("canonical object keys must not be blank");
                }
                copy.put(key, Objects.requireNonNull(value, "canonical object value"));
            });
            values = Map.copyOf(copy);
        }

        @Override
        public Set<String> authorityRefs() {
            return values.values().stream().flatMap(value -> value.authorityRefs().stream())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    private static Set<String> refs(Set<String> values) {
        Set<String> copy = Set.copyOf(Objects.requireNonNull(values, "authorityRefs"));
        if (copy.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("authorityRefs must not contain blank values");
        }
        return copy;
    }
}
