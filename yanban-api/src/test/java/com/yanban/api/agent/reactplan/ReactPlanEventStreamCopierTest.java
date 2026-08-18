package com.yanban.api.agent.reactplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReactPlanEventStreamCopierTest {
    @Test
    void flushesReplayBytesBeforeTheUpstreamStreamCloses() throws Exception {
        byte[] event = "id: 1\ndata: {\"sequence\":1}\n\n"
                .getBytes(StandardCharsets.UTF_8);
        TrackingOutputStream output = new TrackingOutputStream();

        ReactPlanEventStreamCopier.copyAndFlush(
                new ByteArrayInputStream(event), output);

        assertEquals(new String(event, StandardCharsets.UTF_8),
                output.toString(StandardCharsets.UTF_8));
        assertTrue(output.flushes > 0);
    }

    @Test
    void treatsAnInterruptedUpstreamReadAsStreamCancellation() throws Exception {
        InputStream interrupted = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException(new InterruptedException("stream cancelled"));
            }
        };

        try {
            ReactPlanEventStreamCopier.copyAndFlush(interrupted, new ByteArrayOutputStream());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void keepsOrdinaryStreamFailuresVisible() {
        InputStream failed = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("upstream failed");
            }
        };

        assertThrows(IOException.class,
                () -> ReactPlanEventStreamCopier.copyAndFlush(
                        failed, new ByteArrayOutputStream()));
    }

    private static final class TrackingOutputStream extends ByteArrayOutputStream {
        private int flushes;

        @Override
        public void flush() {
            flushes += 1;
        }
    }
}
