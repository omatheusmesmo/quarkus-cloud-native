package dev.omatheusmesmo.demo.dto;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Instant;

public record SystemInfo(
    String mode,
    String javaVersion,
    String javaVendor,
    String osName,
    String osArch,
    long uptimeMs,
    long maxMemoryMb,
    long usedMemoryMb,
    int availableProcessors,
    Instant timestamp
) {
    public static SystemInfo current() {
        Runtime runtime = Runtime.getRuntime();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        long usedBytes = memoryBean.getHeapMemoryUsage().getUsed();
        long maxBytes = memoryBean.getHeapMemoryUsage().getMax();
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();

        return new SystemInfo(
            getMode(),
            System.getProperty("java.version"),
            System.getProperty("java.vendor"),
            System.getProperty("os.name"),
            System.getProperty("os.arch"),
            uptime,
            maxBytes / (1024 * 1024),
            usedBytes / (1024 * 1024),
            runtime.availableProcessors(),
            Instant.now()
        );
    }

    private static String getMode() {
        String imageCode = System.getProperty("org.graalvm.nativeimage.imagecode", "jvm");
        return "runtime".equals(imageCode) ? "native" : "jvm";
    }
}
