///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.6

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "memory", mixinStandardHelpOptions = true,
    description = "Measure RSS memory for JVM and Native modes")
class Memory implements Callable<Integer> {

    @Option(names = "--jar", description = "Path to the runner JAR")
    String jarPath;

    @Option(names = "--native-bin", description = "Path to the native executable")
    String nativePath;

    @Option(names = "--wait-seconds", defaultValue = "5", description = "Seconds to wait before measuring")
    int waitSeconds;

    @Option(names = "--output", description = "Output JSON file")
    String outputFile;

    @Override
    public Integer call() throws Exception {
        if (jarPath == null) jarPath = findJar();
        if (nativePath == null) nativePath = findNative();

        System.out.println("=== RSS Memory Comparison ===");
        System.out.printf("%-20s %-15s %-15s%n", "Mode", "RSS (MB)", "Heap (MB)");
        System.out.println("-".repeat(50));

        long jvmRss = 0, jvmHeap = 0, nativeRss = 0, nativeHeap = 0;

        if (jarPath != null && java.nio.file.Files.exists(java.nio.file.Path.of(jarPath))) {
            Process p = new ProcessBuilder("java", "-jar", jarPath).redirectErrorStream(true).start();
            Thread.sleep(waitSeconds * 1000L);
            jvmRss = getRssMb(p.pid());
            jvmHeap = getHeapFromApi();
            p.destroy();
            p.waitFor();
            System.out.printf("%-20s %-15d %-15d%n", "JVM", jvmRss, jvmHeap);
        }

        if (nativePath != null && java.nio.file.Files.exists(java.nio.file.Path.of(nativePath))) {
            Process p = new ProcessBuilder(nativePath).redirectErrorStream(true).start();
            Thread.sleep(waitSeconds * 1000L);
            nativeRss = getRssMb(p.pid());
            nativeHeap = getHeapFromApi();
            p.destroy();
            p.waitFor();
            System.out.printf("%-20s %-15d %-15d%n", "Native", nativeRss, nativeHeap);
        }

        if (jvmRss > 0 && nativeRss > 0) {
            System.out.printf("%nNative uses %.1fx less RSS memory%n", (double) jvmRss / nativeRss);
        }

        if (outputFile != null) {
            String json = String.format("{\"jvm\":{\"rssMb\":%d,\"heapMb\":%d},\"native\":{\"rssMb\":%d,\"heapMb\":%d}}",
                jvmRss, jvmHeap, nativeRss, nativeHeap);
            java.nio.file.Files.writeString(java.nio.file.Path.of(outputFile), json);
            System.out.println("Results saved to: " + outputFile);
        }

        return 0;
    }

    private long getRssMb(long pid) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid));
        pb.redirectErrorStream(true);
        Process ps = pb.start();
        String output = new String(ps.getInputStream().readAllBytes()).trim();
        ps.waitFor();
        long rssKb = Long.parseLong(output.split("\\s+")[0]);
        return rssKb / 1024;
    }

    private long getHeapFromApi() {
        try {
            var client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(3)).build();
            var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:8080/api/system/info"))
                .timeout(java.time.Duration.ofSeconds(5)).GET().build();
            var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                String body = resp.body();
                String heap = extractJsonNumber(body, "usedMemoryMb");
                return Long.parseLong(heap);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private String extractJsonNumber(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx < 0) return "0";
        int start = idx + pattern.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-')) end++;
        return json.substring(start, end).split("\\.")[0];
    }

    private String findJar() {
        java.io.File dir = new java.io.File("target");
        if (dir.exists()) {
            for (String f : dir.list()) {
                if (f.endsWith("-runner.jar") && !f.contains("-sources") && !f.contains("-javadoc")) {
                    return "target/" + f;
                }
            }
        }
        return null;
    }

    private String findNative() {
        java.io.File dir = new java.io.File("target");
        if (dir.exists()) {
            for (String f : dir.list()) {
                if (f.endsWith("-runner") && !f.endsWith(".jar") && !f.endsWith(".zip")) {
                    return "target/" + f;
                }
            }
        }
        return null;
    }

    public static void main(String... args) {
        System.exit(new CommandLine(new Memory()).execute(args));
    }
}
