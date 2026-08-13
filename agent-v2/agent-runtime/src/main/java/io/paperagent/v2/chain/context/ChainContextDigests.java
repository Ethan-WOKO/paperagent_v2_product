package io.paperagent.v2.chain.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class ChainContextDigests {
    private ChainContextDigests() {
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static void verify(String json, String expected, String name) {
        if (!sha256(json).equals(expected)) {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_MODULE_DIGEST_MISMATCH,
                    name + " digest does not match its frozen JSON");
        }
    }
}
