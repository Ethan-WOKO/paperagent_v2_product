package io.paperagent.v2.providers;

import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.ToolId;

public record ProposedToolCall(
        String providerCallId,
        ToolId toolId,
        ObjectValue arguments) {

    public ProposedToolCall {
        providerCallId = ProviderValues.id(
                providerCallId,
                "proposedToolCall.providerCallId");
        ProviderValues.required(toolId, "proposedToolCall.toolId");
        ProviderValues.required(arguments, "proposedToolCall.arguments");
    }
}
