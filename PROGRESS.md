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
| **M4 — Graph Reasoner Service** | 🟡 not started | |
| **M5 — LangGraph Deliberation Orchestrator** | 🟡 not started | Python sidecar |
| **M6 — Decision Orchestrator** | 🟡 not started | |
| **M7 — Audit & Decision API** | 🟡 not started | |
| **M8 — Reference Configurations** | 🟡 partial | fraud + security raw/enriched schemas + feature specs exist; graph templates deferred to M4 |
| **M9 — Synthetic Data Generators** | 🟡 not started | |
| **M10 — Dashboard + Demo Harness** | 🟡 not started | |

**Last green build:** Session 3 (see history below) — **74/74 tests passing across 3 modules** (orchestrator: 34, baseline: 40), orchestrator coverage 97%/80%, baseline coverage 99%/92% (threshold: 80%/70%, fails the build below).
**Coverage threshold enforced:** 80% line, 70% branch (JaCoCo, fails the build below).

---

## ▶️ Next Actions (top of the queue for the next agent)

1. **Start M4 — Graph Reasoner Service.** Read [spec.md](spec.md) §6 M4 contract.
   - Create a new Maven module `graph/` (sibling of `orchestrator/` and `baseline/`).
   - Wraps Neo4j with a fixed set of Cypher templates per domain
     (`fraud_neighborhood`, `auth_lateral_paths`, etc.).
   - Returns structured `GraphFinding` records.
   - Depth-bounded queries, latency-bounded.
   - Done means: templates documented, unit tests against an in-memory Neo4j seed,
     p99 query < 50ms on a 1M-edge graph.
   - Use the same pattern as M3: REST + gRPC dual surface, ADR-003 for the
     graph schema decisions.
2. **Then M5 — LangGraph Deliberation Orchestrator (Python).** New `agents/` directory,
   different language. See spec §5 + the Ollama opt-in path in §5 "Local-only mode".
3. Update [PROGRESS.md](PROGRESS.md) and commit after each module lands green.
4. Add ADRs under `docs/adr/` for each new abstraction.

**M3 service is wired and ready to integrate.** The judge agent (M5) consumes baselines
via the gRPC stub in `io.conclave.baseline.proto`. The protobuf contract is the source
of truth for cross-module integration — don't reach into the REST controller or service
layer from another module.

**Heads up for M4:** Neo4j Testcontainers also publishes a multi-arch image
(`neo4j:5-community`). Same Apple-Silicon-safe pattern as pgvector.

---

## 🛠️ Local Environment Requirements

A fresh contributor needs:
- **JDK 25** — see `.mvn/jvm.config` for the path the build expects. Install via `brew install openjdk@25` on macOS or `sdk install java 25-tem` via SDKMAN.
- **Maven 3.9+**
- **Docker** — required for integration tests (Testcontainers spins up Kafka, Postgres, Neo4j).
- **Python 3.12+** — only needed when working on M5.

Build & test commands:
```bash
mvn verify                         # unit + integration tests + coverage
mvn -pl orchestrator test          # M1 unit tests only (fast)
mvn -pl orchestrator verify        # M1 + integration (needs Docker)
mvn jacoco:report                  # generate coverage HTML in target/site/jacoco/
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
│       └── 0002-baseline-storage-and-embedding.md     # M3 ✅
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
├── graph/                           # Java graph reasoner (M4) — not yet created
├── agents/                          # Python LangGraph (M5) — not yet created
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
