package com.jsrc.app.util;

/**
 * Simple stopwatch for measuring elapsed time.
 * Thread-safe for reads (immutable start time).
 */
public final class StopWatch {

    private final long startNanos;

    private StopWatch(long startNanos) {
        this.startNanos = startNanos;
    }

    /**
     * Creates and starts a new stopwatch.
     */
    public static StopWatch start() {
        return new StopWatch(System.nanoTime());
    }

    /**
     * Returns elapsed time in nanoseconds since start.
     */
    public long elapsedNanos() {
        return System.nanoTime() - startNanos;
    }

    /**
     * Returns elapsed time in milliseconds since start.
     */
    public long elapsedMs() {
        return elapsedNanos() / 1_000_000;
    }

    @Override
    public String toString() {
        long ms = elapsedMs();
        if (ms < 1000) return ms + "ms";
        return String.format("%.2fs", ms / 1000.0);
    }
}
