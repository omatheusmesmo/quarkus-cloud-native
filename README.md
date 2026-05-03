# Quarkus Cloud Native

A Quarkus demo showcasing JVM vs Native compilation for Knative deployments, with benchmark tooling to measure the real performance differences that matter for cold starts.

## What it does

Webhook receiver API backed by PostgreSQL, designed to run on Knative Serving with scale-to-zero. Includes a suite of JBang benchmark scripts to measure startup time, RSS memory, time-to-first-request, and binary size — the metrics that matter most when Knative scales from zero.

## Endpoints

| Path | Description |
|------|-------------|
| `POST /api/webhooks` | Create webhook event |
| `GET /api/webhooks` | List all webhooks |
| `GET /api/webhooks/{id}` | Get webhook by ID |
| `GET /api/system/info` | Runtime info (mode, JVM, memory) |
| `GET /api/system/benchmark` | Self-benchmark endpoint |
| `GET /q/health` | Health check |
| `GET /q/openapi` | OpenAPI schema |

## Prerequisites

- [SDKMAN](https://sdkman.io) with JDK 25 (`25.0.2-open`)
- [Mandrel](https://github.com/graalvm/mandrel) `25.0.2.r25-mandrel` for native builds
- [JBang](https://www.jbang.dev) for benchmark scripts
- [lazyslide](https://github.com/maxandersen/lazyslide) for presentation slides
- Docker (for PostgreSQL and optional container builds)

## Quick Start

```shell
# Install JDK
sdk install java 25.0.2-open

# Start PostgreSQL
make db-up

# Dev mode (live reload)
make dev

# Run tests
make test
```

## Build & Run

```shell
# JVM mode
make package
make run-jvm

# Native mode (requires Mandrel)
make native
make run-native
```

## Benchmarks

All benchmark scripts are in `benchmark/` and run via JBang:

```shell
# Collect full metrics snapshot (startup, RSS, TTFR, sizes)
make metrics-record

# Individual benchmarks
make benchmark          # Self-benchmark via /api/system/benchmark
make startup            # Measure JVM vs Native startup time
make memory             # Measure RSS memory usage
make load-test          # HTTP load test
make compare            # Full JVM vs Native comparison
make report             # Generate comparison report
```

Metrics are saved to `metrics/YYYY-MM-DD.json` with versioned history in `metrics/history.json`.

### Sample Results

| Metric | Native | JVM | Ratio |
|--------|--------|-----|-------|
| Startup time | ~70 ms | ~2700 ms | 37x faster |
| Time-to-first-request | ~70 ms | ~2700 ms | 37x faster |
| RSS memory | ~79 MB | ~254 MB | 3.2x less |
| Binary size (total) | 86.3 MB | 336.0 MB | 3.9x smaller |

> Native binary includes runtime. JVM total includes app (42.7 MB) + JDK runtime (293.2 MB).

## Presentation

Install [lazyslide](https://github.com/maxandersen/lazyslide) and serve the slides with live reload:

```shell
jbang app install lazyslide@maxandersen
lazyslide serve slides/
```

The presentation covers the Knative cold start problem, JVM vs Native benchmarks, and deployment to Knative Serving.

## Knative Deployment

```shell
# Build native executable
make native

# Deploy to Knative
make deploy-knative

# Remove from Knative
make undeploy-knative
```

The project generates Knative manifests via `quarkus.kubernetes.deployment-target=knative` in `application.properties`.

## Database

PostgreSQL 18 runs via Docker Compose:

```shell
make db-up      # Start
make db-down    # Stop
make db-status  # Check status
```

The `%prod` datasource config is baked into the native binary at build time, so no runtime flags are needed when Docker Compose DB is up.

## All Make Targets

```shell
make help
```

## Tech Stack

- Quarkus 3.35.1
- Hibernate ORM with Panache (snake_case naming strategy)
- PostgreSQL 18
- SmallRye Health, OpenAPI, Micrometer + Prometheus
- Mandrel 25.0.2.0-Final for native compilation
- JBang benchmark scripts with Picocli
- lazyslide for presentation

## Guides

- [Quarkus REST](https://quarkus.io/guides/rest)
- [Hibernate ORM with Panache](https://quarkus.io/guides/hibernate-orm-panache)
- [Building Native Executables](https://quarkus.io/guides/building-native-image)
- [Kubernetes](https://quarkus.io/guides/kubernetes)
- [SmallRye Health](https://quarkus.io/guides/smallrye-health)
