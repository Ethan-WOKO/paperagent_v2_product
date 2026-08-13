package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.contracts.BooleanValue;
import io.paperagent.v2.contracts.ContractValue;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.NullValue;
import io.paperagent.v2.contracts.NumberValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolDescriptor;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** Complete canonical value encoding for a typed granted-tool projection. */
public final class ProductChainToolContextValueCodec {
    private ProductChainToolContextValueCodec() {
    }

    public static Projection encode(
            ProductChainToolContextProjection.Projection source) {
        Objects.requireNonNull(source, "source");
        List<ChainContextValue> schemas = source.completeToolSchemas().stream()
                .sorted(Comparator.comparing(value ->
                        value.descriptor().id().value()))
                .map(ProductChainToolContextValueCodec::toolSchema)
                .map(value -> (ChainContextValue) value)
                .toList();
        ChainContextValue value = ChainContextValue.object(Map.of(
                "schemaVersion", text(source.schemaVersion()),
                "taskFrameRef", referenced(source.taskFrameRef()),
                "permissionTierRef", referenced(
                        source.permissionTierRef()),
                "completeToolSchemas", ChainContextValue.array(schemas),
                "permissionRefs", references(source.permissionRefs().stream()
                        .sorted().toList()),
                "summary", text(source.summary())));
        ChainContextValue plannerValue = plannerValue(source);
        String canonical = ProductChainContractProjectionCodec
                .canonicalJson(value);
        String plannerCanonical = ProductChainContractProjectionCodec
                .canonicalJson(plannerValue);
        return new Projection(
                value,
                ProductChainContractProjectionCodec.sha256(canonical),
                canonical,
                plannerValue,
                ProductChainContractProjectionCodec.sha256(
                        plannerCanonical),
                plannerCanonical);
    }

    /**
     * Planner-visible feasibility catalog. Planning is intentionally tool-free:
     * it needs to know which operations and routing boundaries are available,
     * but never needs invocation arguments or effect-time authority details.
     */
    private static ChainContextValue plannerValue(
            ProductChainToolContextProjection.Projection source) {
        TreeSet<String> capabilities = new TreeSet<>();
        TreeSet<String> routingRequirements = new TreeSet<>();
        source.completeToolSchemas().forEach(value -> {
            value.requiredCapabilities().forEach(capability ->
                    capabilities.add(capability.name()));
            value.routingRequirements().forEach(requirement ->
                    routingRequirements.add(requirement.name()));
        });
        return ChainContextValue.object(Map.of(
                "schemaVersion", text(source.schemaVersion()
                        + "-planner-feasibility-v2"),
                "permissionAuthorityRef", referenced(
                        source.taskFrameRef()),
                "permissionTierRef", referenced(
                        source.permissionTierRef()),
                "grantedOperationCount", ChainContextValue.number(
                        source.completeToolSchemas().size()),
                "availableCapabilities", strings(
                        List.copyOf(capabilities)),
                "availableRoutingRequirements", strings(
                        List.copyOf(routingRequirements)),
                "summary", text(source.completeToolSchemas().isEmpty()
                        ? "No product operation is available under the frozen permission authority."
                        : "Product operations are available for the listed capabilities and routing requirements; Planner does not select a concrete tool.")));
    }

    private static ChainContextValue.ObjectValue toolSchema(
            ProductChainToolContextProjection.ToolSchema source) {
        return ChainContextValue.object(Map.of(
                "publicAlias", text(source.publicAlias()),
                "summary", text(source.summary()),
                "descriptor", descriptor(source.descriptor()),
                "permissionRef", referenced(source.permissionRef()),
                "allowedExecutionTiers", strings(
                        source.allowedExecutionTiers().stream()
                                .map(Enum::name).sorted().toList()),
                "requiredCapabilities", strings(
                        source.requiredCapabilities().stream()
                                .map(Enum::name).sorted().toList()),
                "requiredNetworkAllowlistEntries", strings(
                        source.requiredNetworkAllowlistEntries().stream()
                                .sorted().toList()),
                "routingRequirements", strings(
                        source.routingRequirements().stream()
                                .map(Enum::name).sorted().toList()),
                "executionTarget", text(source.executionTarget().name())));
    }

    private static ChainContextValue descriptor(ToolDescriptor source) {
        return ChainContextValue.object(Map.of(
                "id", referenced(source.id().value()),
                "description", text(source.description()),
                "requiredCapabilities", strings(
                        source.requiredCapabilities().stream()
                                .map(Enum::name).sorted().toList()),
                "parameterSchema", contractValue(source.parameterSchema())));
    }

    private static ChainContextValue contractValue(ContractValue source) {
        Objects.requireNonNull(source, "contract value");
        if (source instanceof TextValue value) {
            return text(value.value());
        }
        if (source instanceof NumberValue value) {
            try {
                return ChainContextValue.number(value.value().longValueExact());
            } catch (ArithmeticException invalid) {
                throw new IllegalArgumentException(
                        "tool schema number must be an exact 64-bit integer",
                        invalid);
            }
        }
        if (source instanceof BooleanValue value) {
            return ChainContextValue.bool(value.value());
        }
        if (source instanceof NullValue) {
            return ChainContextValue.nil();
        }
        if (source instanceof ListValue value) {
            return ChainContextValue.array(value.values().stream()
                    .map(ProductChainToolContextValueCodec::contractValue)
                    .toList());
        }
        if (source instanceof ObjectValue value) {
            TreeMap<String, ChainContextValue> fields = new TreeMap<>();
            value.values().forEach((key, item) ->
                    fields.put(key, contractValue(item)));
            return ChainContextValue.object(fields);
        }
        throw new IllegalArgumentException(
                "unsupported formal tool schema value: "
                        + source.getClass().getName());
    }

    private static ChainContextValue.ArrayValue strings(List<String> values) {
        return ChainContextValue.array(values.stream()
                .map(ProductChainToolContextValueCodec::text).toList());
    }

    private static ChainContextValue.ArrayValue references(
            List<String> values) {
        return ChainContextValue.array(values.stream()
                .map(ProductChainToolContextValueCodec::referenced).toList());
    }

    private static ChainContextValue.Text referenced(String value) {
        return ChainContextValue.referencedText(value, value);
    }

    private static ChainContextValue.Text text(String value) {
        return ChainContextValue.text(value);
    }

    public record Projection(
            ChainContextValue value, String sha256, String canonicalJson,
            ChainContextValue plannerValue, String plannerSha256,
            String plannerCanonicalJson) {
        public Projection {
            Objects.requireNonNull(value, "value");
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 must be canonical");
            }
            Objects.requireNonNull(canonicalJson, "canonicalJson");
            Objects.requireNonNull(plannerValue, "plannerValue");
            if (plannerSha256 == null
                    || !plannerSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "plannerSha256 must be canonical");
            }
            Objects.requireNonNull(
                    plannerCanonicalJson, "plannerCanonicalJson");
        }
    }
}
