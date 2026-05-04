# Quarkus Cloud Native

Webhook receiver API backed by PostgreSQL, designed for Knative Serving with scale-to-zero. Includes a container-based benchmark comparing JVM vs Native startup, memory, and throughput under CPU-pinned conditions.

## Prerequisites

- [SDKMAN](https://sdkman.io) with JDK 25 (`25.0.2-open`)
- [Mandrel](https://github.com/graalvm/mandrel) `25.0.2.r25-mandrel` for native builds
- [JBang](https://www.jbang.dev) for the benchmark script
- [k6](https://k6.io) for load testing
- Docker (for PostgreSQL and container builds)
- [lazyslide](https://github.com/maxandersen/lazyslide) for presentation slides

## Quick Start

```shell
sdk install java 25.0.2-open
make db-up
make dev
make test
```

## Build & Run

```shell
make package && make run-jvm
make native && make run-native
```

## Container-Based Benchmark

`make compare` runs everything: auto-builds missing images, starts PostgreSQL if needed, then benchmarks both modes.

### Methodology

1. `docker run -d --rm --network host` starts the container; health check confirms `/q/health` returns 200 OK
2. 5 startup iterations per mode: 1st = cold (after `drop_caches`), 2-5 = warm average
3. CPU pinning via `--cpuset-cpus` keeps app, DB, and k6 on separate cores
4. k6 warmup: 500 VUs for 60s (discarded), then 60s measurement pass
5. Results saved to `metrics/compare-YYYY-MM-DD.json`

**Two startup measurements:**

- **Container** = `docker run` to `/q/health` 200 OK (production-realistic, includes container overhead)
- **Quarkus** = `started in Xs` from container logs (app-only reference)

### Sample Results

| Metric | JVM | Native | Ratio |
|--------|-----|--------|-------|
| Cold start: container | 3,541 ms | 642 ms | 5.5x Native |
| Cold start: Quarkus | 2,718 ms | 174 ms | 15.6x Native |
| Warm avg: container | 2,400 ms | 301 ms | 8.0x Native |
| Warm avg: Quarkus | 1,990 ms | 48 ms | 41.5x Native |
| RSS memory | 241 MB | 12 MB | 20.1x Native |
| Heap used | 26 MB | 9 MB | 2.9x Native |
| Container image | 185.2 MB | 37.9 MB | 4.9x Native |
| Requests/s (k6) | 408 | 260 | 1.6x JVM |
| P50 latency | 1,039 ms | 1,581 ms | 1.5x JVM |
| P99 latency | 4,265 ms | 7,118 ms | 1.7x JVM |

### Throughput Trade-off

JVM wins sustained throughput: JIT optimization and a larger heap allow higher requests/s and lower tail latency. Native wins cold start and memory density: faster startup, smaller footprint, smaller container image. The Native P99 penalty comes from Serial GC, a smaller heap, and less JIT optimization. For scale-to-zero workloads, cold start and memory density matter more than steady-state throughput.

### Machine Info

AMD Ryzen 5 5600GT / 12 cores / 30 GB RAM / CPU pinning: app=2-5, db=0-1, k6=6-11

## Knative Deployment

```shell
make native
make deploy-knative
```

## Database

```shell
make db-up
make db-down
make db-status
```

The `%prod` datasource config is baked into the native binary at build time.

## Presentation

```shell
jbang app install lazyslide@maxandersen
lazyslide serve slides/
```

## Tech Stack

- Quarkus 3.35.1
- Hibernate ORM with Panache (snake_case naming strategy)
- PostgreSQL 18
- SmallRye Health, OpenAPI, Micrometer + Prometheus
- Mandrel 25.0.2.0-Final for native compilation
- JBang benchmark script with Picocli
- k6 for load testing
- lazyslide for presentation

## Guides

- [Quarkus REST](https://quarkus.io/guides/rest)
- [Hibernate ORM with Panache](https://quarkus.io/guides/hibernate-orm-panache)
- [Building Native Executables](https://quarkus.io/guides/building-native-image)
- [Kubernetes](https://quarkus.io/guides/kubernetes)
- [SmallRye Health](https://quarkus.io/guides/smallrye-health)
