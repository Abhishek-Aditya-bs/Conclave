# CONCLAVE — Progress Tracker & Session Crash-Recovery Log

> **Purpose.** This file is the single source of truth for "where are we, what's done, what's next." A fresh Claude session (or human contributor) reading this file + [spec.md](spec.md) should be able to resume work without re-deriving context. **Append, do not rewrite.** New sessions add a new `## Session N` block at the bottom; the `Current State` and `Next Actions` sections at the top are updated in place.
>
> **Companion file:** [SCRATCHPAD.md](SCRATCHPAD.md) holds in-flight notes, half-formed ideas, and gotchas not yet promoted into the spec or ADRs. Read both when picking up cold.

---

## 🎯 Current State (update on every session)

| Module | Status | Notes |
|---|---|---|
| **Scaffolding** | ✅ done | Maven multi-module aggregator, JaCoCo coverage, CI workflow, profile-startup test framework |
| **M1 — Event Schemas & Ingestion** | ✅ done | Avro schemas (fraud + security), Java producer SDK, Testcontainers integration tests, profile-startup tests |
| **M2 — Feature Extraction Stream Job** | ✅ done | `FeatureSpec` abstraction + shared topology shell, per-domain implementations with stateful velocity counters, Avro-enriched schemas, TopologyTestDriver unit tests + Testcontainers ITs |
| **M3 — Behavioral Baseline Service** | ✅ done | New `baseline/` Maven module. Postgres + pgvector storage, in-JVM langchain4j MiniLM-L6-v2 embeddings (no Python), EMA rolling update, REST + gRPC dual surface, 90-day synthetic-stream IT, **p99 lookup = 0.74ms** (27× under the 20ms budget) |
| **M4 — Graph Reasoner Service** | ✅ done | New `graph/` Maven module. Neo4j 5 storage, 4 fixed Cypher templates (2 per domain), depth-bounded queries, REST + gRPC dual surface, **p99 query = 6ms** on ~100K-edge graph (8× under the 50ms budget). ADR-003 records the schema + template strategy |
| **M5 — LangGraph Deliberation Orchestrator** | ✅ done | New `agents/` Python project (uv-managed). LangGraph state graph: feature → (baseliner ∥ graph_reasoner) → judge with graceful per-node degradation. `JUDGE_LLM_PROVIDER` factory routes between Claude Haiku 4.5 (Anthropic SDK, tool-use structured output) and Ollama (`langchain-ollama`, `format=json`). gRPC server on port 9093. **99 tests, 98% line coverage** (gate: 80%). ADR-004 records the provider-factory rationale |
| **M6 — Decision Orchestrator** | 🟡 not started | |
| **M7 — Audit & Decision API** | 🟡 not started | |
| **M8 — Reference Configurations** | 🟡 partial | fraud + security raw/enriched schemas + feature specs exist; graph templates deferred to M4 |
| **M9 — Synthetic Data Generators** | 🟡 not started | |
| **M10 — Dashboard + Demo Harness** | 🟡 not started | |

**Last green build:** Session 5 (see history below) — **104/104 Java tests + 99/99 Python tests passing across 5 modules** (orchestrator: 34, baseline: 40, graph: 30, agents: 99). Coverage: orchestrator 97%/80%, baseline 99%/92%, graph 94%/88%, agents 98% line (Java threshold: 80%/70% JaCoCo; Python threshold: 80% line pytest-cov, both fail the build below).
**Coverage threshold enforced:** 80% line, 70% branch (JaCoCo); 80% line (pytest-cov).

---

## ▶️ Next Actions (top of the queue for the next agent)

1. **Start M6 — Decision Orchestrator (Java/Spring).** Goes back into `orchestrator/`,
   new package `io.conclave.orchestrator/` (don't reuse the existing
   `io.conclave.ingest` or `io.conclave.stream`). Consumes `events.{domain}.enriched`
   (Kafka Streams topology output from M2), calls **M5 over gRPC**, persists the
   decision to Postgres, emits `decisions.{domain}` topic for downstream consumers.
   - **The M5 gRPC contract is in `agents/proto/deliberation.proto`** — single RPC
     `DeliberationService.Deliberate`. Generate Java stubs in `orchestrator/` from
     a copy of that proto (mirror the pattern the M3/M4 modules use). Generated
     Java package is `io.conclave.deliberation.proto.*`.
   - **JSON-encode the enriched event** when calling M5; the proto field is
     `enriched_event_json`. M5 does not consume Avro. Use Jackson on the Java side.
   - **Failure modes that must degrade gracefully:** M5 timeout, M5 returns
     `INTERNAL`, Postgres unreachable. Don't lose the event — write to a DLQ topic
     `decisions.{domain}.failed`.
   - **p99 budget:** end-to-end (Kafka consume → M5 → Postgres → Kafka emit) must
     stay under ~750ms to leave headroom against the M5 600ms target.
2. **Then M7 — Audit & Decision API.** REST endpoints for browsing decisions, plus
   a `replay` endpoint that re-runs the deliberation on a stored event. Lives in
   `orchestrator/` under a new `io.conclave.audit` package.
3. Update [PROGRESS.md](PROGRESS.md) and commit after each module lands green.
4. Add ADRs under `docs/adr/` for each new abstraction.

**M5 is wired and ready for M6 to consume.** The deliberation server listens on
`localhost:9093` by default (configurable via `DELIBERATION_PORT`). It expects
M3 (`localhost:9091`) and M4 (`localhost:9092`) to be reachable; both targets
are configurable via `BASELINE_SERVICE_TARGET` / `GRAPH_SERVICE_TARGET`.

**Heads up for M6:**
- The `Decision` proto carries `judge_provider` + `judge_model`. **Persist these
  to the decision row** — the audit UI and the paper's benchmark section both
  need them (spec §5 caveat: only Haiku-backed runs are quotable).
- `verdict_explanation_md` is Markdown. The audit table can store it as plain
  TEXT; M10's dashboard renders it.
- `contributing_factors` is a repeated proto message — flatten to a JSONB column
  for query flexibility (filter by factor name, sort by weight).
- The `latency_ms` M5 returns is the **full graph wallclock** the server
  measures, not just the judge LLM time. Trust it for end-to-end p99.

---

## 🛠️ Local Environment Requirements

A fresh contributor needs:
- **JDK 25** — see `.mvn/jvm.config` for the path the build expects. Install via `brew install openjdk@25` on macOS or `sdk install java 25-tem` via SDKMAN.
- **Maven 3.9+**
- **Docker** — required for integration tests (Testcontainers spins up Kafka, Postgres, Neo4j).
- **Python 3.12+** and **uv** — required for M5 (`agents/`). Install uv via `brew install uv` or the [astral installer](https://astral.sh/uv).

Build & test commands:
```bash
mvn verify                         # Java: unit + integration tests + coverage
mvn -pl orchestrator test          # M1 unit tests only (fast)
mvn -pl orchestrator verify        # M1 + integration (needs Docker)
mvn jacoco:report                  # Java coverage HTML in target/site/jacoco/

make m5-install                    # Python: sync deps via uv
make m5-gen-proto                  # Python: regenerate gRPC stubs from agents/proto/
make m5-lint                       # Python: ruff
make m5-test                       # Python: pytest with 80% coverage gate
make m5-coverage                   # Python: open coverage HTML report
make m5-run                        # Python: launch M5 gRPC server on :9093
```

---

## 📂 Repository Layout (current — diverges from spec §10 only where called out)

```
CONCLAVE/
├── spec.md                          # The contract
├── PROGRESS.md                      # This file
├── SCRATCHPAD.md                    # Working notes
├── README.md                        # Public-facing pitch (TODO: written when M10 lands)
├── pom.xml                          # Maven aggregator
├── .mvn/jvm.config                  # Pins build to Java 25
├── .github/workflows/ci.yml         # CI
├── .gitignore
├── configs/
│   ├── fraud/
│   │   ├── schema.avsc                # M1: PaymentEvent
│   │   └── enriched-schema.avsc       # M2: EnrichedPaymentEvent
│   └── security/
│       ├── schema.avsc                # M1: AuthEvent
│       └── enriched-schema.avsc       # M2: EnrichedAuthEvent
├── docs/
│   └── adr/
│       ├── 0001-feature-spec-abstraction.md           # M2 ✅
│       ├── 0002-baseline-storage-and-embedding.md     # M3 ✅
│       ├── 0003-graph-templates-and-schema.md         # M4 ✅
│       └── 0004-judge-llm-provider-factory.md         # M5 ✅
├── orchestrator/                    # Java/Spring service (M1, M2, M6, M7)
│   ├── pom.xml
│   └── src/
│       ├── main/java/io/conclave/
│       │   ├── ingest/                # M1 ✅
│       │   └── stream/                # M2 ✅  (FeatureSpec, FraudFeatureSpec, SecurityFeatureSpec, KafkaStreamsConfig)
│       └── test/java/io/conclave/
│           ├── ingest/                # M1 ✅
│           └── stream/                # M2 ✅
├── baseline/                        # M3 ✅  (Postgres + pgvector, MiniLM in-JVM, REST + gRPC)
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── proto/baseline.proto             # gRPC contract
│       │   ├── java/io/conclave/baseline/
│       │   │   ├── domain/                      # Baseline record
│       │   │   ├── embedding/                   # EmbeddingService + AllMiniLm impl
│       │   │   ├── storage/                     # JdbcBaselineRepository, SchemaInitializer
│       │   │   ├── service/                     # BaselineService (EMA rolling update)
│       │   │   ├── rest/                        # REST controller
│       │   │   ├── grpc/                        # gRPC service impl (BaselineGrpcService)
│       │   │   └── config/                      # BaselineProperties
│       │   └── resources/application.yaml
│       └── test/java/                           # 40 tests, 99% line / 92% branch
├── graph/                           # M4 ✅  (Neo4j 5 + Cypher templates, REST + gRPC)
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── proto/graph.proto                # gRPC contract
│       │   ├── java/io/conclave/graph/
│       │   │   ├── domain/                      # GraphFinding, GraphTemplate iface
│       │   │   ├── template/{fraud,security}/   # 4 fixed Cypher templates
│       │   │   ├── storage/                     # Neo4jConfig, SchemaInitializer
│       │   │   ├── service/                     # GraphReasonerService (registry + latency)
│       │   │   ├── rest/                        # REST controller
│       │   │   ├── grpc/                        # gRPC service impl (GraphGrpcService)
│       │   │   └── config/                      # GraphProperties
│       │   └── resources/application.yaml
│       └── test/java/                           # 30 tests, 94% line / 88% branch
├── agents/                          # M5 ✅  (Python LangGraph deliberation orchestrator, uv-managed)
│   ├── pyproject.toml                          # uv project; ruff + pytest config
│   ├── .python-version                         # pins 3.12
│   ├── README.md
│   ├── proto/                                  # source-of-truth proto contracts (mirrored M3 + M4 + new M5)
│   │   ├── baseline.proto
│   │   ├── graph.proto
│   │   └── deliberation.proto                  # M5 gRPC contract
│   ├── scripts/gen_protos.sh                   # regenerates Python stubs via grpc_tools.protoc
│   ├── deliberation/
│   │   ├── _proto/                             # generated stubs (gitignored at file granularity)
│   │   ├── state.py                            # DeliberationState TypedDict + Decision dataclass
│   │   ├── llm/                                # LLMProvider factory (anthropic + ollama)
│   │   ├── clients/                            # gRPC clients for M3 + M4
│   │   ├── nodes/                              # feature, baseliner, graph_reasoner, judge
│   │   ├── graph.py                            # LangGraph wiring: feature → (baseliner ∥ graph_reasoner) → judge
│   │   └── server/                             # gRPC server for DeliberationService
│   └── tests/                                  # 99 tests, 98% line coverage
├── generators/                      # M9 — not yet created
├── dashboard/                       # M10 — not yet created
├── website/                         # M10 — not yet created
└── benchmark/                       # not yet created
```

---

## ⚠️ Known Gotchas / Open Questions

- **Spring Boot 4.0 + Java 25** is a recent combination — if a transitive dependency breaks, check the Spring Boot 4.0.6 release notes before assuming our code is wrong.
- **Testcontainers + Apple Silicon:** images must support arm64. We pin `confluentinc/cp-kafka:7.6.0` (multi-arch).
- The spec locks the **Ollama path** as opt-in for M5 (see spec §5 "Local-only mode"). Don't ship benchmark numbers from the Ollama backend.
- The judge LLM default is `claude-haiku-4-5-20251001` — DO NOT silently swap to Sonnet or Opus.

---

## 📜 Session History (append a new block per session)

### Session 1 — 2026-05-25 — Scaffolding + M1 landed
**Agent:** Claude Opus 4.7
**Started from:** Empty repo with just [spec.md](spec.md). No prior commits in CONCLAVE.
**Pre-work this session (before any code):**
- Spec edits: locked Spring Boot 4.0, judge model = Haiku 4.5, Vite + React stack (Cloudflare Pages), added Ollama opt-in path. See spec §5 and §11.

**Delivered:**
- Initialized CONCLAVE as its own git repo (`git init -b main`).
- Installed Java 25 via Homebrew (`openjdk@25` keg-only at `/opt/homebrew/opt/openjdk@25`), pinned all build invocations to it via the `Makefile`'s `JAVA_HOME` export.
- Root `pom.xml` as Maven aggregator with **Spring Boot 4.0.6** BOM, Java 25 target, **JaCoCo (80% line / 70% branch threshold, fails build below)**, Surefire (unit) + Failsafe (integration) split, Avro Maven plugin reading from `configs/`, maven-enforcer-plugin hard-failing on Java != 25.
- CI workflow `.github/workflows/ci.yml`: `mvn verify` on push/PR, uploads JaCoCo HTML coverage as artifact, uploads failed Surefire/Failsafe reports on failure.
- M1 — Event Schemas and Ingestion (`io.conclave.ingest`):
  - [configs/fraud/schema.avsc](configs/fraud/schema.avsc) — PaymentEvent, 15 fields, every field doc'd.
  - [configs/security/schema.avsc](configs/security/schema.avsc) — AuthEvent, 11 fields, every field doc'd.
  - Java sources in [orchestrator/src/main/java/io/conclave/](orchestrator/src/main/java/io/conclave/):
    - `ConclaveApplication` — Spring Boot main, profile-aware via `SPRING_PROFILES_ACTIVE`.
    - `ingest/EventDomain` — enum carrying topic-naming convention (`events.{domain}.raw|.enriched`, `decisions.{domain}`).
    - `ingest/IngestProperties` — `@ConfigurationProperties("conclave.ingest")` record with fail-fast validation.
    - `ingest/EventProducer` — public producer SDK interface.
    - `ingest/KafkaEventProducer` — Avro-backed `KafkaTemplate` impl, keys by `eventId`.
    - `ingest/TopicConfig` — declares `NewTopic` bean; Spring Kafka's `KafkaAdmin` auto-creates the topic on startup.
    - `ingest/KafkaProducerConfig` — typed `KafkaTemplate<String, SpecificRecord>` + `ProducerFactory` (needed because Spring Boot's auto-config bean is `<?, ?>`).
  - `application.yaml` + `application-fraud.yaml` + `application-security.yaml`.
- Tests (20 tests total, **all green**):
  - **Unit** (`AvroRoundTripTest`, 5 tests): pure Avro binary encode/decode for both event types, including optional-field null handling and a "missing required field rejected" case.
  - **Unit** (`KafkaEventProducerUnitTest`, 6 tests): Mockito-mocked `KafkaTemplate`, verifies routing to `events.fraud.raw` vs `events.security.raw`, key extraction, null-event rejection, `IngestProperties` validation, `EventDomain` topic-naming convention.
  - **Integration** (`KafkaProducerIT`, 1 test): Testcontainers `cp-kafka:7.6.0` in KRaft mode, publishes 1000 PaymentEvents through the producer SDK, consumes them back via raw `KafkaConsumer` with Confluent `mock://` schema registry, asserts every event ID is preserved.
  - **Profile-startup** (`ProfileStartupFraudIT` + `ProfileStartupSecurityIT`, 4 tests each): `@SpringBootTest` with `@ActiveProfiles("fraud")` / `@ActiveProfiles("security")`, asserts the right domain binds, the right `NewTopic` bean is declared, the topic actually exists on the broker after startup, and a single end-to-end produce-and-consume round-trip works in each profile.
- `mvn verify` green. **Final coverage: 87% line / 80% branch** on the M1 code path (excluding Avro-generated classes per JaCoCo `<excludes>`).
- Surfaced gotchas in [SCRATCHPAD.md](SCRATCHPAD.md):
  - Spring Boot 4 moved `KafkaProperties` from `org.springframework.boot.autoconfigure.kafka` to `org.springframework.boot.kafka.autoconfigure` (Spring Boot 4 modularization) — and `buildProducerProperties()` is now no-arg, not `(SslBundles)`.
  - Required dependency switched from `org.springframework.kafka:spring-kafka` (Spring Boot 3 idiom) to `org.springframework.boot:spring-boot-starter-kafka` (Spring Boot 4 idiom — same starter name, different transitive layout).
  - `KafkaTemplate<?, ?>` auto-config bean does NOT satisfy a parameterized `KafkaTemplate<String, SpecificRecord>` injection under Spring 6+; we declare our own pair (`avroProducerFactory` + `kafkaTemplate`) in `KafkaProducerConfig` to keep the producer code type-safe.
  - Avro Maven plugin with default settings generates `Instant` (JSR310) for `timestamp-millis` logical type, not `long`. Use `Instant.now()` not `Instant.now().toEpochMilli()`.

**Handoff for Session 2:** Start at M2. See "Next Actions" above. The `FeatureSpec` interface is the key abstraction — make it shape-able for both domains' feature sets. Watch out for the Spring Boot 4 package moves (more autoconfigure-package renames very likely lurking).

### Session 2 — 2026-05-25 — M2 landed (Feature Extraction Stream Job)
**Agent:** Claude Opus 4.7
**Started from:** Session 1's commit `ce5a96d`. M1 fully landed, ready for M2.

**Delivered:**
- Enriched Avro schemas:
  - [configs/fraud/enriched-schema.avsc](configs/fraud/enriched-schema.avsc) — EnrichedPaymentEvent carries raw fields forward + `cardholderVelocity`, `binRiskScore`, `baselineEntityId`, `graphEntityIds`, `featureExtractedAt`.
  - [configs/security/enriched-schema.avsc](configs/security/enriched-schema.avsc) — EnrichedAuthEvent + `principalVelocity`, `failedLoginsRecent`, `baselineEntityId`, `graphEntityIds`, `featureExtractedAt`.
  - Avro plugin include pattern changed from `**/schema.avsc` to `**/*.avsc` so new schema files get auto-picked-up.
- `io.conclave.stream` package:
  - `FeatureSpec<R, E>` — generic interface for the per-domain enrichment contract.
  - `FeatureExtractionTopology` — static builder that takes a `FeatureSpec` and constructs the full Kafka Streams `Topology` (the shared shell).
  - `FraudFeatureSpec` (`@Profile("fraud")`) — stateful enrichment via a `FixedKeyProcessor` + persistent `KeyValueStore<String, Long>` for cardholder velocity.
  - `SecurityFeatureSpec` (`@Profile("security")`) — two state stores (total + failed-only) keyed by principalId.
  - `KafkaStreamsConfig` — builds the topology, creates the `KafkaStreams` bean, starts it on `ApplicationReadyEvent`.
- `TopicConfig` extended to declare the enriched topic in addition to the raw topic.
- `application.yaml` restructured: `spring.kafka.properties.*` now applies to producer, consumer, AND streams clients. `spring.kafka.streams.state-dir` defaults to `./target/kafka-streams-state`.
- Dependencies added: `org.apache.kafka:kafka-streams`, `io.confluent:kafka-streams-avro-serde`. The Spring Boot 4 starter `spring-boot-starter-kafka` does NOT bring Kafka Streams — has to be explicit.
- Tests (14 new — total now 34, all green):
  - **Unit** ([FraudFeatureSpecTest](orchestrator/src/test/java/io/conclave/stream/FraudFeatureSpecTest.java), 6 tests): `TopologyTestDriver`, no broker. Verifies velocity counter increments per cardholder, all raw fields propagate, BIN risk is deterministic, 100-event pump produces 100 enriched events.
  - **Unit** ([SecurityFeatureSpecTest](orchestrator/src/test/java/io/conclave/stream/SecurityFeatureSpecTest.java), 5 tests): mirror for AuthEvent + verifies failed-login count only ticks on non-SUCCESS results.
  - **Integration** ([FeatureExtractionFraudIT](orchestrator/src/test/java/io/conclave/stream/FeatureExtractionFraudIT.java), 2 tests): Testcontainers Kafka, 1000-event round-trip + 2000-event burst.
  - **Integration** ([FeatureExtractionSecurityIT](orchestrator/src/test/java/io/conclave/stream/FeatureExtractionSecurityIT.java), 1 test): 200 mixed SUCCESS/FAILURE events.
- ADR-001 ([docs/adr/0001-feature-spec-abstraction.md](docs/adr/0001-feature-spec-abstraction.md)) — the FeatureSpec abstraction, with rejected alternatives.
- `mvn verify` green. **97% line / 90% branch** coverage on the M1 + M2 code path (up from M1's 87%/80%).

**Build settings changed:**
- Failsafe now uses `forkCount=1` + `reuseForks=false` so each IT class runs in its own fresh JVM. Required because M2 added `KafkaStreams` to the main code path, and re-using one JVM across multiple `@SpringBootTest` classes caused stale connections and "Kafka Send failed" flakes on the second IT class. Tradeoff: full `mvn verify` now takes ~2:40 (was ~25s).

**New gotchas surfaced in [SCRATCHPAD.md](SCRATCHPAD.md):**
- Spring Boot 4 `KafkaProperties` lives in `org.springframework.boot.kafka.autoconfigure` (M1 found this); for streams, `buildStreamsProperties()` is no-arg.
- `kafka-streams` is NOT pulled in by `spring-boot-starter-kafka`; needs explicit dependency.
- `KafkaStreams` constructor expects `Properties`, not `Map`.
- `SpecificAvroSerde` lives in `io.confluent:kafka-streams-avro-serde`, separate artifact from `kafka-avro-serializer`.
- Avro `timestamp-millis` generates `Instant`-typed getters/setters by default.
- Per-IT-class JVM forking is non-negotiable once `KafkaStreams` runs in the main context — shared JVM accumulates connection state across `@SpringBootTest` classes.
- IT-level velocity-counter assertions are intrinsically flaky due to Streams' at-least-once semantics + RocksDB checkpoint cadence. Use `TopologyTestDriver` for deterministic counter logic checks (unit tests do this); ITs assert "events arrived and counters advanced > 0" only.

**Handoff for Session 3:** Start at M3 (Behavioral Baseline Service). See "Next Actions" above. Note: M3 is in a NEW Maven module (`baseline/`), not in `orchestrator/`. The grpc-spring-boot-starter community fork may or may not exist for Spring Boot 4 yet — check before committing to the binding approach.

### Session 3 — 2026-05-25 — M3 landed (Behavioral Baseline Service)
**Agent:** Claude Opus 4.7
**Started from:** Session 2's commit `e504857`.

**Delivered:**
- New `baseline/` Maven module — second sibling of `orchestrator/`. Build, JaCoCo coverage,
  and the Spring Boot 4 starter for gRPC all wired into the existing root pom.
- gRPC contract — [baseline.proto](baseline/src/main/proto/baseline.proto) with two RPCs
  (`GetBaseline`, `UpdateBaseline`) + `Baseline` and `NotFound` messages. Generated stubs
  land in `io.conclave.baseline.proto.*` (excluded from JaCoCo coverage so the threshold
  doesn't dilute against generated code).
- Java sources in [baseline/src/main/java/io/conclave/baseline/](baseline/src/main/java/io/conclave/baseline/):
  - `BaselineApplication` — Spring Boot main, REST on 8081, gRPC on 9091.
  - `domain/Baseline` — record with custom `equals`/`hashCode`/`toString` because the
    `float[]` field doesn't work with the default record-derived equality.
  - `embedding/EmbeddingService` + `AllMiniLmEmbeddingService` — langchain4j-backed
    in-JVM model. 384-dim, ~100ms cold start, sub-ms warm.
  - `storage/JdbcBaselineRepository` — `JdbcTemplate` over Postgres + pgvector. Manual
    vector text-format (de)serialization to avoid registering custom JDBC types on
    HikariCP.
  - `storage/SchemaInitializer` — idempotent `CREATE EXTENSION vector` + `CREATE TABLE
    IF NOT EXISTS baselines (...)` on startup.
  - `service/BaselineService` — orchestrates embed → EMA-fold → persist. Decay factor
    configurable via `conclave.baseline.ema-decay`.
  - `rest/BaselineController` — `GET` and `POST` under `/api/v1/baselines/{domain}/{entityId}`.
  - `grpc/BaselineGrpcService` — implements the generated stub via the modern
    `@GrpcService` annotation from Spring's official `spring-grpc-spring-boot-starter` 1.0.2.
  - `config/BaselineProperties` — record with fail-fast validation.
- ADR-002 ([docs/adr/0002-baseline-storage-and-embedding.md](docs/adr/0002-baseline-storage-and-embedding.md))
  documents storage / embedding / EMA decisions and rejected alternatives.
- Tests — **40 total, all green**:
  - **Unit** (28): `BaselineServiceTest` (7 — EMA math, dimension-mismatch fail-fast,
    save-exactly-once), `AllMiniLmEmbeddingServiceTest` (4 — real model, deterministic,
    discriminative, 384-dim), `JdbcBaselineRepositoryTest` (5 — vector format/parse
    round-trip), `BaselineTest` (6 — record equals/hashCode/toString), `BaselinePropertiesTest`
    (6 — validation edge cases).
  - **Integration** (12, Testcontainers pgvector + Spring): `BaselineRepositoryIT` (4 —
    save/find/upsert/independent-domains), `BaselineRestIT` (4 — REST round-trip +
    **90-day synthetic stream** showing EMA converges to per-entity patterns),
    `BaselineGrpcIT` (3 — gRPC client end-to-end), **`BaselineLatencyIT` (1 — p99
    measurement)**.
- **p99 lookup latency = 0.74ms** measured against 10K seeded baselines / 1000 random
  reads on Apple M3. Spec budget is 20ms; we're 27× under.
- Coverage: **99% line / 92% branch** on the baseline module (excluding generated proto
  classes via root-pom JaCoCo `<excludes>`).

**Build settings changed:**
- Root pom: `protobuf-java.version` and `grpc.version` properties exposed at root level
  so they're visible to the `protobuf-maven-plugin`. Mirror what `spring-grpc-dependencies`
  imports — bump together when bumping spring-grpc.
- JaCoCo plugin moved its `<excludes>` to the top-level `<configuration>` block (was
  inside the `check` rule). The check-level exclude only filters which BUNDLEs/PACKAGEs
  are checked, NOT which classes count toward coverage. The agent-level exclude is the
  right place. Now excludes `io/conclave/events/**/*` (Avro) and
  `io/conclave/baseline/proto/**/*` (protobuf).

**New gotchas surfaced in [SCRATCHPAD.md](SCRATCHPAD.md):**
- `TestRestTemplate` is **gone** in Spring Boot 4. Use `RestClient` directly with
  `@LocalServerPort`.
- Spring Boot 4 requires `@Autowired` on the production constructor of a `@Service`
  when there are multiple constructors (e.g., one with `Clock` for tests). Otherwise
  Spring throws "No default constructor found".
- JaCoCo: `<excludes>` inside `<rule>` doesn't do what you want for filtering generated
  classes out of coverage. Use the top-level `<configuration>/<excludes>`.
- Generated protobuf classes go in whichever package `java_package` declares. Put them
  in a package distinct from your handwritten gRPC service so the JaCoCo exclude is
  trivial.

**Handoff for Session 4:** Start at M4 (Graph Reasoner Service). See "Next Actions" above. The protobuf + multi-arch Testcontainers patterns from M3 apply directly — Neo4j publishes a multi-arch image too. Watch for more Spring Boot 4 modularization surprises; document any new ones in SCRATCHPAD.md.

### Session 4 — 2026-05-25 — M4 landed (Graph Reasoner Service)
**Agent:** Claude Opus 4.7
**Started from:** Session 3's commit `47c8392`.

**Delivered:**
- New `graph/` Maven module — third sibling. Spring Boot 4 app, REST on 8082 + gRPC on 9092.
- gRPC contract — [graph.proto](graph/src/main/proto/graph.proto) with `ListTemplates`
  + `ExecuteTemplate` RPCs. Generated stubs in `io.conclave.graph.proto.*` (excluded
  from JaCoCo).
- **Four Cypher templates**, all depth-bounded, all with structured `GraphFinding`
  output:
  - Fraud:
    - [FraudCardTestingRingTemplate](graph/src/main/java/io/conclave/graph/template/fraud/FraudCardTestingRingTemplate.java)
      — device → cardholder/card counts; risk fires above 3 cardholders.
    - [FraudCardholderNeighborhoodTemplate](graph/src/main/java/io/conclave/graph/template/fraud/FraudCardholderNeighborhoodTemplate.java)
      — 1-2 hop neighborhood; descriptive context, no risk signal.
  - Security:
    - [SecurityLateralMovementTemplate](graph/src/main/java/io/conclave/graph/template/security/SecurityLateralMovementTemplate.java)
      — principal → distinct host count; risk fires above 5 hosts.
    - [SecurityPrivilegedAccessTemplate](graph/src/main/java/io/conclave/graph/template/security/SecurityPrivilegedAccessTemplate.java)
      — sensitive-resource access; any access raises a flag.
- Domain: `GraphFinding` record (templateName, rootEntityId, domain, attributes,
  riskSignal, queryLatencyMs); `GraphTemplate` interface; `GraphReasonerService`
  builds a name → template registry from autowired `List<GraphTemplate>` + stamps
  latency around every call.
- Storage: raw `neo4j-java-driver` (no Spring Data Neo4j, same logic as M3's
  JdbcTemplate choice). `Neo4jConfig` wires the `Driver` bean; `SchemaInitializer`
  creates 8 indexes (5 fraud, 3 security) on startup.
- REST + gRPC surfaces both backed by the same service bean.
- [ADR-003](docs/adr/0003-graph-templates-and-schema.md) records the schema, the
  fixed-template strategy (rejecting LLM-generated Cypher), and the raw-driver
  choice.
- Tests — **30 total, all green**:
  - **Unit** (13): `GraphFindingTest` (5 — record validation, withLatency),
    `GraphPropertiesTest` (4 — validation edge cases),
    `GraphReasonerServiceTest` (4 — registry lookup, duplicate-name rejection,
    template-not-found, latency stamping).
  - **Integration** (17, Testcontainers Neo4j + full Spring): `FraudTemplatesIT`
    (4 — seeded ring detection, normal-device safe, unknown-device empty,
    neighborhood bounded at 2 hops), `SecurityTemplatesIT` (5 — lateral
    movement detected, normal-user safe, unknown-principal empty, privileged
    access raises flag, normal user no flag), `GraphRestIT` (4 — REST endpoint
    smoke tests), `GraphGrpcIT` (3 — gRPC client end-to-end + NotFound branch),
    **`GraphLatencyIT` (1 — p99 measurement on 100K-edge graph)**.
- **p99 query latency = 6ms** on a graph with 5,000 cardholders × 5,000 devices
  × 100,000 USED_DEVICE relationships (Apple M3). Spec budget is 50ms; we're 8×
  under. p50=3ms, p95=4ms, max=7ms.
- Coverage: **94% line / 88% branch** on the graph module (excluding generated
  proto via the same JaCoCo exclude pattern as M3).

**Build settings changed:**
- Spring Boot 4.0.6 manages `neo4j-java-driver` at version **6.0.5** (not 5.x) and
  expects a `neo4j-java-driver-bom` artifact. Trying to override the version in
  the root pom broke the build because no BOM exists for arbitrary versions —
  removed the override; Spring Boot's managed version is used. Documented in
  SCRATCHPAD.
- Added Jackson `jackson-databind` as an explicit dep in `graph/pom.xml`
  because Spring Boot 4's modularized `spring-boot-starter-web` no longer brings
  it transitively in every layout. The `ObjectMapper` is also no longer
  auto-registered as a bean — `GraphGrpcService` instantiates one directly
  rather than depending on the missing auto-config.
- Root pom JaCoCo `<excludes>` now includes `io/conclave/graph/proto/**/*` in
  addition to the M3 baseline/proto pattern.

**New gotchas surfaced in [SCRATCHPAD.md](SCRATCHPAD.md):**
- Spring Boot 4 manages neo4j-java-driver as `6.0.x` — don't override the version
  in the root pom unless you also override the BOM coordinates.
- `ObjectMapper` is NOT auto-registered with just `spring-boot-starter-web` in
  Spring Boot 4. Either add a Jackson starter or instantiate directly.
- Cypher `*1..2` means "exactly 1 or 2 hops" — three-hop targets (cards owned by
  neighbors-of-neighbors) are NOT included. Test assertions need to reflect the
  bound.

**Handoff for Session 5:** Start at M5 (Python LangGraph deliberation orchestrator). See "Next Actions" above. The two service stubs are wired and waiting. Read spec §5 "Local-only mode (Ollama)" and rule #6 about the judge model BEFORE writing code — the `JUDGE_LLM_PROVIDER` factory is the central abstraction.

### Session 5 — 2026-05-25 — M5 landed (LangGraph Deliberation Orchestrator)
**Agent:** Claude Opus 4.7
**Started from:** Session 4's commit `c49cc95`.

**Delivered:**
- New `agents/` Python project — first non-Maven module in the repo. uv-managed,
  Python 3.12+, ruff + pytest pre-wired.
- gRPC contract — [agents/proto/deliberation.proto](agents/proto/deliberation.proto)
  with one RPC (`Deliberate`). Mirrors spec §6 M5 output: `Decision { score [0,1],
  verdict_label, verdict_explanation_md, contributing_factors[], latency_ms,
  judge_provider, judge_model }`. The M3 + M4 protos are vendored alongside it so
  the Python side regenerates them itself via
  [scripts/gen_protos.sh](agents/scripts/gen_protos.sh).
- LangGraph state graph
  ([agents/deliberation/graph.py](agents/deliberation/graph.py)):
  `feature → (baseliner ∥ graph_reasoner) → judge`. Parallel fanout, AND-join,
  list-concat reducer on `errors` so partial failures don't clobber sibling
  branches.
- Nodes:
  - [feature.py](agents/deliberation/nodes/feature.py) — domain-aware
    summarization of the enriched event (fraud + security branches).
  - [baseliner.py](agents/deliberation/nodes/baseliner.py) — calls M3 over
    gRPC, degrades gracefully on RPC errors or empty entity IDs.
  - [graph_reasoner.py](agents/deliberation/nodes/graph_reasoner.py) — picks a
    per-domain template (fraud_card_testing_ring rooted on device fingerprint;
    security_lateral_movement rooted on principal), calls M4 over gRPC.
  - [judge.py](agents/deliberation/nodes/judge.py) — calls the LLM provider;
    falls back to a deterministic heuristic if the provider can't return a
    parseable Decision.
- LLMProvider factory ([provider.py](agents/deliberation/llm/provider.py)):
  - `JUDGE_LLM_PROVIDER=anthropic` (default) → `AnthropicProvider` with
    `claude-haiku-4-5-20251001` (spec rule #6 lock), tool-use structured
    output, prompt-caching opted in.
  - `JUDGE_LLM_PROVIDER=ollama` → `OllamaProvider` with `langchain-ollama`
    `ChatOllama`, `format="json"`, default model `qwen3:8b`. Lazy imports so
    the Anthropic path doesn't pull in `langchain-ollama` and vice versa.
  - Shared `parse_decision_payload` + `derive_fallback_decision` mean both
    backends produce identical `JudgeOutput` shape and the judge node doesn't
    branch on backend.
- gRPC server ([server/service.py](agents/deliberation/server/service.py))
  exposes `DeliberationService.Deliberate` on port 9093 (configurable). Reads
  env vars for backend selection + M3/M4 targets; entrypoint at
  `python -m deliberation.server.entrypoint`.
- Tests — **99 total, all green** (`make m5-test`):
  - **State/types** (15): `test_state.py` covers `VerdictLabel.from_score`
    thresholds, Decision validation, the `Annotated[list[str], operator.add]`
    reducer on `errors`.
  - **Provider factory** (9): env-var dispatch, lock-model defaults, missing-key
    rejection, base-URL override, case-insensitive provider names.
  - **Anthropic provider** (4): tool-use happy path, APIError wrapping,
    missing tool_use block, non-dict tool input. SDK fully mocked.
  - **Ollama provider** (5): string / list / invalid / non-object content,
    client exception wrapping. ChatOllama fully mocked.
  - **Provider parser** (14): every failure mode in `parse_decision_payload`
    (out-of-range, missing fields, unknown labels, malformed factors), every
    branch in `derive_fallback_decision`, every branch in `build_user_prompt`,
    `to_decision` metadata stamping.
  - **Feature node** (5): fraud + security event summarization, geo-split
    bullet only when countries differ, malformed-JSON degrades to error
    entry, unknown-domain fallback.
  - **Baseliner node** (5): no-client skip, happy path, empty-entity short
    circuit, gRPC error → graceful, unexpected exception → graceful.
  - **Graph reasoner node** (8): no-client skip, fraud roots on device,
    security roots on principal, unknown domain, no entity IDs, template not
    registered, gRPC error, unexpected exception.
  - **Judge node** (3): provider happy path, LLMProviderError → fallback,
    missing-feature-summary raises.
  - **Baseline client** (6): translates baseline response, translates NotFound
    to cold-start, passes timeout to stub, RpcError propagation, channel
    ownership semantics.
  - **Graph client** (6): finding with JSON attributes, TemplateNotFound,
    empty attributes string, invalid JSON preserved, RpcError, channel close.
  - **LangGraph end-to-end** (3): Anthropic backend fraud → BLOCK, Ollama
    backend security → REVIEW, both services down still returns Decision via
    fallback path.
  - **gRPC server** (5): proto round-trip, happy-path Deliberate call,
    missing event_id rejected, invalid domain rejected, graph crash →
    INTERNAL, missing decision → INTERNAL. Uses a real in-process gRPC
    server bound to an OS-assigned free port.
- ADR-004 ([docs/adr/0004-judge-llm-provider-factory.md](docs/adr/0004-judge-llm-provider-factory.md))
  records the `LLMProvider` strategy, why the Claude Agent SDK wrapper was
  rejected (Agent SDK is for multi-turn loops; bare `anthropic` SDK is simpler
  for a single structured call and bills against the same Max-20x credit
  pool because billing is endpoint-driven), why a unified mega-provider was
  rejected, and why `langchain`'s `with_structured_output` wasn't used for
  both backends.
- **Python coverage: 98% line** across 17 modules / 577 statements (gate:
  80% line via `pytest-cov --cov-fail-under=80` in `pyproject.toml`).
- Makefile additions: `m5-install`, `m5-gen-proto`, `m5-lint`, `m5-test`,
  `m5-coverage`, `m5-run`. `m5-test` depends on `m5-gen-proto` so a fresh
  clone bootstraps cleanly. The help target's awk regex now accepts digits
  in target names so `m1-*` and `m5-*` show up in `make help`.
- CI: split `ci.yml` into two parallel jobs — `java` (unchanged) and
  `python` (uv setup + codegen + ruff + pytest). The codegen step doubles as
  a "is `agents/proto/` in sync with `baseline/`+`graph/`?" check because the
  vendored copies would diverge silently otherwise.

**Build settings changed:**
- `.gitignore` now ignores `agents/deliberation/_proto/*_pb2.py`,
  `*_pb2.pyi`, `*_pb2_grpc.py` — generated code regenerated on every
  install via `scripts/gen_protos.sh`. The committed `__init__.py` in the
  same directory carries the `sys.path` shim that makes the flat-namespace
  protoc imports resolve.
- Root `Makefile` `help` target's awk pattern widened from `[a-zA-Z_-]+`
  to `[a-zA-Z0-9_-]+` so module-numbered targets (`m1-test`, `m5-install`)
  appear in `make help`. Pre-existing latent bug: `m1-*` targets weren't
  showing up either.

**New gotchas surfaced in [SCRATCHPAD.md](SCRATCHPAD.md):**
- `grpc_tools.protoc` emits flat-namespace imports — solved with a committed
  `__init__.py` that adds the directory to `sys.path`.
- Anthropic structured output is tool-use only (no `response_format=json_schema`
  yet); the `tool_choice` keyword forces single-tool dispatch.
- Anthropic prompt-caching needs explicit `cache_control` on the system block.
- `ChatOllama(format="json")` is the lowest-common-denominator for Ollama
  structured output; embed the schema in the prompt and validate post-hoc.
- LangGraph parallel fanout = two `add_edge(source, ...)` calls; AND-join is
  automatic when parallel branches write disjoint state keys.
- `TypedDict(total=False)` + `Annotated[list[str], operator.add]` reducer is
  the right pattern for partial-update state with parallel branches.
- `MagicMock(spec=Client)` constrains tests to the real class's API so typos
  fail loudly instead of silently auto-stubbing.
- `StrEnum` (Python 3.11+) is the right base for verdict labels.

**Handoff for Session 6:** Start at M6 (Decision Orchestrator). See "Next
Actions" above. The M5 gRPC server is wired on port 9093 — M6 generates Java
stubs from a copy of `agents/proto/deliberation.proto` (mirror the pattern
M3/M4 modules use). The `judge_provider` + `judge_model` fields are on the
Decision proto and MUST be persisted to the decision row — the audit UI and
the paper both depend on them (spec §5 caveat: only Haiku-backed runs are
quotable). Latency budget for the M6 hop end-to-end is ~750ms; M5 already
holds itself to 600ms on the Anthropic path.
