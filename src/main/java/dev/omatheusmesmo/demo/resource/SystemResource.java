package dev.omatheusmesmo.demo.resource;

import dev.omatheusmesmo.demo.dto.BenchmarkResult;
import dev.omatheusmesmo.demo.dto.SystemInfo;
import io.quarkus.logging.Log;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

@Path("/api/system")
public class SystemResource {

    @GET
    @Path("/info")
    public SystemInfo info() {
        return SystemInfo.current();
    }

    @GET
    @Path("/benchmark")
    @RunOnVirtualThread
    public BenchmarkResult benchmark(@QueryParam("iterations") int iterations) {
        int iters = iterations > 0 ? iterations : 1000;
        Log.infof("[%s] Running self-benchmark with %d iterations", SystemInfo.current().mode(), iters);

        long[] durations = new long[iters];
        long startTotal = System.nanoTime();

        for (int i = 0; i < iters; i++) {
            long start = System.nanoTime();
            mathematicalWorkload();
            durations[i] = System.nanoTime() - start;
        }

        long totalDuration = System.nanoTime() - startTotal;
        return BenchmarkResult.compute(iters, durations, totalDuration);
    }

    private void mathematicalWorkload() {
        double result = 0;
        for (int i = 1; i <= 100; i++) {
            result += Math.sqrt(i) * Math.log1p(i) / Math.cbrt(i);
        }
    }
}
