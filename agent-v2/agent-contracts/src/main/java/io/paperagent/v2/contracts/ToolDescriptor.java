package io.paperagent.v2.contracts;

import java.util.Set;

public record ToolDescriptor(
        ToolId id,
        String description,
        Set<Capability> requiredCapabilities) {

    public ToolDescriptor {
        Contracts.required(id, "toolDescriptor.id");
        description = Contracts.text(description, "toolDescriptor.description");
        requiredCapabilities = Contracts.set(requiredCapabilities, "toolDescriptor.requiredCapabilities");
    }
}
