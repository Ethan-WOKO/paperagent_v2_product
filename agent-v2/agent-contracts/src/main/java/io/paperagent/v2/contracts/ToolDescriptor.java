package io.paperagent.v2.contracts;

import java.util.Map;
import java.util.Set;

public record ToolDescriptor(
        ToolId id,
        String description,
        Set<Capability> requiredCapabilities,
        ObjectValue parameterSchema) {

    public ToolDescriptor(
            ToolId id,
            String description,
            Set<Capability> requiredCapabilities) {
        this(id, description, requiredCapabilities, permissiveObjectSchema());
    }

    public ToolDescriptor {
        Contracts.required(id, "toolDescriptor.id");
        description = Contracts.text(description, "toolDescriptor.description");
        requiredCapabilities = Contracts.set(requiredCapabilities, "toolDescriptor.requiredCapabilities");
        Contracts.required(parameterSchema, "toolDescriptor.parameterSchema");
        if (!(parameterSchema.values().get("type") instanceof TextValue type)
                || !"object".equals(type.value())) {
            Contracts.fail(
                    ViolationCode.INCONSISTENT_REFERENCE,
                    "toolDescriptor.parameterSchema.type",
                    "tool parameter schema root must be an object");
        }
    }

    private static ObjectValue permissiveObjectSchema() {
        return new ObjectValue(Map.of(
                "type", new TextValue("object"),
                "additionalProperties", new BooleanValue(true)));
    }
}
