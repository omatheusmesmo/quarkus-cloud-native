package dev.omatheusmesmo.demo.dto;

import java.time.Instant;

public record BenchmarkResult(
    String mode,
    int iterations,
    long totalDurationMs,
    double avgDurationMs,
        double minDurationMs,
        double maxDurationMs,
    double throughputPerSec,
    Instant timestamp
) {
    public static BenchmarkResult compute(int iterations, long[] durations, long totalDurationNanos) {
        String mode = getMode();
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        long sum = 0;
        for (long d : durations) {
            sum += d;
            if (d < min) min = d;
            if (d > max) max = d;
        }
        double avgMs = (sum / (double) iterations) / 1_000_000.0;
        double throughput = iterations / (totalDurationNanos / 1_000_000_000.0);

        return new BenchmarkResult(
                mode,
                iterations,
                totalDurationNanos / 1_000_000,
                avgMs,
                min / 1_000_000.0,
                max / 1_000_000.0,
                throughput,
                Instant.now()
        );
    }

    private static String getMode() {
        String imageCode = System.getProperty("org.graalvm.nativeimage.imagecode", "jvm");
        return "runtime".equals(imageCode) ? "native" : "jvm";
    }
}
