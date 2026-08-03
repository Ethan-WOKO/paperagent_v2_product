package com.yanban.api.agent.v2.context.runtime;

import com.yanban.api.agent.v2.context.V2ContextRevisionStatus;
import com.yanban.api.agent.v2.context.V2ContextStage;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class V2ContextStageKeyFactory {
    private static final Pattern PART = Pattern.compile("[A-Za-z0-9._-]{1,96}");

    public String logicalKey(
            V2ContextStage stage,
            List<String> canonicalAuthorityTuple,
            String subCall) {
        if (stage == null || canonicalAuthorityTuple == null
                || canonicalAuthorityTuple.isEmpty()) {
            throw new IllegalArgumentException("stage and authority tuple are required");
        }
        String canonical = canonicalAuthorityTuple.stream()
                .map(value -> part(value, "authorityTuple"))
                .reduce((left, right) -> left + "\u001f" + right)
                .orElseThrow();
        return "ctx-v1/" + stage.name().toLowerCase(Locale.ROOT) + "/"
                + sha256(canonical) + "/" + part(subCall, "subCall");
    }

    public String childKey(String logicalKey,
                           V2ContextRevisionStatus phase,
                           int attempt) {
        if (logicalKey == null || logicalKey.isBlank() || phase == null
                || phase == V2ContextRevisionStatus.READY || attempt < 1) {
            throw new IllegalArgumentException("child phase key is invalid");
        }
        String phasePath = switch (phase) {
            case COMPACTION_REQUIRED -> "required";
            case COMPACTING -> "compacting";
            case FAILED -> "failed";
            case ASSEMBLING -> "assembling";
            default -> throw new IllegalArgumentException("child phase is invalid");
        };
        return logicalKey + "/" + phasePath
                + "/" + attempt;
    }

    private static String part(String value, String field) {
        if (value == null || !PART.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
