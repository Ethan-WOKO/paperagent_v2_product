package io.paperagent.v2.persistence;

public record IdempotencyKey(String scope, String key) {
}
