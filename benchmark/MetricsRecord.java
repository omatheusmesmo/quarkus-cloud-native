///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.6

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "record", mixinStandardHelpOptions = true,
        description = "Collect all metrics and save a versioned JSON snapshot to metrics/")
class MetricsRecord implements Callable<Integer> {

    @Option(names = "--url", defaultValue = "http://localhost:8080", description = "Base URL of running app")
    String baseUrl;

    @Option(names = "--native-bin", description = "Path to native executable")
    String nativePath;

    @Option(names = "--jar", description = "Path to runner JAR")
    String jarPath;

    @Option(names = "--benchmark-iterations", defaultValue = "1000", description = "Iterations for self-benchmark")
    int benchmarkIterations;

    @Option(names = "--startup-warmup", defaultValue = "2", description = "Warmup runs to discard")
    int startupWarmup;

    @Option(names = "--startup-runs", defaultValue = "5", description = "Measured startup runs")
    int startupRuns;

    @Option(names = "--rss-wait-seconds", defaultValue = "3", description = "Seconds to wait before RSS measurement")
    int rssWaitSeconds;

    @Option(names = "--output-dir", defaultValue = "metrics", description = "Directory to save metrics JSON")
    String outputDir;

    @Override
    public Integer call() throws Exception {
        Path dir = Path.of(outputDir);
        Files.createDirectories(dir);

        System.out.println("=== Collecting Metrics Snapshot ===\n");

        var snapshot = new StringBuilder();
        String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String timestamp = Instant.now().toString();

        snapshot.append("{\n");
        snapshot.append("  \"date\": \"").append(date).append("\",\n");
        snapshot.append("  \"timestamp\": \"").append(timestamp).append("\",\n");

        collectLiveMetrics(snapshot);
        collectStartupMetrics(snapshot);
        collectBinarySizes(snapshot);
        collectBuildInfo(snapshot);

        snapshot.append("}");

        String filename = date + ".json";
        Path outputFile = dir.resolve(filename);
        Files.writeString(outputFile, snapshot.toString());
        System.out.println("\n=== Metrics saved to " + outputFile + " ===");

        updateHistory(dir, outputFile);

        return 0;
    }

    private void collectLiveMetrics(StringBuilder snapshot) throws Exception {
        SystemInfoResult sysInfo = fetchSystemInfo();
        if (sysInfo != null) {
            System.out.println("[OK] System info collected");
            snapshot.append("  \"mode\": \"").append(sysInfo.mode).append("\",\n");
            snapshot.append("  \"javaVersion\": \"").append(sysInfo.javaVersion).append("\",\n");
            snapshot.append("  \"javaVendor\": \"").append(sysInfo.javaVendor).append("\",\n");
            snapshot.append("  \"osName\": \"").append(sysInfo.osName).append("\",\n");
            snapshot.append("  \"osArch\": \"").append(sysInfo.osArch).append("\",\n");
            snapshot.append("  \"usedMemoryMb\": ").append(sysInfo.usedMemoryMb).append(",\n");
            snapshot.append("  \"maxMemoryMb\": ").append(sysInfo.maxMemoryMb).append(",\n");
        } else {
            System.out.println("[SKIP] System info - app not reachable");
            snapshot.append("  \"mode\": null,\n");
        }

        BenchmarkResult bench = fetchBenchmark();
        if (bench != null) {
            System.out.println("[OK] Benchmark collected (" + benchmarkIterations + " iterations)");
            snapshot.append("  \"benchmark\": {\n");
            snapshot.append("    \"iterations\": ").append(bench.iterations).append(",\n");
            snapshot.append("    \"totalDurationMs\": ").append(bench.totalDurationMs).append(",\n");
            snapshot.append("    \"avgDurationMs\": ").append(bench.avgDurationMs).append(",\n");
            snapshot.append("    \"minDurationMs\": ").append(bench.minDurationMs).append(",\n");
            snapshot.append("    \"maxDurationMs\": ").append(bench.maxDurationMs).append(",\n");
            snapshot.append("    \"throughputPerSec\": ").append(bench.throughputPerSec).append("\n");
            snapshot.append("  },\n");
        } else {
            System.out.println("[SKIP] Benchmark - app not reachable");
            snapshot.append("  \"benchmark\": null,\n");
        }
    }

    private void collectStartupMetrics(StringBuilder snapshot) throws Exception {
        if (nativePath == null) nativePath = findNative();
        if (jarPath == null) jarPath = findJar();

        StartupResult nativeStartup = null;
        StartupResult jvmStartup = null;

        if (nativePath != null && Files.exists(Path.of(nativePath))) {
            System.out.println("[MEASURE] Native startup (" + startupRuns + " runs, " + startupWarmup + " warmup)...");
            nativeStartup = measureStartup(nativePath);
            System.out.printf("[OK] Native startup: avg %.0f ms, min %.0f ms%n", nativeStartup.avgMs, nativeStartup.minMs);
            System.out.printf("[OK] Native time-to-first-request: %d ms%n", nativeStartup.ttfrMs);
            System.out.printf("[OK] Native RSS memory: %d MB%n", nativeStartup.rssMb);
        }

        if (jarPath != null && Files.exists(Path.of(jarPath))) {
            System.out.println("[MEASURE] JVM startup (" + startupRuns + " runs, " + startupWarmup + " warmup)...");
            jvmStartup = measureStartup("java", "-jar", jarPath);
            System.out.printf("[OK] JVM startup: avg %.0f ms, min %.0f ms%n", jvmStartup.avgMs, jvmStartup.minMs);
            System.out.printf("[OK] JVM time-to-first-request: %d ms%n", jvmStartup.ttfrMs);
            System.out.printf("[OK] JVM RSS memory: %d MB%n", jvmStartup.rssMb);
        }

        snapshot.append("  \"startup\": {\n");
        if (nativeStartup != null) {
            snapshot.append("    \"native\": {\n");
            snapshot.append("      \"avgMs\": ").append(String.format("%.0f", nativeStartup.avgMs)).append(",\n");
            snapshot.append("      \"minMs\": ").append(String.format("%.0f", nativeStartup.minMs)).append(",\n");
            snapshot.append("      \"maxMs\": ").append(String.format("%.0f", nativeStartup.maxMs)).append(",\n");
            snapshot.append("      \"ttfrMs\": ").append(nativeStartup.ttfrMs).append(",\n");
            snapshot.append("      \"rssMb\": ").append(nativeStartup.rssMb).append("\n");
            snapshot.append("    }");
        }
        if (jvmStartup != null) {
            if (nativeStartup != null) snapshot.append(",\n");
            else snapshot.append("\n");
            snapshot.append("    \"jvm\": {\n");
            snapshot.append("      \"avgMs\": ").append(String.format("%.0f", jvmStartup.avgMs)).append(",\n");
            snapshot.append("      \"minMs\": ").append(String.format("%.0f", jvmStartup.minMs)).append(",\n");
            snapshot.append("      \"maxMs\": ").append(String.format("%.0f", jvmStartup.maxMs)).append(",\n");
            snapshot.append("      \"ttfrMs\": ").append(jvmStartup.ttfrMs).append(",\n");
            snapshot.append("      \"rssMb\": ").append(jvmStartup.rssMb).append("\n");
            snapshot.append("    }");
        }
        if (nativeStartup == null && jvmStartup == null) {
            snapshot.append("    \"native\": null,\n");
            snapshot.append("    \"jvm\": null");
        }
        snapshot.append("\n  },\n");

        if (nativeStartup != null && jvmStartup != null) {
            double speedup = jvmStartup.avgMs / nativeStartup.avgMs;
            double rssRatio = (double) jvmStartup.rssMb / nativeStartup.rssMb;
            snapshot.append("  \"startupSpeedup\": ").append(String.format("%.1f", speedup)).append(",\n");
            snapshot.append("  \"rssRatioJvmOverNative\": ").append(String.format("%.1f", rssRatio)).append(",\n");
            System.out.printf("[SUMMARY] Native is %.1fx faster at startup, uses %.1fx less RSS%n", speedup, rssRatio);
        }
    }

    private void collectBinarySizes(StringBuilder snapshot) throws Exception {
        if (nativePath == null) nativePath = findNative();
        if (nativePath != null && Files.exists(Path.of(nativePath))) {
            long sizeBytes = Files.size(Path.of(nativePath));
            double sizeMb = sizeBytes / (1024.0 * 1024.0);
            System.out.printf("[OK] Native binary size: %.1f MB%n", sizeMb);
            snapshot.append("  \"nativeBinarySizeBytes\": ").append(sizeBytes).append(",\n");
            snapshot.append("  \"nativeBinarySizeMb\": ").append(String.format("%.1f", sizeMb)).append(",\n");
        } else {
            System.out.println("[SKIP] Native binary size - binary not found");
            snapshot.append("  \"nativeBinarySizeBytes\": null,\n");
        }

        if (jarPath == null) jarPath = findJar();
        if (jarPath != null && Files.exists(Path.of(jarPath))) {
            long jarSize = Files.size(Path.of(jarPath));
            long libSize = 0;
            Path libDir = Path.of("target/quarkus-app/lib");
            if (Files.isDirectory(libDir)) {
                libSize = Files.walk(libDir).filter(Files::isRegularFile).mapToLong(p -> {
                    try { return Files.size(p); } catch (Exception e) { return 0; }
                }).sum();
            }
            long jvmRuntimeSize = measureJvmRuntimeSize();
            long appTotal = jarSize + libSize;
            long fullTotal = appTotal + jvmRuntimeSize;
            double appMb = appTotal / (1024.0 * 1024.0);
            double fullMb = fullTotal / (1024.0 * 1024.0);
            double runtimeMb = jvmRuntimeSize / (1024.0 * 1024.0);
            System.out.printf("[OK] JVM app size: %.1f MB (runner + lib)%n", appMb);
            System.out.printf("[OK] JVM runtime size: %.1f MB (JDK libs)%n", runtimeMb);
            System.out.printf("[OK] JVM total size: %.1f MB (app + runtime)%n", fullMb);
            snapshot.append("  \"jvmAppSizeBytes\": ").append(appTotal).append(",\n");
            snapshot.append("  \"jvmAppSizeMb\": ").append(String.format("%.1f", appMb)).append(",\n");
            snapshot.append("  \"jvmRuntimeSizeBytes\": ").append(jvmRuntimeSize).append(",\n");
            snapshot.append("  \"jvmRuntimeSizeMb\": ").append(String.format("%.1f", runtimeMb)).append(",\n");
            snapshot.append("  \"jvmTotalSizeBytes\": ").append(fullTotal).append(",\n");
            snapshot.append("  \"jvmTotalSizeMb\": ").append(String.format("%.1f", fullMb)).append(",\n");
        } else {
            System.out.println("[SKIP] JVM size - JAR not found");
            snapshot.append("  \"jvmAppSizeBytes\": null,\n");
        }
    }

    private void collectBuildInfo(StringBuilder snapshot) throws Exception {
        String quarkusVersion = extractQuarkusVersion();
        if (quarkusVersion != null) {
            System.out.println("[OK] Quarkus version: " + quarkusVersion);
            snapshot.append("  \"quarkusVersion\": \"").append(quarkusVersion).append("\",\n");
        } else {
            snapshot.append("  \"quarkusVersion\": null,\n");
        }

        String mandrelVersion = findMandrelVersion();
        if (mandrelVersion != null) {
            System.out.println("[OK] Mandrel version: " + mandrelVersion);
            snapshot.append("  \"mandrelVersion\": \"").append(mandrelVersion).append("\",\n");
        } else {
            snapshot.append("  \"mandrelVersion\": null,\n");
        }

        String buildJdk = System.getProperty("java.version");
        snapshot.append("  \"buildJdkVersion\": \"").append(buildJdk).append("\"\n");
    }

    private StartupResult measureStartup(String... command) throws Exception {
        List<Double> startupTimes = new ArrayList<>();
        long ttfrMs = 0;
        long rssMb = 0;
        int port = 9090;

        for (int i = 0; i < startupWarmup + startupRuns; i++) {
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
            pb.environment().put("QUARKUS_HTTP_PORT", String.valueOf(port));
            long start = System.nanoTime();
            Process process = pb.start();

            Thread stdoutDrainer = new Thread(() -> {
                try {
                    var r = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    while (r.readLine() != null) {}
                } catch (Exception ignored) {}
            });
            stdoutDrainer.setDaemon(true);
            stdoutDrainer.start();

            long startupMs = waitForHttpReady(port, 30);
            if (i >= startupWarmup) startupTimes.add((double) startupMs);

            if (i == startupWarmup) {
                ttfrMs = startupMs;
                Thread.sleep(rssWaitSeconds * 1000L);
                rssMb = getRssMb(process.pid());
            }

            process.destroyForcibly();
            process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            Thread.sleep(500);
        }

        double avg = startupTimes.stream().mapToDouble(d -> d).average().orElse(0);
        double min = startupTimes.stream().mapToDouble(d -> d).min().orElse(0);
        double max = startupTimes.stream().mapToDouble(d -> d).max().orElse(0);

        return new StartupResult(avg, min, max, ttfrMs, rssMb);
    }

    private long waitForHttpReady(int port, int timeoutSec) {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build();
        var req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/q/health"))
                .timeout(Duration.ofMillis(500)).GET().build();
        long startMs = System.currentTimeMillis();
        long deadline = startMs + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                var resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() == 200) {
                    return System.currentTimeMillis() - startMs;
                }
            } catch (Exception ignored) {}
            try { Thread.sleep(20); } catch (InterruptedException e) { break; }
        }
        return System.currentTimeMillis() - startMs;
    }

    private long getRssMb(long pid) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid));
        pb.redirectErrorStream(true);
        Process ps = pb.start();
        String output = new String(ps.getInputStream().readAllBytes()).trim();
        ps.waitFor();
        if (output.isEmpty()) return 0;
        long rssKb = Long.parseLong(output.split("\\s+")[0]);
        return rssKb / 1024;
    }

    private long measureJvmRuntimeSize() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null) return 0;
        try {
            Path libDir = Path.of(javaHome, "lib");
            if (!Files.isDirectory(libDir)) return 0;
            return Files.walk(libDir).filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (Exception e) { return 0; }
            }).sum();
        } catch (Exception e) { return 0; }
    }

    private String findMandrelVersion() {
        String graalvmHome = System.getenv("GRAALVM_HOME");
        if (graalvmHome != null) {
            String v = readMandrelVersion(graalvmHome);
            if (v != null) return v;
        }
        try (var stream = Files.list(Path.of("/home/omatheusmesmo/.sdkman/candidates/java/"))) {
            var mandrel = stream.filter(p -> p.getFileName().toString().contains("mandrel"))
                    .findFirst();
            if (mandrel.isPresent()) {
                String v = readMandrelVersion(mandrel.get().toString());
                if (v != null) return v;
                v = getMandrelVersionFromBinary(mandrel.get().toString());
                if (v != null) return v;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void updateHistory(Path dir, Path newEntry) throws Exception {
        Path historyFile = dir.resolve("history.json");
        List<String> entries = new ArrayList<>();
        if (Files.exists(historyFile)) {
            String content = Files.readString(historyFile).trim();
            if (content.startsWith("[") && content.endsWith("]")) {
                content = content.substring(1, content.length() - 1).trim();
                if (!content.isEmpty()) {
                    for (String entry : content.split("\\},\\s*\\{")) {
                        if (!entry.startsWith("{")) entry = "{" + entry;
                        if (!entry.endsWith("}")) entry = entry + "}";
                        entries.add(entry);
                    }
                }
            }
        }

        String newContent = Files.readString(newEntry).trim();
        entries.removeIf(e -> {
            String d = extractJsonValue(e, "date");
            return d.equals(extractJsonValue(newContent, "date"));
        });
        entries.add(newContent);

        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < entries.size(); i++) {
            sb.append("  ").append(entries.get(i)).append(i < entries.size() - 1 ? "," : "").append("\n");
        }
        sb.append("]");
        Files.writeString(historyFile, sb.toString());
        System.out.println("History updated: " + historyFile + " (" + entries.size() + " entries)");
    }

    private SystemInfoResult fetchSystemInfo() {
        try {
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            var req = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/api/system/info"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                String body = resp.body();
                return new SystemInfoResult(
                        extractJsonValue(body, "mode"),
                        extractJsonValue(body, "javaVersion"),
                        extractJsonValue(body, "javaVendor"),
                        extractJsonValue(body, "osName"),
                        extractJsonValue(body, "osArch"),
                        Long.parseLong(extractJsonValue(body, "usedMemoryMb").replaceAll("\\..*", "")),
                        Long.parseLong(extractJsonValue(body, "maxMemoryMb").replaceAll("\\..*", ""))
                );
            }
        } catch (Exception ignored) {}
        return null;
    }

    private BenchmarkResult fetchBenchmark() {
        try {
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/system/benchmark?iterations=" + benchmarkIterations))
                    .timeout(Duration.ofSeconds(60)).GET().build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                String body = resp.body();
                return new BenchmarkResult(
                        (int) Double.parseDouble(extractJsonValue(body, "iterations")),
                        Long.parseLong(extractJsonValue(body, "totalDurationMs").replaceAll("\\..*", "")),
                        Double.parseDouble(extractJsonValue(body, "avgDurationMs")),
                        Double.parseDouble(extractJsonValue(body, "minDurationMs")),
                        Double.parseDouble(extractJsonValue(body, "maxDurationMs")),
                        Double.parseDouble(extractJsonValue(body, "throughputPerSec"))
                );
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx < 0) return "";
        int start = idx + pattern.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start < json.length() && json.charAt(start) == '"') {
            int end = json.indexOf("\"", start + 1);
            return json.substring(start + 1, end);
        }
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-' || json.charAt(end) == 'e' || json.charAt(end) == 'E' || json.charAt(end) == '+')) end++;
        return json.substring(start, end);
    }

    private String extractQuarkusVersion() {
        try {
            Path pom = Path.of("pom.xml");
            if (!Files.exists(pom)) return null;
            String content = Files.readString(pom);
            int idx = content.indexOf("<quarkus.platform.version>");
            if (idx < 0) return null;
            int start = content.indexOf(">", idx) + 1;
            int end = content.indexOf("</quarkus.platform.version>", start);
            return content.substring(start, end).trim();
        } catch (Exception e) { return null; }
    }

    private String readMandrelVersion(String graalvmHome) {
        try {
            Path releaseFile = Path.of(graalvmHome, "release");
            if (!Files.exists(releaseFile)) return null;
            String content = Files.readString(releaseFile);
            for (String line : content.split("\n")) {
                if (line.contains("GRAALVM_VERSION") || line.contains("MANDREL_VERSION")) {
                    int eq = line.indexOf("=");
                    String val = line.substring(eq + 1).replace("\"", "").trim();
                    return val;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String getMandrelVersionFromBinary(String graalvmHome) {
        try {
            Path ni = Path.of(graalvmHome, "bin", "native-image");
            if (!Files.exists(ni)) return null;
            var pb = new ProcessBuilder(ni.toString(), "--version").redirectErrorStream(true);
            var proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            for (String line : output.split("\n")) {
                if (line.contains("Mandrel")) {
                    var matcher = java.util.regex.Pattern.compile("Mandrel-[\\d.]+(?:-\\w+)?").matcher(line);
                    if (matcher.find()) return matcher.group();
                }
                if (line.contains("GraalVM")) {
                    var matcher = java.util.regex.Pattern.compile("GraalVM[\\s]+SVM[\\s]+\\S+").matcher(line);
                    if (matcher.find()) return matcher.group().trim();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String findNative() {
        Path dir = Path.of("target");
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                var found = stream.filter(p -> p.getFileName().toString().endsWith("-runner")
                        && !p.getFileName().toString().endsWith(".jar")
                        && !p.getFileName().toString().endsWith(".zip")
                        && Files.isRegularFile(p)).findFirst();
                if (found.isPresent()) return found.get().toString();
            } catch (Exception ignored) {}
        }
        return null;
    }

    private String findJar() {
        Path jarPath = Path.of("target/quarkus-app/quarkus-run.jar");
        if (Files.exists(jarPath)) return jarPath.toString();
        Path dir = Path.of("target");
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                var found = stream.filter(p -> p.getFileName().toString().endsWith("-runner.jar")
                        && !p.getFileName().toString().contains("-sources")
                        && !p.getFileName().toString().contains("-javadoc")).findFirst();
                if (found.isPresent()) return found.get().toString();
            } catch (Exception ignored) {}
        }
        return null;
    }

    record SystemInfoResult(String mode, String javaVersion, String javaVendor, String osName, String osArch, long usedMemoryMb, long maxMemoryMb) {}
    record BenchmarkResult(int iterations, long totalDurationMs, double avgDurationMs, double minDurationMs, double maxDurationMs, double throughputPerSec) {}
    record StartupResult(double avgMs, double minMs, double maxMs, long ttfrMs, long rssMb) {}

    public static void main(String... args) {
        System.exit(new CommandLine(new MetricsRecord()).execute(args));
    }
}
