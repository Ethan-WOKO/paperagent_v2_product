package io.paperagent.v2.contracts;

public record ArtifactRef(String value) {
    public ArtifactRef {
        value = Contracts.id(value, "artifactRef");
    }
}
