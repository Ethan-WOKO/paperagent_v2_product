package io.paperagent.v2.runtime.routing;

public record RoutingRequestId(String value) {
    public RoutingRequestId {
        value = RoutingValues.id(value, "routingRequestId.value");
    }
}
