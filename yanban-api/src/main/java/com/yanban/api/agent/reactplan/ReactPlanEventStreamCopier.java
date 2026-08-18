package com.yanban.api.agent.reactplan;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class ReactPlanEventStreamCopier {
    private ReactPlanEventStreamCopier() { }

    static void copyAndFlush(InputStream source, OutputStream target) throws IOException {
        byte[] buffer = new byte[1024];
        try {
            int read;
            while ((read = source.read(buffer)) != -1) {
                target.write(buffer, 0, read);
                target.flush();
            }
        } catch (IOException failure) {
            if (!causedByInterruption(failure)) {
                throw failure;
            }
            Thread.currentThread().interrupt();
        }
    }

    private static boolean causedByInterruption(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof InterruptedException) return true;
            current = current.getCause();
        }
        return false;
    }
}
