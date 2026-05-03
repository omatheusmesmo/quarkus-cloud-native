///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.6

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "report", mixinStandardHelpOptions = true,
    description = "Generate comparison report from benchmark JSON files")
class Report implements Callable<Integer> {

    @Option(names = "--startup-file", defaultValue = "target/startup.json", description = "Startup results JSON")
    String startupFile;

    @Option(names = "--memory-file", defaultValue = "target/memory.json", description = "Memory results JSON")
    String memoryFile;

    @Option(names = "--benchmark-file", defaultValue = "target/benchmark.json", description = "Benchmark results JSON")
    String benchmarkFile;

    @Option(names = "--load-file", defaultValue = "target/load-test.json", description = "Load test results JSON")
    String loadFile;

    @Option(names = "--output", description = "Output report file (default: stdout)")
    String outputFile;

    @Override
    public Integer call() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# Quarkus Cloud Native - Performance Report\n");
        sb.append("Generated: ").append(LocalDateTime.now()).append("\n\n");

        if (Files.exists(Path.of(startupFile))) {
            sb.append("## Startup Time\n```\n");
            sb.append(Files.readString(Path.of(startupFile))).append("\n```\n\n");
        }

        if (Files.exists(Path.of(memoryFile))) {
            sb.append("## Memory Usage\n```\n");
            sb.append(Files.readString(Path.of(memoryFile))).append("\n```\n\n");
        }

        if (Files.exists(Path.of(benchmarkFile))) {
            sb.append("## Compute Benchmark\n```\n");
            sb.append(Files.readString(Path.of(benchmarkFile))).append("\n```\n\n");
        }

        if (Files.exists(Path.of(loadFile))) {
            sb.append("## Load Test\n```\n");
            sb.append(Files.readString(Path.of(loadFile))).append("\n```\n\n");
        }

        String report = sb.toString();
        if (outputFile != null) {
            Files.writeString(Path.of(outputFile), report);
            System.out.println("Report saved to: " + outputFile);
        } else {
            System.out.println(report);
        }

        return 0;
    }

    public static void main(String... args) {
        System.exit(new CommandLine(new Report()).execute(args));
    }
}
