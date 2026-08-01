package com.yanban.api.agent;

import java.util.List;
import java.util.Objects;

/** Auditable coverage requirement. It describes tools already allowed; it grants none. */
public record ResearchMaterialRequirement(
        ResearchMaterialKind material,
        List<String> acceptedTools,
        List<String> availableTools,
        boolean covered
) {
    public ResearchMaterialRequirement {
        Objects.requireNonNull(material, "material must not be null");
        acceptedTools = acceptedTools == null ? List.of() : List.copyOf(acceptedTools);
        availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
        if (!acceptedTools.containsAll(availableTools)) {
            throw new IllegalArgumentException("available material tools must be accepted tools");
        }
        if (covered != !availableTools.isEmpty()) {
            throw new IllegalArgumentException("material coverage must match available tools");
        }
    }
}
