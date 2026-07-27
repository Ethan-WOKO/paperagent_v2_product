package io.paperagent.v2.contracts;

/**
 * An opaque logical reference. Secret values are deliberately not representable.
 */
public record SecretRef(String name) {
    public SecretRef {
        Contracts.text(name, "secretRef.name");
        if (!name.matches("[A-Za-z][A-Za-z0-9._/-]{0,127}") || name.contains("=")) {
            Contracts.fail(ViolationCode.SECRET_VALUE_NOT_ALLOWED, "secretRef.name",
                    "secret references must be logical names, never key/value material");
        }
    }
}
