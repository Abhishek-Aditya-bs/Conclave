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
| **M2 — Feature Extraction Stream Job** | 🟡 not started | Picks up next session |
| **M3 — Behavioral Baseline Service** | 🟡 not started | |
| **M4 — Graph Reasoner Service** | 🟡 not started | |
| **M5 — LangGraph Deliberation Orchestrator** | 🟡 not started | Python sidecar |
| **M6 — Decision Orchestrator** | 🟡 not started | |
| **M7 — Audit & Decision API** | 🟡 not started | |
| **M8 — Reference Configurations** | 🟡 partial | fraud + security schemas exist; feature/graph configs deferred to M2/M4 |
| **M9 — Synthetic Data Generators** | 🟡 not started | |
| **M10 — Dashboard + Demo Harness** | 🟡 not started | |

**Last green build:** Session 1 (see history below) — 20/20 tests passing, **87% line coverage, 80% branch coverage** (threshold: 80%/70%, fails the build below).
**Coverage threshold enforced:** 80% line, 70% branch (JaCoCo, fails the build below).

---

## ▶️ Next Actions (top of the queue for the next agent)

1. **Start M2 — Feature Extraction Stream Job.** Read [spec.md](spec.md) §6 M2 contract. Implement a Kafka Streams topology in `orchestrator/` under `io.conclave.stream/` that:
   - Reads from `events.{domain}.raw` (input topic from M1)
   - Computes features per `FeatureSpec` interface (one impl per domain)
   - Writes to `events.{domain}.enriched`
   - Has a Testcontainers integration test that publishes 1000 raw events and asserts 1000 enriched events appear with the expected feature fields.
2. Update [PROGRESS.md](PROGRESS.md) and commit after each module lands green.
3. Before merging M2: add an ADR under `docs/adr/` describing the FeatureSpec abstraction.

**Do NOT start M5 (Python) until M3 and M4 are at least scaffolded** — M5's gRPC contract depends on them.

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
│   ├── fraud/schema.avsc            # M1: PaymentEvent
│   └── security/schema.avsc         # M1: AuthEvent
├── orchestrator/                    # Java/Spring service (M1, M2, M6, M7)
│   ├── pom.xml
│   └── src/
│       ├── main/java/io/conclave/ingest/      # M1 ✅
│       └── test/java/io/conclave/ingest/      # M1 ✅
├── baseline/                        # Java baseline service (M3) — not yet created
├── graph/                           # Java graph reasoner (M4) — not yet created
├── agents/                          # Python LangGraph (M5) — not yet created
├── generators/                      # M9 — not yet created
├── dashboard/                       # M10 — not yet created
├── website/                         # M10 — not yet created
├── docs/                            # ADRs — not yet created
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
