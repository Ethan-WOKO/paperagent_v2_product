package io.paperagent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords.CanonicalJson;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Runtime-canonicalized source values for one frozen context module. */
public final class ChainContextSourceSnapshot {
    private final ChainContextModule module;
    private final ChainContextModuleStatus presenceKind;
    private final CanonicalJson sourceVersion;
    private final CanonicalJson readBoundary;
    private final String projectionVersion;
    private final String paginationVersion;
    private final CanonicalJson projectionParameters;
    private final CanonicalJson projection;
    private final Set<String> projectionFieldNames;
    private final Set<String> visibleSourceRefs;

    public ChainContextSourceSnapshot(
            ChainContextModule module,
            ChainContextModuleStatus presenceKind,
            Map<String, ? extends ChainContextValue> sourceVersionComponents,
            Map<String, ? extends ChainContextValue> readBoundaryComponents,
            String projectionVersion,
            String paginationVersion,
            Map<String, ? extends ChainContextValue> projectionParameters,
            Map<String, ? extends ChainContextValue> projectionFields,
            String emptyWatermark) {
        this.module = Objects.requireNonNull(module, "module");
        this.presenceKind = Objects.requireNonNull(presenceKind, "presenceKind");
        this.projectionVersion = required(projectionVersion, "projectionVersion");
        this.paginationVersion = required(paginationVersion, "paginationVersion");
        Map<String, ChainContextValue> source = copy(sourceVersionComponents, "sourceVersionComponents");
        Map<String, ChainContextValue> boundary = copy(readBoundaryComponents, "readBoundaryComponents");
        Map<String, ChainContextValue> parameters = copy(projectionParameters, "projectionParameters");
        Map<String, ChainContextValue> fields = copy(projectionFields, "projectionFields");
        ChainContextVersionMatrix.VersionRequirement version =
                ChainContextVersionMatrix.requirement(module);
        requireExactKeys(source.keySet(), version.sourceVersionFields(), "sourceVersionComponents");
        requireExactKeys(boundary.keySet(), version.readBoundaryFields(), "readBoundaryComponents");
        if (presenceKind == ChainContextModuleStatus.EMPTY) {
            if (!version.emptyAllowed()) {
                throw new ChainContextException(
                        ChainContextErrorCode.CONTEXT_REQUIRED_MODULE_EMPTY,
                        module + " cannot be EMPTY");
            }
            if (!version.emptyWatermark().equals(emptyWatermark)) {
                throw new ChainContextException(
                        ChainContextErrorCode.CONTEXT_EMPTY_PROJECTION_INVALID,
                        "EMPTY watermark does not match the frozen module contract");
            }
            if (!fields.isEmpty()) {
                throw new ChainContextException(
                        ChainContextErrorCode.CONTEXT_EMPTY_PROJECTION_INVALID,
                        "EMPTY module cannot carry semantic projection fields");
            }
        } else if (emptyWatermark != null) {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_EMPTY_PROJECTION_INVALID,
                    "PRESENT module cannot carry an EMPTY watermark");
        }
        this.sourceVersion = ChainContextCanonicalJson.canonicalObject(source);
        this.readBoundary = ChainContextCanonicalJson.canonicalObject(boundary);
        this.projectionParameters = ChainContextCanonicalJson.canonicalObject(parameters);
        this.projectionFieldNames = Set.copyOf(fields.keySet());
        this.visibleSourceRefs = java.util.Collections.unmodifiableSet(
                new java.util.TreeSet<>(unionRefs(source, boundary, parameters, fields)));
        this.projection = ChainContextCanonicalJson.canonicalObject(projectionEnvelope(
                module, presenceKind, fields, emptyWatermark, visibleSourceRefs));
    }

    public ChainContextModule module() { return module; }

    public ChainContextModuleStatus presenceKind() { return presenceKind; }

    public CanonicalJson sourceVersion() { return sourceVersion; }

    public CanonicalJson readBoundary() { return readBoundary; }

    public String projectionVersion() { return projectionVersion; }

    public String paginationVersion() { return paginationVersion; }

    public CanonicalJson projectionParameters() { return projectionParameters; }

    public CanonicalJson projection() { return projection; }

    public Set<String> projectionFieldNames() { return projectionFieldNames; }

    public Set<String> visibleSourceRefs() { return visibleSourceRefs; }

    private static Map<String, ChainContextValue> projectionEnvelope(
            ChainContextModule module,
            ChainContextModuleStatus status,
            Map<String, ChainContextValue> fields,
            String emptyWatermark,
            Set<String> visibleRefs) {
        TreeMap<String, ChainContextValue> envelope = new TreeMap<>();
        envelope.put("fields", ChainContextValue.object(fields));
        envelope.put("module", ChainContextValue.text(module.wireName()));
        envelope.put("status", ChainContextValue.text(status.name()));
        List<ChainContextValue> refs = visibleRefs.stream().sorted()
                .map(value -> (ChainContextValue) ChainContextValue.text(value)).toList();
        envelope.put("visibleSourceRefs", ChainContextValue.array(refs));
        if (emptyWatermark != null) {
            envelope.put("emptyWatermark", ChainContextValue.text(emptyWatermark));
        }
        return envelope;
    }

    @SafeVarargs
    private static Set<String> unionRefs(Map<String, ChainContextValue>... groups) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (Map<String, ChainContextValue> group : groups) {
            group.values().forEach(value -> refs.addAll(value.authorityRefs()));
        }
        return Set.copyOf(refs);
    }

    private static Map<String, ChainContextValue> copy(
            Map<String, ? extends ChainContextValue> values, String name) {
        Objects.requireNonNull(values, name);
        TreeMap<String, ChainContextValue> copy = new TreeMap<>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException(name + " keys must not be blank");
            }
            copy.put(key, Objects.requireNonNull(value, name + " value"));
        });
        return Map.copyOf(copy);
    }

    private static void requireExactKeys(Set<String> actual, List<String> expected, String name) {
        if (!actual.equals(Set.copyOf(expected))) {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_MODULE_REQUIREMENTS_MISSING,
                    name + " must contain exactly the frozen component vector " + expected);
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
