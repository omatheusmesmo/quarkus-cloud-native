///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.6

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "load-test", mixinStandardHelpOptions = true,
    description = "Run HTTP load test against the Event Processor API")
class LoadTest implements Callable<Integer> {

    @Option(names = "--url", defaultValue = "http://localhost:8080", description = "Base URL")
    String baseUrl;

    @Option(names = "--concurrency", defaultValue = "10", description = "Concurrent connections")
    int concurrency;

    @Option(names = "--duration", defaultValue = "30s", description = "Test duration (e.g. 10s, 1m)")
    String durationStr;

    @Option(names = "--path", defaultValue = "/api/system/info", description = "Endpoint to test")
    String path;

    @Option(names = "--output", description = "Output JSON file")
    String outputFile;

    @Override
    public Integer call() throws Exception {
        long durationMs = parseDuration(durationStr);
        String url = baseUrl + path;

        System.out.printf("Load test: %d concurrent connections for %s against %s%n",
            concurrency, durationStr, url);

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger errorCount = new AtomicInteger();
        List<Long> latencies = java.util.Collections.synchronizedList(new ArrayList<>());

        Instant deadline = Instant.now().plusMillis(durationMs);
        Instant startAll = Instant.now();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            futures.add(CompletableFuture.runAsync(() -> {
                while (Instant.now().isBefore(deadline)) {
                    long start = System.nanoTime();
                    try {
                        HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(10))
                            .GET().build();
                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        long latency = (System.nanoTime() - start) / 1_000_000;
                        latencies.add(latency);
                        if (response.statusCode() == 200) successCount.incrementAndGet();
                        else errorCount.incrementAndGet();
                    } catch (Exception e) {
                        long latency = (System.nanoTime() - start) / 1_000_000;
                        latencies.add(latency);
                        errorCount.incrementAndGet();
                    }
                }
            }, executor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        executor.shutdown();

        long totalElapsed = (System.nanoTime() - startAll.toEpochMilli() * 1_000_000) / 1_000_000;

        latencies.sort(Long::compare);
        double avgLatency = latencies.stream().mapToLong(l -> l).average().orElse(0);
        long p50 = percentile(latencies, 50);
        long p90 = percentile(latencies, 90);
        long p99 = percentile(latencies, 99);
        double rps = successCount.get() / (durationMs / 1000.0);

        System.out.println("\n=== Load Test Results ===");
        System.out.printf("%-25s %s%n", "Total requests:", successCount.get() + errorCount.get());
        System.out.printf("%-25s %s%n", "Successful:", successCount.get());
        System.out.printf("%-25s %s%n", "Errors:", errorCount.get());
        System.out.printf("%-25s %.1f req/s%n", "Throughput:", rps);
        System.out.printf("%-25s %.1f ms%n", "Avg latency:", avgLatency);
        System.out.printf("%-25s %d ms%n", "P50 latency:", p50);
        System.out.printf("%-25s %d ms%n", "P90 latency:", p90);
        System.out.printf("%-25s %d ms%n", "P99 latency:", p99);

        if (outputFile != null) {
            String json = String.format(
                "{\"totalRequests\":%d,\"success\":%d,\"errors\":%d,\"rps\":%.1f,\"avgLatencyMs\":%.1f,\"p50Ms\":%d,\"p90Ms\":%d,\"p99Ms\":%d,\"durationMs\":%d}",
                successCount.get() + errorCount.get(), successCount.get(), errorCount.get(),
                rps, avgLatency, p50, p90, p99, durationMs);
            java.nio.file.Files.writeString(java.nio.file.Path.of(outputFile), json);
            System.out.println("Results saved to: " + outputFile);
        }

        return 0;
    }

    private long percentile(List<Long> sorted, int pct) {
        int idx = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private long parseDuration(String s) {
        if (s.endsWith("s")) return Long.parseLong(s.replace("s", "")) * 1000;
        if (s.endsWith("m")) return Long.parseLong(s.replace("m", "")) * 60 * 1000;
        return Long.parseLong(s) * 1000;
    }

    public static void main(String... args) {
        System.exit(new CommandLine(new LoadTest()).execute(args));
    }
}
