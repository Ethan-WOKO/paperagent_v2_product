package io.paperagent.v2.chain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.HexFormat;

final class ChainValues {
    private ChainValues() {
    }

    static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static String requiredAscii(String value, String name) {
        required(value, name);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character > 0x7f) {
                throw new IllegalArgumentException(name + " must contain only ASCII system-identity characters");
            }
        }
        return value;
    }

    static String requiredSha256(String value, String name) {
        required(value, name);
        if (value.length() != 64) {
            throw new IllegalArgumentException(name + " must be a 64-character lowercase SHA-256 value");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f'))) {
                throw new IllegalArgumentException(name + " must be a 64-character lowercase SHA-256 value");
            }
        }
        return value;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static <T> List<T> copy(List<T> values, String name) {
        return List.copyOf(Objects.requireNonNull(values, name));
    }

    static <T> List<T> nonEmptyCopy(List<T> values, String name) {
        List<T> copy = copy(values, name);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return copy;
    }
}
