package com.yanban.api.agent.v2;

/**
 * Produces failure diagnostics that are useful for operations without
 * exposing exception messages, request payloads, file contents, or secrets.
 */
public final class V2SafeFailureDiagnostics {
    private static final int MAX_CAUSE_DEPTH = 8;

    private V2SafeFailureDiagnostics() {
    }

    public static String exceptionType(Throwable failure) {
        return safeType(failure);
    }

    public static String causeType(Throwable failure) {
        return safeType(rootCause(failure));
    }

    public static String origin(Throwable failure) {
        Throwable current = rootCause(failure);
        while (current != null) {
            for (StackTraceElement element : current.getStackTrace()) {
                String className = element.getClassName();
                if (!className.startsWith("com.yanban.")
                        && !className.startsWith("io.paperagent.v2.")) {
                    continue;
                }
                int separator = className.lastIndexOf('.');
                String simpleClass = separator < 0
                        ? className : className.substring(separator + 1);
                return simpleClass + "#" + element.getMethodName()
                        + ":" + element.getLineNumber();
            }
            current = current.getCause();
        }
        return "application-boundary";
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0;
                current != null && current.getCause() != null
                        && current.getCause() != current
                        && depth < MAX_CAUSE_DEPTH;
                depth++) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeType(Throwable failure) {
        if (failure == null) {
            return "UnknownFailure";
        }
        String simple = failure.getClass().getSimpleName();
        return simple == null || simple.isBlank()
                ? "UnnamedFailure" : simple;
    }
}
