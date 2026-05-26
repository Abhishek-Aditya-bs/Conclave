# ADR-008 — Compose stack + `make demo-*` switching (M8)

* **Status.** Accepted (Session 9, 2026-05-26).
* **Module.** M8 — Reference Configurations.
* **Spec hooks.** §6 M8, §11 acceptance criteria 1-3 (`make demo-fraud`,
  `make switch-config security && make demo-security`, `make demo-fraud-local`).

## Context

The spec §11 acceptance criteria require:

1. `git clone && make demo-fraud` works on a fresh Mac in <5 minutes.
2. `make demo-security` switches domains without rebuilding from a different
   branch — same images, different env vars.
3. `make demo-fraud-local` boots an Ollama sidecar so users without an
   Anthropic key still get an end-to-end demo.

Before this ADR, four green Java services + one Python service + the M9
generators existed in isolation. They each had their own local-dev story
(`mvn spring-boot:run`, `make m5-run`, `make m9-run-fraud`). What was
missing was the integrated harness — the moment where one command boots
the whole pipeline.

## Decision

### One `docker-compose.yml`, no per-domain forks

The compose file declares the data-plane infrastructure (Kafka, Schema
Registry, Postgres + pgvector, Neo4j) plus the four CONCLAVE services.
Domain selection is a single env var (`SPRING_PROFILES_ACTIVE`) read by
the orchestrator container; the other three services (baseline, graph,
agents) are domain-agnostic and need no per-domain config.

`make demo-fraud` exports `SPRING_PROFILES_ACTIVE=fraud` and runs
`docker compose up -d`. `make demo-security` swaps the value. No second
compose file, no profile gymnastics on the docker side.

### Ollama path as an overlay, not a second compose file

`docker-compose.ollama.yml` is a small overlay that adds the Ollama
sidecar and flips the agents service's `JUDGE_LLM_PROVIDER` env to
`ollama`. `make demo-fraud-local` invokes:

```
docker compose -f docker-compose.yml -f docker-compose.ollama.yml up -d
```

The overlay pattern keeps the diff between the two demos visible in
~25 lines instead of duplicating the entire stack.

Rejected alternative — `profiles: [ollama]` in the main compose file:
profiles would require every Ollama-mode env var to be present (with
empty defaults) in the main file, polluting it.

### Pre-built fat jars, not in-Docker Maven

Each Java service's Dockerfile is six lines:

```
FROM eclipse-temurin:25-jre
COPY <module>/target/<artifact>-0.1.0-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

`make demo-build` (a prerequisite of every `demo-*` target) runs
`mvn package -DskipTests` on the host, then `docker compose build`.
This is much faster on iteration than a multi-stage in-Docker Maven
build:

- The host already has the Maven repo cached from prior `mvn verify`
  runs. In-Docker Maven would re-download everything (~500MB) the
  first time.
- `mvn -DskipTests` finishes in ~20s on a warm cache; the Docker COPY
  step is sub-second. A full multi-stage Docker build would be
  3-5 minutes on each tag change.
- Tests still run via `mvn verify` in CI; the demo path doesn't
  duplicate them.

The cost: contributors must have JDK 25 + Maven on the host. The
Makefile already requires this (`check-java` target), and spec §5
mandates it for any CONCLAVE work, so this is a no-op constraint.

### KRaft Kafka with dual listeners

The Kafka container declares two listeners:

- `PLAINTEXT://kafka:29092` — used by services inside the compose
  network (orchestrator, schema-registry).
- `EXTERNAL://localhost:9092` — used by the M9 generators when run
  from the host via `make m9-run-fraud`.

`KAFKA_ADVERTISED_LISTENERS` advertises each per listener so a client
connecting on the EXTERNAL listener gets back a `localhost` broker
endpoint (not `kafka:29092`, which the host can't resolve).

`CLUSTER_ID` is pinned to a fixed base64-22 UUID. Without it, a
re-create of the container against the persisted log dir would fail
with a cluster-id-mismatch error.

### Real Schema Registry, not the mock

Unit tests use `mock://conclave-default` (in-process); the compose
stack runs `confluentinc/cp-schema-registry:7.6.0`. Same major version
as the broker so wire-format compatibility holds. Exposed on host
port 8085 so contributors can curl-inspect schemas without docker exec.

### Shared Postgres database

Both the orchestrator (M6 decisions table) and the baseline service
(M3 baselines table) write to the same `conclave` database under the
same `conclave` user. Each service's `SchemaInitializer` runs
`CREATE TABLE IF NOT EXISTS` for its own tables at startup, so they
coexist without coordination.

Rejected — separate Postgres instances per service: would double
memory + boot time for no real isolation benefit (the test surface
already exercises both schemas in one DB via Testcontainers).

### Health checks chain the boot order

Every infra container exposes a healthcheck (`pg_isready`,
`kafka-broker-api-versions`, `cypher-shell 'RETURN 1'`, `curl
/subjects`). Service containers declare `depends_on:
{service: condition: service_healthy}` so `docker compose up` waits
for infra to be actually serving requests before booting the
CONCLAVE services. Saves the user the "oh I need to retry once
Postgres warms up" footgun.

The agents container doesn't have a meaningful healthcheck (it's a
gRPC server with no HTTP probe); orchestrator depends on it with
`condition: service_started` only. M6's `DeliberationClient` retries
internally via gRPC deadlines.

## Consequences

- `git clone && make demo-fraud` is the canonical first-run experience.
  The Makefile target bundles `demo-build` + `docker compose up -d` +
  a 200-event burst from the generator + a hint about the audit API
  URL.
- The two demos share **one binary set**. Switching from fraud to
  security is `make demo-stop && make demo-security` — no rebuild.
- Acceptance criterion #3 (`make demo-fraud-local`) requires the
  qwen3:8b model (~6 GB) to be pulled on first boot. The Ollama
  sidecar does this in its entrypoint script; subsequent boots
  read from the named volume.
- The Java services don't bundle the Avro schemas — each module
  regenerates from `configs/` at `mvn package` time. The runtime
  jars are self-contained.
- The Schema Registry runs even though tests use `mock://`. Acceptable
  cost (~150 MB image, ~10s boot) for matching production wire format.

## Rejected alternatives

- **Multi-stage Maven build in Docker.** Reproducible, but adds 3-5
  minutes to every iteration. CI already validates the build; the
  demo path doesn't need to recapitulate that.
- **One compose service per domain.** Forking the orchestrator into
  `orchestrator-fraud` + `orchestrator-security` containers would have
  been a literal "one architecture, two domains" violation.
- **Spring Boot Buildpacks.** Cleaner per-jar but couples the demo
  to a buildpack toolchain; the JRE COPY pattern is two lines per
  service.
- **`docker compose profiles`.** Considered for the Ollama vs Anthropic
  split; rejected because overlay files keep the difference visible.

## Smoke-test status

The compose files validate cleanly under
`docker compose config --quiet` and `docker compose -f docker-compose.yml
-f docker-compose.ollama.yml config --quiet`. Full end-to-end boot is
deferred to the next session (it pulls ~2 GB of images on first run and
needs `ANTHROPIC_API_KEY` set). The wiring follows the same patterns
the Testcontainers ITs (M1, M2, M3, M4, M6, M9) already exercise green
— same broker image, same pg image, same Neo4j image, same env-var
shapes the services already read.
