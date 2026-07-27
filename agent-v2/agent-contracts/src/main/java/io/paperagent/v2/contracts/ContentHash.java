package io.paperagent.v2.contracts;

public record ContentHash(String algorithm, String value) {
    public ContentHash {
        algorithm = Contracts.text(algorithm, "contentHash.algorithm");
        value = Contracts.text(value, "contentHash.value");
        if (!algorithm.equals("sha256") || !value.matches("[a-f0-9]{64}")) {
            Contracts.fail(ViolationCode.INVALID_HASH, "contentHash",
                    "Wave 1 hashes use lowercase sha256");
        }
    }
}
