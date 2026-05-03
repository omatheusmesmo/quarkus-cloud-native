///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.6

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "compare", mixinStandardHelpOptions = true,
    description = "Full JVM vs Native comparison: startup, memory, throughput")
class Compare implements Callable<Integer> {

    @Option(names = "--iterations", defaultValue = "1000", description = "Benchmark iterations")
    int iterations;

    @Option(names = "--jvm-url", defaultValue = "http://localhost:8080", description = "JVM mode URL")
    String jvmUrl;

    @Option(names = "--native-url", description = "Native mode URL")
    String nativeUrl;

    @Option(names = "--output", description = "Output JSON file")
    String outputFile;

    @Override
    public Integer call() throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

        System.out.println("=== JVM vs Native Full Comparison ===\n");

        SystemInfo jvmInfo = fetchSystemInfo(client, jvmUrl);
        BenchmarkResult jvmBench = fetchBenchmark(client, jvmUrl, iterations);

        SystemInfo nativeInfo = null;
        BenchmarkResult nativeBench = null;
        if (nativeUrl != null) {
            nativeInfo = fetchSystemInfo(client, nativeUrl);
            nativeBench = fetchBenchmark(client, nativeUrl, iterations);
        }

        System.out.printf("%-25s %-15s %-15s %-15s%n", "Metric", "JVM", "Native", "Ratio");
        System.out.println("=".repeat(70));

        printRow("Mode", jvmInfo != null ? jvmInfo.mode : "N/A",
                 nativeInfo != null ? nativeInfo.mode : "N/A", "");
        if (jvmInfo != null && nativeInfo != null) {
            printRow("Java Version", jvmInfo.javaVersion, nativeInfo.javaVersion, "");
            printRow("Max Heap (MB)", String.valueOf(jvmInfo.maxMemoryMb),
                     String.valueOf(nativeInfo.maxMemoryMb),
                     ratio(nativeInfo.maxMemoryMb, jvmInfo.maxMemoryMb));
            printRow("Used Heap (MB)", String.valueOf(jvmInfo.usedMemoryMb),
                     String.valueOf(nativeInfo.usedMemoryMb),
                     ratio(nativeInfo.usedMemoryMb, jvmInfo.usedMemoryMb));
        }

        System.out.println("-".repeat(70));
        if (jvmBench != null) {
            System.out.printf("%-25s %-15s %-15s %-15s%n", "Throughput (req/s)",
                String.format("%.1f", jvmBench.throughputPerSec),
                nativeBench != null ? String.format("%.1f", nativeBench.throughputPerSec) : "N/A",
                nativeBench != null ? ratio(nativeBench.throughputPerSec, jvmBench.throughputPerSec) : "");
            System.out.printf("%-25s %-15s %-15s %-15s%n", "Avg Duration (ms)",
                String.format("%.2f", jvmBench.avgDurationMs),
                nativeBench != null ? String.format("%.2f", nativeBench.avgDurationMs) : "N/A",
                nativeBench != null ? ratio(nativeBench.avgDurationMs, jvmBench.avgDurationMs) : "");
        }

        if (outputFile != null && jvmBench != null) {
            String json = String.format("{\"jvm\":{\"throughput\":%.1f,\"avgMs\":%.2f},",
                jvmBench.throughputPerSec, jvmBench.avgDurationMs);
            if (nativeBench != null) {
                json += String.format("\"native\":{\"throughput\":%.1f,\"avgMs\":%.2f}}",
                    nativeBench.throughputPerSec, nativeBench.avgDurationMs);
            } else {
                json += "}";
            }
            java.nio.file.Files.writeString(java.nio.file.Path.of(outputFile), json);
            System.out.println("\nResults saved to: " + outputFile);
        }

        return 0;
    }

    private void printRow(String label, String jvm, String nativeVal, String ratio) {
        System.out.printf("%-25s %-15s %-15s %-15s%n", label, jvm, nativeVal, ratio);
    }

    private String ratio(double a, double b) {
        if (b == 0) return "N/A";
        return String.format("%.2fx", a / b);
    }

    private SystemInfo fetchSystemInfo(HttpClient client, String url) {
        try {
            var req = HttpRequest.newBuilder().uri(URI.create(url + "/api/system/info"))
                .timeout(Duration.ofSeconds(10)).GET().build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return parseSystemInfo(resp.body());
        } catch (Exception e) {
            System.err.println("Failed to fetch system info from " + url + ": " + e.getMessage());
        }
        return null;
    }

    private BenchmarkResult fetchBenchmark(HttpClient client, String url, int iterations) {
        try {
            var req = HttpRequest.newBuilder().uri(URI.create(url + "/api/system/benchmark?iterations=" + iterations))
                .timeout(Duration.ofSeconds(60)).GET().build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return parseBenchmark(resp.body());
        } catch (Exception e) {
            System.err.println("Failed to fetch benchmark from " + url + ": " + e.getMessage());
        }
        return null;
    }

    private SystemInfo parseSystemInfo(String json) {
        return new SystemInfo(
            extractString(json, "mode"),
            extractString(json, "javaVersion"),
            extractString(json, "javaVendor"),
            extractString(json, "osName"),
            extractString(json, "osArch"),
            extractLong(json, "uptimeMs"),
            extractLong(json, "maxMemoryMb"),
            extractLong(json, "usedMemoryMb"),
            (int) extractLong(json, "availableProcessors"),
            null
        );
    }

    private BenchmarkResult parseBenchmark(String json) {
        return new BenchmarkResult(
            extractString(json, "mode"),
            (int) extractLong(json, "iterations"),
            extractLong(json, "totalDurationMs"),
            extractDouble(json, "avgDurationMs"),
            extractLong(json, "minDurationMs"),
            extractLong(json, "maxDurationMs"),
            extractDouble(json, "throughputPerSec"),
            null
        );
    }

    private String extractString(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return "";
        int start = idx + pattern.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    private long extractLong(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx < 0) return 0;
        int start = idx + pattern.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-' || json.charAt(end) == '.')) end++;
        return (long) Double.parseDouble(json.substring(start, end));
    }

    private double extractDouble(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx < 0) return 0;
        int start = idx + pattern.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-' || json.charAt(end) == '.')) end++;
        return Double.parseDouble(json.substring(start, end));
    }

    record SystemInfo(String mode, String javaVersion, String javaVendor, String osName, String osArch,
                      long uptimeMs, long maxMemoryMb, long usedMemoryMb, int availableProcessors, String timestamp) {}
    record BenchmarkResult(String mode, int iterations, long totalDurationMs, double avgDurationMs,
                           long minDurationMs, long maxDurationMs, double throughputPerSec, String timestamp) {}

    public static void main(String... args) {
        System.exit(new CommandLine(new Compare()).execute(args));
    }
}
