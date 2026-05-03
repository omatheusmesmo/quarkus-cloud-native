.PHONY: dev test package native native-docker run-jvm run-native clean benchmark compare startup memory load-test report metrics-record deploy-knative undeploy-knative db-up db-down db-status slides slides-serve help

BASE_URL ?= http://localhost:8080
ITERATIONS ?= 1000
CONCURRENCY ?= 10
DURATION ?= 30s

help:
	@echo "Quarkus Cloud Native Demo"
	@echo ""
	@echo "Development:"
	@echo "  make dev             Start dev mode"
	@echo "  make test            Run unit tests"
	@echo ""
	@echo "Build:"
	@echo "  make package         Build JAR"
	@echo "  make native          Build native executable (local GraalVM)"
	@echo "  make native-docker   Build native executable (Docker container build)"
	@echo ""
	@echo "Run (requires make db-up first):"
	@echo "  make run-jvm         Run JVM mode (prod profile)"
	@echo "  make run-native      Run native executable (prod profile)"
	@echo ""
	@echo "Benchmarks (requires running app):"
	@echo "  make benchmark       Run self-benchmark endpoint"
	@echo "  make startup         Measure startup time (JVM vs Native)"
	@echo "  make memory          Measure RSS memory (JVM vs Native)"
	@echo "  make load-test       Run HTTP load test"
	@echo "  make compare         Full JVM vs Native comparison"
	@echo "  make report          Generate comparison report"
	@echo "  make metrics-record  Collect and save versioned metrics snapshot"
	@echo ""
	@echo "Kubernetes / Knative:"
	@echo "  make deploy-knative   Deploy to Knative"
	@echo "  make undeploy-knative Remove from Knative"
	@echo ""
	@echo "Database:"
	@echo " make db-up Start PostgreSQL via Docker Compose"
	@echo " make db-down Stop PostgreSQL"
	@echo " make db-status Check PostgreSQL status"
	@echo ""
	@echo "Slides:"
	@echo " make slides Render presentation (static HTML)"
	@echo " make slides-serve Render and serve with live reload"

dev:
	./mvnw quarkus:dev

test:
	./mvnw test

package:
	./mvnw package -DskipTests

native:
	./mvnw package -Dnative -DskipTests

native-docker:
	./mvnw package -Dnative -DskipTests -Dquarkus.native.container-build=true -Dquarkus.container-image.build=true

run-jvm: package
	java -jar target/quarkus-app/quarkus-run.jar

run-native: native
	./target/quarkus-cloud-native-1.0.0-SNAPSHOT-runner

benchmark:
	jbang benchmark/Benchmark.java --url $(BASE_URL) --iterations $(ITERATIONS)

startup:
	jbang benchmark/Startup.java

memory:
	jbang benchmark/Memory.java

load-test:
	jbang benchmark/LoadTest.java --url $(BASE_URL) --concurrency $(CONCURRENCY) --duration $(DURATION)

compare:
	jbang benchmark/Compare.java --iterations $(ITERATIONS)

report:
	jbang benchmark/Report.java

metrics-record:
	jbang benchmark/MetricsRecord.java --url $(BASE_URL) --benchmark-iterations $(ITERATIONS)

deploy-knative:
	kubectl apply -f target/kubernetes/knative.yml

undeploy-knative:
	kubectl delete -f target/kubernetes/knative.yml

clean:
	./mvnw clean

db-up:
	docker compose up -d
	@echo "Waiting for PostgreSQL..."
	@until docker compose exec -T postgres pg_isready -U quarkus > /dev/null 2>&1; do sleep 1; done
	@echo "PostgreSQL ready on localhost:5432"

db-down:
	docker compose down

db-status:
	docker compose ps

slides:
	lazyslide render slides/

slides-serve:
	lazyslide serve slides/
