package io.paperagent.v2.sandbox;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Captured facts from an executed command. A non-zero exit code is still an
 * executed result, not a Sandbox failure.
 */
public final class ExecutedCommand implements SandboxResult {
    private final int exitCode;
    private final Instant startedAt;
    private final Instant endedAt;
    private final byte[] stdout;
    private final byte[] stderr;
    private final boolean stdoutTruncated;
    private final boolean stderrTruncated;
    private final Map<String, String> metadata;

    public ExecutedCommand(
            int exitCode,
            Instant startedAt,
            Instant endedAt,
            byte[] stdout,
            byte[] stderr,
            boolean stdoutTruncated,
            boolean stderrTruncated,
            Map<String, String> metadata) {
        this.exitCode = exitCode;
        this.startedAt = SandboxValues.required(startedAt, "executedCommand.startedAt");
        this.endedAt = SandboxValues.required(endedAt, "executedCommand.endedAt");
        SandboxProtocolValidator.validateTimeRange(this.startedAt, this.endedAt);
        this.stdout = SandboxValues.required(stdout, "executedCommand.stdout").clone();
        this.stderr = SandboxValues.required(stderr, "executedCommand.stderr").clone();
        this.stdoutTruncated = stdoutTruncated;
        this.stderrTruncated = stderrTruncated;
        this.metadata = SandboxValues.boundedMetadata(
                metadata,
                "executedCommand.metadata");
    }

    public int exitCode() {
        return exitCode;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant endedAt() {
        return endedAt;
    }

    public byte[] stdout() {
        return stdout.clone();
    }

    public byte[] stderr() {
        return stderr.clone();
    }

    public boolean stdoutTruncated() {
        return stdoutTruncated;
    }

    public boolean stderrTruncated() {
        return stderrTruncated;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    int stdoutLength() {
        return stdout.length;
    }

    int stderrLength() {
        return stderr.length;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExecutedCommand that)) {
            return false;
        }
        return exitCode == that.exitCode
                && stdoutTruncated == that.stdoutTruncated
                && stderrTruncated == that.stderrTruncated
                && startedAt.equals(that.startedAt)
                && endedAt.equals(that.endedAt)
                && Arrays.equals(stdout, that.stdout)
                && Arrays.equals(stderr, that.stderr)
                && metadata.equals(that.metadata);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                exitCode,
                startedAt,
                endedAt,
                stdoutTruncated,
                stderrTruncated,
                metadata);
        result = 31 * result + Arrays.hashCode(stdout);
        result = 31 * result + Arrays.hashCode(stderr);
        return result;
    }

    @Override
    public String toString() {
        return "ExecutedCommand[exitCode=" + exitCode
                + ", startedAt=" + startedAt
                + ", endedAt=" + endedAt
                + ", stdoutBytes=" + stdout.length
                + ", stderrBytes=" + stderr.length
                + ", stdoutTruncated=" + stdoutTruncated
                + ", stderrTruncated=" + stderrTruncated
                + ", metadata=" + metadata + "]";
    }
}
