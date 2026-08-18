package com.yanban.api.agent.reactplan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class ReactPlanTraceIds {
    private ReactPlanTraceIds() { }

    public static String forTask(String taskId) {
        try {
            return "trace." + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("reactplan-trace-v1\0" + taskId).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
