package com.jsrc.app.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StopWatchTest {

    @Test
    @DisplayName("Should measure elapsed time in milliseconds")
    void shouldMeasureElapsedMs() throws InterruptedException {
        StopWatch sw = StopWatch.start();
        Thread.sleep(50);
        long elapsed = sw.elapsedMs();
        assertTrue(elapsed >= 30, "Should be at least 30ms, got " + elapsed);
        assertTrue(elapsed < 500, "Should be less than 500ms, got " + elapsed);
    }

    @Test
    @DisplayName("Should measure elapsed time in nanoseconds")
    void shouldMeasureElapsedNanos() throws InterruptedException {
        StopWatch sw = StopWatch.start();
        Thread.sleep(10);
        long nanos = sw.elapsedNanos();
        assertTrue(nanos > 0, "Should have positive nanos");
    }

    @Test
    @DisplayName("Should format elapsed as human-readable string")
    void shouldFormatElapsed() throws InterruptedException {
        StopWatch sw = StopWatch.start();
        Thread.sleep(10);
        String formatted = sw.toString();
        assertNotNull(formatted);
        assertTrue(formatted.contains("ms"), "Should contain 'ms': " + formatted);
    }

    @Test
    @DisplayName("Should allow multiple reads without resetting")
    void shouldAllowMultipleReads() throws InterruptedException {
        StopWatch sw = StopWatch.start();
        Thread.sleep(10);
        long first = sw.elapsedMs();
        Thread.sleep(10);
        long second = sw.elapsedMs();
        assertTrue(second >= first, "Second read should be >= first");
    }
}
