///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.6

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "compare", mixinStandardHelpOptions = true,
    description = "JVM vs Native container-based comparison with k6 load testing")
class Compare implements Callable<Integer> {

    static final String JVM_IMAGE = "quarkus-cloud-native:jvm";
    static final String NATIVE_IMAGE = "quarkus-cloud-native:native";
    static final String CONTAINER_JVM = "qcn-bench-jvm";
    static final String CONTAINER_NATIVE = "qcn-bench-native";

    @Option(names = "--port", defaultValue = "9090",
        description = "Quarkus HTTP port inside container (mapped to host)")
    int port;

    @Option(names = "--vus", defaultValue = "500", description = "k6 virtual users")
    int vus;

    @Option(names = "--duration", defaultValue = "60s",
        description = "k6 test duration (e.g. 60s, 2m)")
    String duration;

    @Option(names = "--warmup-duration", defaultValue = "60s",
        description = "k6 warmup duration before measurement (e.g. 60s, 2m)")
    String warmupDuration;

    @Option(names = "--warmup", description = "Run a warmup k6 pass before measuring (disabled by default: DB-bound workload, JIT warmup has minimal effect)")
    boolean warmup;

    @Option(names = "--output", description = "Save results as JSON")
    String outputFile;

    @Option(names = "--jvm-image", defaultValue = JVM_IMAGE,
        description = "JVM container image name")
    String jvmImage;

    @Option(names = "--native-image", defaultValue = NATIVE_IMAGE,
        description = "Native container image name")
    String nativeImage;

    @Option(names = "--skip-jvm", description = "Skip JVM benchmark")
    boolean skipJvm;

    @Option(names = "--skip-native", description = "Skip Native benchmark")
    boolean skipNative;

    @Option(names = "--app-cpus", description = "Pin app container to cpuset (e.g. '2-5'). Default: no pinning.")
    String appCpus;

    @Option(names = "--db-cpus", description = "Pin DB container to cpuset (e.g. '0-1') via docker update.")
    String dbCpus;

    @Option(names = "--db-container", defaultValue = "quarkus-cloud-native-postgres-1",
        description = "DB container name (used with --db-cpus)")
    String dbContainer;

    @Option(names = "--k6-cpus", description = "Pin k6 to cpuset via taskset (e.g. '6-11')")
    String k6Cpus;

    @Override
    public Integer call() throws Exception {
        println("╔══════════════════════════════════════════════════════════════════╗");
        println("║ Container-Based JVM vs Native | k6 Load Testing                ║");
        println("╚══════════════════════════════════════════════════════════════════╝");
        println();

        if (!checkDocker()) {
            println("ERROR: docker not found or not running.");
            return 1;
        }
        if (!checkK6()) {
            println("ERROR: k6 not found. Install: https://k6.io/docs/get-started/installation/");
            return 1;
        }
        if (!checkDatabase()) {
            println("WARNING: PostgreSQL not reachable on localhost:5432");
            println("  Webhook endpoints will fail. Run 'make db-up' first.");
            println("  Continuing anyway (k6 will test available endpoints)...");
            println();
        }

        if (!skipJvm && !imageExists(jvmImage)) {
            println("ERROR: JVM image not found: " + jvmImage);
            println("  Run 'make jvm-image' first.");
            return 1;
        }
        if (!skipNative && !imageExists(nativeImage)) {
            println("ERROR: Native image not found: " + nativeImage);
            println("  Run 'make native-image' first.");
            return 1;
        }

        if (appCpus != null || dbCpus != null || k6Cpus != null) {
            println("CPU pinning:");
            if (appCpus != null) println("  app: cpuset=" + appCpus + " (--cpuset-cpus on docker run)");
            if (dbCpus != null) println("  db:  cpuset=" + dbCpus + " (" + dbContainer + ")");
            if (k6Cpus != null) println("  k6:  cpuset=" + k6Cpus + " (taskset -c)");
            println();
        }
        if (dbCpus != null && !dbCpus.isEmpty()) {
            pinDbContainer();
        }

        ModeResult jvm = null, native_ = null;

        if (!skipJvm) {
            println("━━━ JVM Container ━━━");
            println("  Image: " + jvmImage);
            jvm = measureContainerMode("JVM", jvmImage, CONTAINER_JVM);
        }

        println();

        if (!skipNative) {
            println("━━━ Native Container ━━━");
            println("  Image: " + nativeImage);
            native_ = measureContainerMode("Native", nativeImage, CONTAINER_NATIVE);
        }

    println();
    Map<String, Object> machine = collectMachineInfo();
    printTable(jvm, native_, machine);

    if (outputFile != null) {
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("date", LocalDateTime.now().toString());
        results.put("methodology", "container-based");
        results.put("machine", machine);
        Map<String, Object> k6Cfg = new LinkedHashMap<>();
        k6Cfg.put("vus", vus);
        k6Cfg.put("duration", duration);
        if (warmup) k6Cfg.put("warmup", warmupDuration);
        if (appCpus != null) k6Cfg.put("appCpus", appCpus);
        if (dbCpus != null) k6Cfg.put("dbCpus", dbCpus);
        if (k6Cpus != null) k6Cfg.put("k6Cpus", k6Cpus);
        results.put("benchmarkConfig", k6Cfg);
        if (jvm != null) results.put("jvm", jvm.toMap());
        if (native_ != null) results.put("native", native_.toMap());
        Path out = Path.of(outputFile);
        Files.createDirectories(out.getParent());
        Files.writeString(out, toJson(results));
        println("\nSaved: " + outputFile);
    }

    return 0;
}

    private ModeResult measureContainerMode(String label, String image, String containerName) throws Exception {
        ModeResult r = new ModeResult(label);
        r.imageSizeMb = getImageSizeMb(image);

        System.out.printf("  Image size: %.1f MB%n", r.imageSizeMb);

        println("  Startup (5 runs, 1st = cold after page cache drop)...");
        println("  container = docker run -> /q/health 200 OK");
        println("  app       = Quarkus 'started in Xs' (parsed from container logs)");
        List<Double> all = new ArrayList<>();
        List<Double> appAll = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            if (i == 0) dropPageCache();
            double ms = measureContainerStartup(image, containerName);
            double appMs = parseAppStartupFromLogs(containerName);
            all.add(ms);
            appAll.add(appMs);
            String tag = i == 0 ? "COLD" : String.valueOf(i + 1);
            System.out.printf("  %-4s container: %.0fms  app: %.0fms%n", tag, ms, appMs);
            stopContainer(containerName);
            waitForPortFree(port);
            Thread.sleep(2000);
        }
        r.coldStartMs = all.get(0);
        r.appColdStartMs = appAll.get(0);
        List<Double> warm = all.subList(1, all.size());
        List<Double> appWarm = appAll.subList(1, appAll.size());
        r.warmAvgMs = warm.stream().mapToDouble(d -> d).average().orElse(0);
        r.warmMinMs = warm.stream().mapToDouble(d -> d).min().orElse(0);
        r.warmMaxMs = warm.stream().mapToDouble(d -> d).max().orElse(0);
        r.appWarmAvgMs = appWarm.stream().mapToDouble(d -> d).average().orElse(0);

        startContainer(image, containerName);
        try {
            println("  Waiting 5s for RSS stabilization...");
            Thread.sleep(5000);
            r.rssMb = getContainerRssMb(containerName);
            r.heapMb = getHeapFromApi();
            System.out.printf("  RSS: %d MB | Heap: %d MB%n", r.rssMb, r.heapMb);

            if (warmup) {
                println("  Warmup (" + vus + " VUs, " + warmupDuration + ", discarded)...");
                runWarmupK6();
            }
            println("  k6 load test (" + vus + " VUs, " + duration + ")...");
            K6Result k6 = runK6();
            if (k6 != null) {
                r.rps = k6.rps;
                r.avgLatencyMs = k6.avgMs;
                r.p50Ms = k6.p50Ms;
                r.p90Ms = k6.p90Ms;
                r.p99Ms = k6.p99Ms;
                r.totalReqs = k6.totalReqs;
                System.out.printf("  %.0f req/s | P50: %.1f ms | P90: %.1f ms | P99: %.1f ms%n",
                    k6.rps, k6.p50Ms, k6.p90Ms, k6.p99Ms);
            } else {
                println("  k6 test failed or produced no results");
            }
        } finally {
            stopContainer(containerName);
        }

        return r;
    }

    private double measureContainerStartup(String image, String containerName) throws Exception {
        removeContainer(containerName);
        waitForPortFree(port);
        long t0 = System.nanoTime();
        startContainerDetached(image, containerName);
        boolean ready = waitForHealth(port, 60_000);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (!ready) System.err.println("  WARNING: container did not start within 60s");
        return ms;
    }

    private double parseAppStartupFromLogs(String containerName) {
        for (int i = 0; i < 3; i++) {
            try {
                if (i > 0) Thread.sleep(200);
                var pb = new ProcessBuilder("docker", "logs", containerName).redirectErrorStream(true);
                Process p = pb.start();
                String logs = new String(p.getInputStream().readAllBytes());
                p.waitFor();
                var pattern = java.util.regex.Pattern.compile("started in ([0-9.]+)s");
                var m = pattern.matcher(logs);
                if (m.find()) return Double.parseDouble(m.group(1)) * 1000;
            } catch (Exception ignored) {}
        }
        return 0;
    }

    private void waitForPortFree(int port) throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("localhost", port), 200);
            } catch (Exception e) {
                return;
            }
            Thread.sleep(200);
        }
        System.err.println("  WARNING: port " + port + " still in use after 15s");
    }

    private void startContainerDetached(String image, String containerName) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
            "docker", "run", "-d", "--rm",
            "--name", containerName,
            "--network", "host",
            "-e", "QUARKUS_HTTP_PORT=" + port
        ));
        if (appCpus != null && !appCpus.isEmpty()) {
            cmd.add("--cpuset-cpus");
            cmd.add(appCpus);
        }
        cmd.add(image);
        var pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0) {
            throw new RuntimeException("docker run failed (exit " + code + "): " + output.trim());
        }
    }

    private void startContainer(String image, String containerName) throws Exception {
        removeContainer(containerName);
        startContainerDetached(image, containerName);
        if (!waitForHealth(port, 60_000)) {
            throw new RuntimeException("Container did not start within 60s");
        }
    }

    private void stopContainer(String containerName) {
        try {
            var pb = new ProcessBuilder("docker", "stop", containerName)
                .redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().readAllBytes();
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            p.destroyForcibly();
        } catch (Exception ignored) {}
        removeContainer(containerName);
    }

    private void removeContainer(String containerName) {
        try {
            var pb = new ProcessBuilder("docker", "rm", "-f", containerName)
                .redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().readAllBytes();
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            p.destroyForcibly();
        } catch (Exception ignored) {}
    }

    private boolean waitForHealth(int port, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000;
        while (System.nanoTime() < deadline) {
            Thread.sleep(50);
            try {
                var c = (java.net.HttpURLConnection)
                    new java.net.URL("http://localhost:" + port + "/q/health").openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(500);
                c.setReadTimeout(500);
                if (c.getResponseCode() == 200) { c.disconnect(); return true; }
                c.disconnect();
            } catch (Exception ignored) {}
        }
        return false;
    }

    private long getContainerRssMb(String containerName) throws Exception {
        var pb = new ProcessBuilder("docker", "stats", "--no-stream",
            "--format", "{{.MemUsage}}", containerName).redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return parseMemMb(out);
    }

    private long parseMemMb(String memUsage) {
        try {
            String[] parts = memUsage.split("/");
            if (parts.length < 1) return 0;
            String used = parts[0].trim().toLowerCase();
            double val = Double.parseDouble(used.replaceAll("[^0-9.]", ""));
            if (used.contains("gib") || used.contains("gb")) return (long)(val * 1024);
            if (used.contains("kib") || used.contains("kb")) return (long)(val / 1024);
            return (long) val;
        } catch (Exception e) { return 0; }
    }

    private double getImageSizeMb(String image) throws Exception {
        var pb = new ProcessBuilder("docker", "image", "inspect",
            "--format", "{{.Size}}", image).redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        try {
            return Long.parseLong(out) / (1024.0 * 1024.0);
        } catch (NumberFormatException e) { return 0; }
    }

    private boolean imageExists(String image) {
        try {
            var pb = new ProcessBuilder("docker", "image", "inspect", image)
                .redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) { return false; }
    }

    private boolean checkDocker() {
        try {
            var pb = new ProcessBuilder("docker", "info").redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) { return false; }
    }

    private boolean checkK6() {
        try {
            var pb = new ProcessBuilder("k6", "version").redirectErrorStream(true);
            String out = new String(pb.start().getInputStream().readAllBytes());
            return out.contains("k6");
        } catch (Exception e) { return false; }
    }

    private boolean checkDatabase() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", 5432), 1000);
            return true;
        } catch (Exception e) { return false; }
    }

    private void runWarmupK6() {
        try {
            String k6Script = Path.of("benchmark/k6-load.js").toAbsolutePath().toString();
            List<String> k6Cmd = new ArrayList<>();
            if (k6Cpus != null && !k6Cpus.isEmpty()) {
                k6Cmd.add("taskset"); k6Cmd.add("-c"); k6Cmd.add(k6Cpus);
            }
            k6Cmd.add("k6"); k6Cmd.add("run");
            k6Cmd.add("--no-summary");
            k6Cmd.add("--env"); k6Cmd.add("K6_BASE_URL=http://localhost:" + port);
            k6Cmd.add("--env"); k6Cmd.add("K6_VUS=" + vus);
            k6Cmd.add("--env"); k6Cmd.add("K6_DURATION=" + warmupDuration);
            k6Cmd.add(k6Script);
            var pb = new ProcessBuilder(k6Cmd).redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().readAllBytes();
            p.waitFor();
        } catch (Exception e) {
            System.err.println("  Warmup k6 error: " + e.getMessage());
        }
    }

    private K6Result runK6() {
        try {
            Path summaryFile = Files.createTempFile("k6-summary-", ".json");
            summaryFile.toFile().deleteOnExit();

            String k6Script = Path.of("benchmark/k6-load.js").toAbsolutePath().toString();

            List<String> k6Cmd = new ArrayList<>();
            if (k6Cpus != null && !k6Cpus.isEmpty()) {
                k6Cmd.add("taskset"); k6Cmd.add("-c"); k6Cmd.add(k6Cpus);
            }
            k6Cmd.add("k6"); k6Cmd.add("run");
            k6Cmd.add("--summary-export"); k6Cmd.add(summaryFile.toString());
            k6Cmd.add("--env"); k6Cmd.add("K6_BASE_URL=http://localhost:" + port);
            k6Cmd.add("--env"); k6Cmd.add("K6_VUS=" + vus);
            k6Cmd.add("--env"); k6Cmd.add("K6_DURATION=" + duration);
            k6Cmd.add(k6Script);
            var pb = new ProcessBuilder(k6Cmd).redirectErrorStream(true);

            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            int code = p.waitFor();
            if (code != 0) {
                System.err.println("  k6 exit code: " + code);
                System.err.println(output.substring(0, Math.min(output.length(), 500)));
                return null;
            }

            String json = Files.readString(summaryFile);
            Files.deleteIfExists(summaryFile);

            K6Result r = new K6Result();
            r.rps = parseMetricValue(json, "http_reqs", "rate");
            r.totalReqs = (long) parseMetricValue(json, "http_reqs", "count");
            r.avgMs = parseMetricValue(json, "http_req_duration", "avg");
            r.p50Ms = parseMetricValue(json, "http_req_duration", "med");
            r.p90Ms = parseMetricValue(json, "http_req_duration", "p(90)");
            r.p99Ms = parseMetricValue(json, "http_req_duration", "p(99)");
            return r;

        } catch (Exception e) {
            System.err.println("  k6 error: " + e.getMessage());
            return null;
        }
    }

    private double parseMetricValue(String json, String metricName, String field) {
        String metricKey = "\"" + metricName + "\":";
        int metricStart = json.indexOf(metricKey);
        if (metricStart < 0) return 0;
        int metricObjStart = json.indexOf("{", metricStart + metricKey.length());
        if (metricObjStart < 0) return 0;
        int metricObjEnd = findMatchingBrace(json, metricObjStart);
        if (metricObjEnd < 0) return 0;
        String metricObj = json.substring(metricObjStart, metricObjEnd + 1);

        String fieldKey = "\"" + field + "\":";
        int fieldStart = metricObj.indexOf(fieldKey);
        if (fieldStart < 0) return 0;
        int colonPos = fieldStart + fieldKey.length();
        int s = colonPos;
        while (s < metricObj.length() && (metricObj.charAt(s) == ' ' || metricObj.charAt(s) == '\n')) s++;
        int e = s;
        while (e < metricObj.length() && (Character.isDigit(metricObj.charAt(e)) || metricObj.charAt(e) == '-' || metricObj.charAt(e) == '.' || metricObj.charAt(e) == 'e' || metricObj.charAt(e) == 'E' || (e > s && (metricObj.charAt(e) == '+' || metricObj.charAt(e) == '-')))) e++;
        try {
            return Double.parseDouble(metricObj.substring(s, e));
        } catch (Exception ex) { return 0; }
    }

    private int findMatchingBrace(String json, int start) {
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private long getHeapFromApi() {
        try {
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            var req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/system/info"))
                .timeout(Duration.ofSeconds(5)).GET().build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return Long.parseLong(extractNum(resp.body(), "usedMemoryMb"));
        } catch (Exception ignored) {}
        return 0;
    }

    private Map<String, Object> collectMachineInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            m.put("cpu", readCmd("lscpu -J 2>/dev/null | head -1").isEmpty()
                    ? readCmd("grep 'model name' /proc/cpuinfo | head -1 | cut -d: -f2-").trim()
                    : readCmd("lscpu | grep 'Model name' | head -1 | cut -d: -f2-").trim());
            m.put("cores", readCmd("nproc").trim());
            m.put("memoryGb", readCmd("free -g | awk '/^Mem:/{print $2}'").trim());
            m.put("kernel", readCmd("uname -r").trim());
            m.put("os", readCmd("cat /etc/os-release 2>/dev/null | grep PRETTY_NAME | cut -d= -f2- | tr -d '\"'").trim());
            m.put("docker", readCmd("docker version --format '{{.Server.Version}}' 2>/dev/null").trim());
        } catch (Exception ignored) {}
        return m;
    }

    private String readCmd(String cmd) {
        try {
            var pb = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return out;
        } catch (Exception e) { return ""; }
    }

    private void dropPageCache() {
        try {
            var pb = new ProcessBuilder("sh", "-c",
                "sync && echo 3 | sudo tee /proc/sys/vm/drop_caches > /dev/null");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().readAllBytes();
            int code = p.waitFor();
            if (code == 0) println("  (page cache dropped, true cold start)");
            else println("  (sudo failed: install NOPASSWD rule for /usr/bin/tee /proc/sys/vm/drop_caches)");
        } catch (Exception e) {
            println("  (sudo error, page cache warm)");
        }
    }

    private void pinDbContainer() {
        try {
            var pb = new ProcessBuilder("docker", "update", "--cpuset-cpus", dbCpus, dbContainer)
                .redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            int code = p.waitFor();
            if (code == 0) println("  DB pinned: cpuset=" + dbCpus + " (" + dbContainer + ")");
            else println("  WARNING: DB pinning failed: " + out.trim());
        } catch (Exception e) {
            println("  WARNING: DB pinning error: " + e.getMessage());
        }
    }

    private void printTable(ModeResult jvm, ModeResult nat, Map<String, Object> machine) {
        println("╔══════════════════════════════════════════════════════════════════╗");
        boxLine("Results");
        println("╠═════════════════════════╤══════════╤══════════╤═════════════════╣");
        System.out.printf("║ %-23s │ %8s │ %8s │ %15s ║%n", "Metric", "JVM", "Native", "Ratio");
        println("╠═════════════════════════╪══════════╪══════════╪═════════════════╣");

        if (jvm != null && nat != null) {
        row("Cold start: container (ms)", f(jvm.coldStartMs), f(nat.coldStartMs), betterLowerD(jvm.coldStartMs, nat.coldStartMs));
        row("Cold start: Quarkus log (ms)", f(jvm.appColdStartMs), f(nat.appColdStartMs), betterLowerD(jvm.appColdStartMs, nat.appColdStartMs));
        row("Warm avg: container (ms)", f(jvm.warmAvgMs), f(nat.warmAvgMs), betterLowerD(jvm.warmAvgMs, nat.warmAvgMs));
        row("Warm avg: Quarkus log (ms)", f(jvm.appWarmAvgMs), f(nat.appWarmAvgMs), betterLowerD(jvm.appWarmAvgMs, nat.appWarmAvgMs));
            row("RSS memory (MB)",        s(jvm.rssMb),          s(nat.rssMb),          betterLowerL(jvm.rssMb, nat.rssMb));
            row("Heap used (MB)",         s(jvm.heapMb),         s(nat.heapMb),         betterLowerL(jvm.heapMb, nat.heapMb));
            row("Container image (MB)",   f1(jvm.imageSizeMb),   f1(nat.imageSizeMb),   betterLowerD(jvm.imageSizeMb, nat.imageSizeMb));
            row("Requests/s (k6)",        f0(jvm.rps),           f0(nat.rps),           betterHigherD(jvm.rps, nat.rps));
            row("P50 latency (ms)",       f1(jvm.p50Ms),         f1(nat.p50Ms),         betterLowerD(jvm.p50Ms, nat.p50Ms));
            row("P90 latency (ms)",       f1(jvm.p90Ms),         f1(nat.p90Ms),         betterLowerD(jvm.p90Ms, nat.p90Ms));
            row("P99 latency (ms)",       f1(jvm.p99Ms),         f1(nat.p99Ms),         betterLowerD(jvm.p99Ms, nat.p99Ms));

            println("╠═════════════════════════╧══════════╧══════════╧═════════════════╣");
            if (jvm.coldStartMs > 0 && nat.coldStartMs > 0)
                boxLine(String.format("★ Native container cold start: %.1fx faster", jvm.coldStartMs / nat.coldStartMs));
            if (jvm.appColdStartMs > 0 && nat.appColdStartMs > 0)
                boxLine(String.format("★ Native Quarkus init: %.1fx faster", jvm.appColdStartMs / nat.appColdStartMs));
            if (jvm.rssMb > 0 && nat.rssMb > 0)
                boxLine(String.format("★ Native uses %.1fx less RSS memory", (double) jvm.rssMb / nat.rssMb));
            if (jvm.imageSizeMb > 0 && nat.imageSizeMb > 0)
                boxLine(String.format("★ Native image %.1fx smaller", jvm.imageSizeMb / nat.imageSizeMb));
            if (jvm.rps > 0 && nat.rps > 0 && jvm.rps >= nat.rps)
                boxLine(String.format("★ JVM throughput: %.1fx higher (expected for DB-bound)", jvm.rps / nat.rps));
        boxLine("");
        boxLine("Container = docker run -> /q/health (production-realistic)");
        boxLine("Quarkus = \"started in Xs\" log (app-only, comparable)");
        boxLine("Network: --network host (PostgreSQL on localhost:5432)");
        boxLine("k6 load test: " + vus + " VUs, " + duration);
        if (appCpus != null || dbCpus != null || k6Cpus != null) {
            String pin = "CPU pinning:";
            if (appCpus != null) pin += " app=" + appCpus;
            if (dbCpus != null) pin += " db=" + dbCpus;
            if (k6Cpus != null) pin += " k6=" + k6Cpus;
            boxLine(pin);
        }
        if (!machine.isEmpty()) {
            String cpu = machine.getOrDefault("cpu", "").toString();
            String cores = machine.getOrDefault("cores", "").toString();
            String mem = machine.getOrDefault("memoryGb", "").toString();
            if (!cpu.isEmpty()) boxLine("Machine: " + cpu.trim() + " | " + cores + " cores | " + mem + " GB RAM");
        }
            println("╚══════════════════════════════════════════════════════════════════╝");
    } else {
            ModeResult m = jvm != null ? jvm : nat;
            row("Cold start: container (ms)", f(m.coldStartMs), "", "");
            row("Cold start: Quarkus log (ms)", f(m.appColdStartMs), "", "");
            row("Warm avg: container (ms)", f(m.warmAvgMs), "", "");
            row("Warm avg: Quarkus log (ms)", f(m.appWarmAvgMs), "", "");
            row("RSS memory (MB)", s(m.rssMb), "", "");
            row("Container image (MB)", f1(m.imageSizeMb), "", "");
            row("Requests/s (k6)", f0(m.rps), "", "");
            row("P50 latency (ms)", f1(m.p50Ms), "", "");
            row("P99 latency (ms)", f1(m.p99Ms), "", "");
            println("╠═════════════════════════╧══════════╧══════════╧═════════════════╣");
            if (!machine.isEmpty()) {
                String cpu = machine.getOrDefault("cpu", "").toString();
                String cores = machine.getOrDefault("cores", "").toString();
                String mem = machine.getOrDefault("memoryGb", "").toString();
                if (!cpu.isEmpty()) boxLine("Machine: " + cpu.trim() + " | " + cores + " cores | " + mem + " GB RAM");
            }
            println("╚══════════════════════════════════════════════════════════════════╝");
        }
    }

    private void boxLine(String content) {
        System.out.printf("║ %-64s ║%n", content);
    }

    private String betterLowerD(double jvmVal, double natVal) {
        if (jvmVal <= 0 || natVal <= 0) return "—";
        if (jvmVal > natVal) return String.format("%.1fx Native", jvmVal / natVal);
        if (natVal > jvmVal) return String.format("%.1fx JVM", natVal / jvmVal);
        return "1.0x";
    }

    private String betterLowerL(long jvmVal, long natVal) {
        if (jvmVal <= 0 || natVal <= 0) return "—";
        if (jvmVal > natVal) return String.format("%.1fx Native", (double) jvmVal / natVal);
        if (natVal > jvmVal) return String.format("%.1fx JVM", (double) natVal / jvmVal);
        return "1.0x";
    }

    private String betterHigherD(double jvmVal, double natVal) {
        if (jvmVal <= 0 || natVal <= 0) return "—";
        if (jvmVal > natVal) return String.format("%.1fx JVM", jvmVal / natVal);
        if (natVal > jvmVal) return String.format("%.1fx Native", natVal / jvmVal);
        return "1.0x";
    }

    private void row(String label, String jvm, String nat, String ratio) {
        System.out.printf("║ %-23s │ %8s │ %8s │ %15s ║%n", label, jvm, nat, ratio);
    }

    private String f(double v) { return v > 0 ? String.format("%.0f", v) : "—"; }
    private String s(long v) { return v > 0 ? String.valueOf(v) : "—"; }
    private String f0(double v) { return v > 0 ? String.format("%.0f", v) : "—"; }
    private String f1(double v) { return v > 0 ? String.format("%.1f", v) : "—"; }

    private String extractNum(String json, String key) {
        String p = "\"" + key + "\":";
        int i = json.indexOf(p);
        if (i < 0) return "0";
        int s = i + p.length(), e = s;
        while (e < json.length() && (Character.isDigit(json.charAt(e)) || json.charAt(e) == '.' || json.charAt(e) == '-')) e++;
        return json.substring(s, e).split("\\.")[0];
    }

    private void println(String s) { System.out.println(s); }
    private void println() { System.out.println(); }

    static class ModeResult {
        String label;
        double coldStartMs, warmAvgMs, warmMinMs, warmMaxMs;
        double appColdStartMs, appWarmAvgMs;
        long rssMb, heapMb;
        double imageSizeMb;
        double rps, avgLatencyMs, p50Ms, p90Ms, p99Ms;
        long totalReqs;

        ModeResult(String label) { this.label = label; }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("coldStartMs", coldStartMs);
            s.put("warmAvgMs", warmAvgMs);
            s.put("warmMinMs", warmMinMs);
            s.put("warmMaxMs", warmMaxMs);
            s.put("appColdStartMs", appColdStartMs);
            s.put("appWarmAvgMs", appWarmAvgMs);
            m.put("startup", s);
            Map<String, Object> mem = new LinkedHashMap<>();
            mem.put("rssMb", rssMb);
            mem.put("heapMb", heapMb);
            m.put("memory", mem);
            m.put("imageSizeMb", imageSizeMb);
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("rps", rps);
            k.put("avgLatencyMs", avgLatencyMs);
            k.put("p50Ms", p50Ms);
            k.put("p90Ms", p90Ms);
            k.put("p99Ms", p99Ms);
            k.put("totalReqs", totalReqs);
            m.put("k6", k);
            return m;
        }
    }

    static class K6Result {
        double rps, avgMs, p50Ms, p90Ms, p99Ms;
        long totalReqs;
    }

    private String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(e.getKey()).append("\":").append(val(e.getValue()));
        }
        return sb.append("}").toString();
    }

    private String val(Object v) {
        if (v == null) return "null";
        if (v instanceof Number) return v.toString();
        if (v instanceof String) return "\"" + v + "\"";
        if (v instanceof Map) return toJson((Map<String, Object>) v);
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            List<?> l = (List<?>) v;
            for (int i = 0; i < l.size(); i++) { if (i > 0) sb.append(","); sb.append(val(l.get(i))); }
            return sb.append("]").toString();
        }
        return "\"" + v + "\"";
    }

    public static void main(String... args) { System.exit(new CommandLine(new Compare()).execute(args)); }
}
