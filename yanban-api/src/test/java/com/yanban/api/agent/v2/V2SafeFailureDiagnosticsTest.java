package com.yanban.api.agent.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class V2SafeFailureDiagnosticsTest {
    @Test
    void exposesOnlyTypesAndCodeOrigin() {
        IllegalStateException cause = new IllegalStateException(
                "secret-key and user/file/content");
        cause.setStackTrace(new StackTraceElement[]{
                new StackTraceElement(
                        "com.yanban.api.agent.v2.loop.TestBoundary",
                        "execute", "C:/private/UserFile.java", 42)});
        RuntimeException wrapper = new RuntimeException(
                "another secret", cause);

        assertEquals("RuntimeException",
                V2SafeFailureDiagnostics.exceptionType(wrapper));
        assertEquals("IllegalStateException",
                V2SafeFailureDiagnostics.causeType(wrapper));
        String origin = V2SafeFailureDiagnostics.origin(wrapper);
        assertEquals("TestBoundary#execute:42", origin);
        assertTrue(origin.contains("execute"));
        assertFalse(origin.contains("secret"));
        assertFalse(origin.contains("C:/private"));
    }
}
