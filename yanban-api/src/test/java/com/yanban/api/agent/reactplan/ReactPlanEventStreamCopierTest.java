package com.yanban.api.agent.reactplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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

    private static final class TrackingOutputStream extends ByteArrayOutputStream {
        private int flushes;

        @Override
        public void flush() {
            flushes += 1;
        }
    }
}
