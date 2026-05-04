.PHONY: dev test package native jvm-image native-image run-jvm run-native clean compare deploy-knative undeploy-knative db-up db-down db-status slides slides-serve help

help:
	@echo "Quarkus Cloud Native Demo"
	@echo ""
	@echo "Development:"
	@echo "  make dev            Start dev mode"
	@echo "  make test           Run unit tests"
	@echo ""
	@echo "Build:"
	@echo "  make package        Build JAR"
	@echo "  make native         Build native executable"
	@echo "  make jvm-image      Build JVM container image"
	@echo "  make native-image   Build native container image"
	@echo ""
	@echo "Run (requires make db-up first):"
	@echo "  make run-jvm        Run JVM mode (prod)"
	@echo "  make run-native     Run native executable (prod)"
	@echo ""
	@echo "Benchmark (container-based):"
	@echo "  make compare         Full JVM vs Native comparison (500 VUs, 60s, CPU-pinned)"
	@echo ""
	@echo "Kubernetes / Knative:"
	@echo "  make deploy-knative Deploy to Knative"
	@echo "  make undeploy-knative Remove from Knative"
	@echo ""
	@echo "Database:"
	@echo "  make db-up          Start PostgreSQL"
	@echo "  make db-down        Stop PostgreSQL"
	@echo "  make db-status      Check PostgreSQL status"
	@echo ""
	@echo "Slides:"
	@echo "  make slides         Render static HTML"
	@echo "  make slides-serve   Serve with live reload"

dev:
	./mvnw quarkus:dev

test:
	./mvnw test

package:
	./mvnw package -DskipTests

native:
	./mvnw package -Dnative -DskipTests

jvm-image: package
	docker build -f src/main/docker/Dockerfile.jvm -t quarkus-cloud-native:jvm .

native-image: native
	docker build -f src/main/docker/Dockerfile.native-micro -t quarkus-cloud-native:native .

run-jvm: package
	java -jar target/quarkus-app/quarkus-run.jar

run-native: native
	./target/quarkus-cloud-native-1.0.0-SNAPSHOT-runner

compare:
	@docker compose ps postgres 2>/dev/null | grep -q running || (echo "Starting PostgreSQL..." && make db-up)
	@docker image inspect quarkus-cloud-native:jvm > /dev/null 2>&1 || (echo "Building JVM image..." && make jvm-image)
	@docker image inspect quarkus-cloud-native:native > /dev/null 2>&1 || (echo "Building native image..." && make native-image)
	jbang benchmark/Compare.java \
		--vus 500 \
		--duration 60s \
		--app-cpus 2-5 \
		--db-cpus 0-1 \
		--k6-cpus 6-11 \
		--output metrics/compare-$$(date +%Y-%m-%d).json

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
