///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.6

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "startup", mixinStandardHelpOptions = true,
    description = "Measure startup time for JVM and Native modes")
class Startup implements Callable<Integer> {

    @Option(names = "--jar", description = "Path to the runner JAR")
    String jarPath;

    @Option(names = "--native-bin", description = "Path to the native executable")
    String nativePath;

    @Option(names = "--warmup", defaultValue = "3", description = "Number of warmup runs to discard")
    int warmup;

    @Option(names = "--runs", defaultValue = "5", description = "Number of measured runs")
    int runs;

    @Option(names = "--output", description = "Output JSON file")
    String outputFile;

    @Override
    public Integer call() throws Exception {
        List<Double> jvmTimes = new ArrayList<>();
        List<Double> nativeTimes = new ArrayList<>();

        if (jarPath == null) {
            jarPath = findJar();
        }
        if (nativePath == null) {
            nativePath = findNative();
        }

        if (jarPath != null && java.nio.file.Files.exists(java.nio.file.Path.of(jarPath))) {
            System.out.println("Measuring JVM startup (" + (warmup + runs) + " runs, discarding " + warmup + ")...");
            for (int i = 0; i < warmup + runs; i++) {
                double time = measureStartup("java", "-jar", jarPath);
                if (i >= warmup) jvmTimes.add(time);
                System.out.printf("  Run %d: %.0f ms%n", i + 1, time);
            }
        }

        if (nativePath != null && java.nio.file.Files.exists(java.nio.file.Path.of(nativePath))) {
            System.out.println("Measuring Native startup (" + (warmup + runs) + " runs, discarding " + warmup + ")...");
            for (int i = 0; i < warmup + runs; i++) {
                double time = measureStartup(nativePath);
                if (i >= warmup) nativeTimes.add(time);
                System.out.printf("  Run %d: %.0f ms%n", i + 1, time);
            }
        }

        System.out.println("\n=== Startup Time Comparison ===");
        System.out.printf("%-20s %-12s %-12s %-12s%n", "Mode", "Avg (ms)", "Min (ms)", "Max (ms)");
        System.out.println("-".repeat(56));

        if (!jvmTimes.isEmpty()) {
            double avg = jvmTimes.stream().mapToDouble(d -> d).average().orElse(0);
            double min = jvmTimes.stream().mapToDouble(d -> d).min().orElse(0);
            double max = jvmTimes.stream().mapToDouble(d -> d).max().orElse(0);
            System.out.printf("%-20s %-12.0f %-12.0f %-12.0f%n", "JVM", avg, min, max);
        }
        if (!nativeTimes.isEmpty()) {
            double avg = nativeTimes.stream().mapToDouble(d -> d).average().orElse(0);
            double min = nativeTimes.stream().mapToDouble(d -> d).min().orElse(0);
            double max = nativeTimes.stream().mapToDouble(d -> d).max().orElse(0);
            System.out.printf("%-20s %-12.0f %-12.0f %-12.0f%n", "Native", avg, min, max);
        }

        if (!jvmTimes.isEmpty() && !nativeTimes.isEmpty()) {
            double jvmAvg = jvmTimes.stream().mapToDouble(d -> d).average().orElse(1);
            double nativeAvg = nativeTimes.stream().mapToDouble(d -> d).average().orElse(1);
            System.out.printf("%nNative is %.1fx faster at startup%n", jvmAvg / nativeAvg);
        }

        if (outputFile != null) {
            StringBuilder json = new StringBuilder("{");
            if (!jvmTimes.isEmpty()) {
                json.append("\"jvm\":{\"avg\":").append(jvmTimes.stream().mapToDouble(d -> d).average().orElse(0))
                    .append(",\"min\":").append(jvmTimes.stream().mapToDouble(d -> d).min().orElse(0))
                    .append(",\"max\":").append(jvmTimes.stream().mapToDouble(d -> d).max().orElse(0)).append("}");
            }
            if (!nativeTimes.isEmpty()) {
                if (!jvmTimes.isEmpty()) json.append(",");
                json.append("\"native\":{\"avg\":").append(nativeTimes.stream().mapToDouble(d -> d).average().orElse(0))
                    .append(",\"min\":").append(nativeTimes.stream().mapToDouble(d -> d).min().orElse(0))
                    .append(",\"max\":").append(nativeTimes.stream().mapToDouble(d -> d).max().orElse(0)).append("}");
            }
            json.append("}");
            java.nio.file.Files.writeString(java.nio.file.Path.of(outputFile), json.toString());
            System.out.println("Results saved to: " + outputFile);
        }

        return 0;
    }

    private double measureStartup(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        long start = System.nanoTime();
        Process process = pb.start();
        boolean exited = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        if (!exited) process.destroyForcibly();
        return elapsed;
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
        System.exit(new CommandLine(new Startup()).execute(args));
    }
}
