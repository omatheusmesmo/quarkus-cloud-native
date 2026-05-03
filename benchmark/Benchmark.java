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

@Command(name = "benchmark", mixinStandardHelpOptions = true,
    description = "Run self-benchmark against the Event Processor API")
class Benchmark implements Callable<Integer> {

    @Option(names = "--url", defaultValue = "http://localhost:8080", description = "Base URL")
    String baseUrl;

    @Option(names = "--iterations", defaultValue = "1000", description = "Number of iterations")
    int iterations;

    @Option(names = "--output", description = "Output JSON file")
    String outputFile;

    @Override
    public Integer call() throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

        String url = baseUrl + "/api/system/benchmark?iterations=" + iterations;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build();

        System.out.println("Running benchmark: " + iterations + " iterations against " + url);
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.err.println("Benchmark failed: HTTP " + response.statusCode());
            return 1;
        }

        String json = response.body();
        System.out.println("\n--- Benchmark Result ---");
        System.out.println(json);

        if (outputFile != null) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(outputFile), json);
            System.out.println("Results saved to: " + outputFile);
        }

        return 0;
    }

    public static void main(String... args) {
        System.exit(new CommandLine(new Benchmark()).execute(args));
    }
}
