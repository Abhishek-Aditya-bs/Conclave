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
| **M6 — Decision Orchestrator** | ✅ done | New `io.conclave.orchestrator` package inside the existing `orchestrator/` module. @KafkaListener on `events.{domain}.enriched`, calls M5 over gRPC (grpc-netty-shaded), persists to Postgres (decisions JSONB schema), emits JSON on `decisions.{domain}`. DLQ to `decisions.{domain}.failed` with six stable failure-reason codes. Avro→clean-JSON encoder flattens unions for M5's enriched_event_json. **85 orchestrator tests** (71 unit + 14 IT), **90% line / 77% branch** coverage. ADR-005 records the schema + DLQ design |
| **M7 — Audit & Decision API** | ✅ done | New `io.conclave.audit` package inside `orchestrator/`. Three endpoints: `GET /api/v1/decisions` (paginated list with 8 filter params), `GET /api/v1/decisions/{id}` (detail), `POST /api/v1/decisions/{id}/replay` (re-runs M5 on stored evidence, no persist). Read-side `DecisionAuditRepository` separate from M6's writer; dynamic SQL WHERE builder. Stable error codes (`decision_not_found`, `invalid_argument`). Wire format snake_case throughout — matches the M6 `decisions.{domain}` payload so the dashboard owns one type def. Added `baseline_entity_id` column to decisions table (with idempotent ALTER for roll-forward). ADR-006 records the API surface + read/write split rationale |
| **M8 — Reference Configurations** | ✅ done | Schemas + feature specs + graph templates already shipped under M1/M2/M4. Session 9 added [docker-compose.yml](docker-compose.yml) (Kafka KRaft dual-listener + Confluent Schema Registry + Postgres pgvector + Neo4j 5 + four CONCLAVE services), [docker-compose.ollama.yml](docker-compose.ollama.yml) overlay for the local-only judge backend, and per-service Dockerfiles under [docker/](docker/). `make demo-fraud` / `make demo-security` switch domains via `SPRING_PROFILES_ACTIVE` on the SAME image set; `-local` variants swap the judge backend via the Ollama overlay. `.env.example` documents the `ANTHROPIC_API_KEY` env var. Full boot deferred to next session (compose configs validate clean). ADR-008 records the design |
| **M9 — Synthetic Data Generators** | ✅ done | New `generators/` Maven module. Two CLIs (`FraudGeneratorMain`, `SecurityGeneratorMain`) plus seven `Scenario` impls (clean / card-testing ring / ATO / bust-out for fraud; clean / lateral-movement / ATO / exfil for security). Plain `KafkaProducer<String, SpecificRecord>` — no Spring, ~1s cold start. Ground-truth labels published as JSON on `events.{domain}.labels` so the orchestrator never sees them. **36 unit tests + 2 Testcontainers ITs, 91% line / 88% branch coverage.** ADR-007 records the no-Spring choice, label-side-topic rationale, and pattern catalog |
| **M10 — Dashboard + Demo Harness** | ✅ done | New `dashboard/` Vite + React 19 + Tailwind v4 + TanStack Query app. Consumes the M7 audit API: paginated decision list with 5-filter bar + 10-bucket score histogram + 5s polling; decision detail view with judge-verdict markdown, signed-weight contributing-factor bars, replay button (shows fresh decision in a separate "not persisted" card). Builds clean (720 KB JS / 211 KB gzipped). Plus `website/` — single-page marketing site (same stack, no router) with hero / problem / how-it-works / configs / benchmarks / quickstart sections, ready for Cloudflare Pages deploy. ADR-009 records the Tailwind v4 + shadcn-inlined + TanStack-polling choices. README + DESIGN.md cross-link everything |

**Last green build:** Session 10 (see history below) — **219/219 Java tests + 99/99 Python tests passing across 6 modules** (orchestrator: 111 [34 pre-M6 + 51 M6 + 26 M7], baseline: 40, graph: 30, generators: 38 [36 unit + 2 IT], agents: 99). Coverage: orchestrator 91%/75% (post-M7), baseline 99%/92%, graph 94%/88%, generators 91%/88%, agents 98% line (Java threshold: 80%/70% JaCoCo; Python threshold: 80% line pytest-cov, both fail the build below). Dashboard + website both build cleanly via `npm run build` (Vite 6).
**Coverage threshold enforced:** 80% line, 70% branch (JaCoCo); 80% line (pytest-cov).

---

## ▶️ Next Actions (top of the queue for the next agent)

All ten implementable modules are green. What's left to declare the project
"done" per spec §11 are content/deploy tasks — not code:

1. **End-to-end smoke-test the stack on a fresh Mac.**
   `git clone && cp .env.example .env && make demo-fraud`. Verify the
   dashboard at [http://localhost:5173](http://localhost:5173) (start
   with `make dashboard-dev` in a second terminal) shows decisions
   flowing. Iterate on any compose wiring fixes that surface — the
   YAML validates clean but no full boot has happened yet.
2. **Record demo GIFs + Loom video** (spec §7). Fraud config: clean
   stream → card-testing ring → CONCLAVE flags → drill into the
   decision → graph view. Then `make demo-stop && make demo-security`
   + lateral-movement scenario. Once the GIFs land, drop them into
   README.md (and link from website/src/App.tsx Hero section).
3. **Deploy `website/` to Cloudflare Pages.** `make website-build`
   produces a static `website/dist/` directory; point a Pages project
   at it.
4. **Run the benchmark + write the arXiv paper** (spec §9). The eval
   pipeline reads `decisions.{domain}` ⋈ `events.{domain}.labels` on
   `event_id`. M9's labeled streams + IEEE-CIS Fraud + BETH security
   are the inputs.
5. **Resume bullet draft.** Capability-focused, per spec §11.

Optional refinements that would harden the project but aren't blockers:

- Code-split recharts out of the dashboard's main bundle (saves ~150 KB).
- Add a `LICENSE` file (MIT per spec §11).
- Wire CI for the dashboard + website (npm install + build) — currently
  only the Java + Python modules have CI.
- Promote `springdoc-openapi` to a real version once it ships for
  Spring Boot 4; replace the hand-written
  [dashboard/src/lib/types.ts](dashboard/src/lib/types.ts) with codegen.

**The full data plane runs end-to-end.** A raw event published to
`events.{domain}.raw` flows through M2 (Kafka Streams) → emits on
`events.{domain}.enriched` → M6 consumes → calls M5 over gRPC →
persists to Postgres → emits on `decisions.{domain}` (or `.failed`
on DLQ). M7 exposes the audit + replay surface on top of that.
M9 provides labeled synthetic traffic that drives the whole pipeline
for demos and benchmarks.

**Heads up for M8 (compose):**
- M9's CLIs assume Kafka on `KAFKA_BOOTSTRAP_SERVERS` (default
  `localhost:9092`) and Schema Registry on `SCHEMA_REGISTRY_URL`
  (default `mock://conclave-default` for tests; compose stack will
  need a real Confluent Schema Registry container).
- The four Java services bind: orchestrator 8080 + 9090, baseline
  8081 + 9091, graph 8082 + 9092, agents 9093. M6 reads M5 via
  `DELIBERATION_TARGET=host:9093`.
- All Java services boot from a single `SPRING_PROFILES_ACTIVE`
  (fraud or security) so the same image switches domain via env var.

**Heads up for M10:**
- API base URL: `http://localhost:8080/api/v1/decisions`.
- All field names snake_case in both the REST responses AND the
  `decisions.{domain}` Kafka payload. One type definition suffices.
- Replay endpoint: `POST /api/v1/decisions/{id}/replay` — returns a NEW
  `DecisionDetail` with a fresh `decision_id`. Original row unchanged.
- 404 body: `{"code":"decision_not_found","message":"..."}`. 400 body:
  `{"code":"invalid_argument","message":"..."}`.
- Filter params: `domain`, `verdict_label`, `baseline_entity_id`, `min_score`,
  `max_score`, `since`, `until`, `judge_provider`, `limit`, `offset`.
  Times accept epoch millis OR ISO-8601. Score bounds in [0, 1].

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
│       ├── 0004-judge-llm-provider-factory.md         # M5 ✅
│       ├── 0005-decision-persistence-and-dlq.md       # M6 ✅
│       ├── 0006-audit-api-surface.md                  # M7 ✅
│       ├── 0007-synthetic-data-generators.md          # M9 ✅
│       ├── 0008-compose-demo-harness.md               # M8 ✅
│       └── 0009-dashboard-tech-choices.md             # M10 ✅
├── docker-compose.yml                          # M8 ✅
├── docker-compose.ollama.yml                   # M8 ✅ (local-only judge overlay)
├── .env.example                                # M8 ✅
├── docker/                                     # M8 ✅
│   ├── orchestrator.Dockerfile
│   ├── baseline.Dockerfile
│   ├── graph.Dockerfile
│   └── agents.Dockerfile
├── orchestrator/                    # Java/Spring service (M1, M2, M6, M7)
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── proto/deliberation.proto           # vendored from agents/proto for M6's Java stubs
│       │   ├── java/io/conclave/
│       │   │   ├── ingest/                # M1 ✅
│       │   │   ├── stream/                # M2 ✅
│       │   │   ├── orchestrator/          # M6 ✅
│       │   │   │   ├── DecisionConsumer.java      # @KafkaListener on events.{domain}.enriched
│       │   │   │   ├── DecisionOrchestrator.java  # workflow: M5 → DB → decisions.{domain}
│       │   │   │   ├── client/                    # DeliberationClient + ManagedChannel config
│       │   │   │   ├── config/                    # DecisionOrchestratorProperties
│       │   │   │   ├── domain/                    # DecisionRecord, ContributingFactorRecord
│       │   │   │   ├── encode/                    # Avro → JSON + DeliberationRequestTranslator
│       │   │   │   ├── messaging/                 # DecisionPublisher, DlqPublisher, topic config
│       │   │   │   └── storage/                   # JdbcDecisionRepository, SchemaInitializer
│       │   │   └── audit/                 # M7 ✅
│       │   │       ├── AuditController.java       # REST endpoints @ /api/v1/decisions
│       │   │       ├── AuditService.java          # list/detail/replay logic
│       │   │       ├── DecisionFilter.java        # query-param record + builder
│       │   │       ├── DecisionSummary.java       # list-view DTO
│       │   │       ├── DecisionDetail.java        # full-row DTO
│       │   │       ├── DecisionPage.java          # paginated response envelope
│       │   │       ├── DecisionAuditRepository.java     # read interface
│       │   │       ├── JdbcDecisionAuditRepository.java # JDBC + dynamic WHERE
│       │   │       └── DecisionNotFoundException.java
│       │   └── resources/application.yaml         # Kafka consumer (Avro), Postgres, snake_case JSON
│       └── test/java/io/conclave/
│           ├── ingest/                # M1 ✅
│           ├── stream/                # M2 ✅
│           ├── orchestrator/          # M6 ✅  (51 tests: 49 unit + 2 IT)
│           └── audit/                 # M7 ✅  (25 tests: 18 unit + 7 IT)
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
├── generators/                      # M9 ✅  (no Spring; plain KafkaProducer)
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/io/conclave/generators/
│       │   │   ├── EventPublisher.java         # mirrors M1's idempotent + acks=all producer
│       │   │   ├── GeneratorDomain.java        # mirror of io.conclave.ingest.EventDomain
│       │   │   ├── GeneratorRunner.java        # drains a List<Scenario> into a publisher
│       │   │   ├── CliOptions.java             # hand-rolled --flag value parser
│       │   │   ├── Labels.java                 # ground-truth label enum
│       │   │   ├── LabelRecord.java            # JSON record published to events.{domain}.labels
│       │   │   ├── LabeledEvent.java           # (event, label, scenarioId, reason)
│       │   │   ├── Scenario.java               # functional interface: Stream<LabeledEvent>
│       │   │   ├── fraud/                      # 4 scenarios + FraudGeneratorMain
│       │   │   └── security/                   # 4 scenarios + SecurityGeneratorMain
│       │   └── resources/logback.xml
│       └── test/java/                          # 36 unit + 2 IT tests, 91%/88% coverage
├── dashboard/                       # M10 ✅ Vite + React + Tailwind audit UI
│   ├── package.json
│   ├── vite.config.ts (proxies /api → :8080 in dev)
│   ├── src/
│   │   ├── lib/{api.ts, types.ts, utils.ts}
│   │   ├── hooks/useDecisions.ts (TanStack Query, 5 s polling)
│   │   ├── components/ (DecisionTable, FilterBar, ScoreHistogram,
│   │   │                ContributingFactors, JsonBlock, VerdictMarkdown, …)
│   │   ├── pages/{DecisionsPage, DecisionDetailPage}.tsx
│   │   └── App.tsx
├── website/                         # M10 ✅ marketing SPA (Cloudflare-Pages-ready)
│   ├── package.json
│   ├── src/{App.tsx, main.tsx, index.css}
│   └── public/conclave-favicon.svg
└── benchmark/                       # not yet created (eval pipeline — post-M10)
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

### Session 6 — 2026-05-26 — M6 landed (Decision Orchestrator)
**Agent:** Claude Opus 4.7
**Started from:** Session 5's commit `e93929c`.

**Delivered:**
- Vendored [agents/proto/deliberation.proto](agents/proto/deliberation.proto)
  into [orchestrator/src/main/proto/](orchestrator/src/main/proto/deliberation.proto)
  and wired the `protobuf-maven-plugin` in the orchestrator pom (mirrors the
  M3/M4 pattern). Generated Java stubs land in `io.conclave.deliberation.proto.*`
  (excluded from JaCoCo via root pom).
- Extended `orchestrator/pom.xml`: added `spring-boot-starter-jdbc` +
  `postgresql`, `grpc-netty-shaded` + `grpc-protobuf` + `grpc-stub` +
  `jakarta.annotation-api`, plus Testcontainers `postgresql` + `grpc-inprocess`
  for tests.
- New `io.conclave.orchestrator` package under
  [orchestrator/src/main/java/](orchestrator/src/main/java/io/conclave/orchestrator/):
  - `domain/` — [DecisionRecord](orchestrator/src/main/java/io/conclave/orchestrator/domain/DecisionRecord.java)
    + [ContributingFactorRecord](orchestrator/src/main/java/io/conclave/orchestrator/domain/ContributingFactorRecord.java).
    Records validate in compact constructors; `DecisionRecord` defensively copies
    the factors list so the audit path is genuinely immutable.
  - `encode/` — [EnrichedEventJsonEncoder](orchestrator/src/main/java/io/conclave/orchestrator/encode/EnrichedEventJsonEncoder.java)
    flattens Avro union wrappers (`{"string": "DE"}` → `"DE"`), serializes
    Instants as epoch millis, and produces the clean JSON M5's Python feature
    node expects (matches agents/tests/conftest.py fixtures field-for-field).
    [DeliberationRequestTranslator](orchestrator/src/main/java/io/conclave/orchestrator/encode/DeliberationRequestTranslator.java)
    pulls `eventId`/`baselineEntityId`/`graphEntityIds` from the Avro record and
    builds the proto request.
  - `client/` — [DeliberationClient](orchestrator/src/main/java/io/conclave/orchestrator/client/DeliberationClient.java)
    (sync gRPC blocking stub + per-call deadline + clock-injected `DecisionRecord`
    stamping) + [DeliberationClientConfig](orchestrator/src/main/java/io/conclave/orchestrator/client/DeliberationClientConfig.java)
    (channel with `destroyMethod=shutdown`).
  - `storage/` — [JdbcDecisionRepository](orchestrator/src/main/java/io/conclave/orchestrator/storage/JdbcDecisionRepository.java)
    + [SchemaInitializer](orchestrator/src/main/java/io/conclave/orchestrator/storage/SchemaInitializer.java).
    Idempotent `CREATE TABLE IF NOT EXISTS` on startup; `?::jsonb` parameter
    cast for the contributing_factors column.
  - `messaging/` — [DecisionPublisher](orchestrator/src/main/java/io/conclave/orchestrator/messaging/DecisionPublisher.java)
    + [DlqPublisher](orchestrator/src/main/java/io/conclave/orchestrator/messaging/DlqPublisher.java)
    + [DecisionTopicConfig](orchestrator/src/main/java/io/conclave/orchestrator/messaging/DecisionTopicConfig.java)
    + [DecisionKafkaConfig](orchestrator/src/main/java/io/conclave/orchestrator/messaging/DecisionKafkaConfig.java).
    Separate `KafkaTemplate<String, String>` bean for the JSON-valued decision
    topics (coexists with M1's Avro template via Spring's type-aware DI).
  - [DecisionConsumer](orchestrator/src/main/java/io/conclave/orchestrator/DecisionConsumer.java)
    — `@KafkaListener` driven by SpEL bean references (`#{@enrichedInputTopic}`,
    `#{@orchestratorConsumerGroup}`) so it stays domain-agnostic.
  - [DecisionOrchestrator](orchestrator/src/main/java/io/conclave/orchestrator/DecisionOrchestrator.java)
    — workflow: translate → call M5 → persist → emit. Six failure paths,
    all DLQ-routed.
- `EventDomain` extended with `decisionsFailedTopic()` for the DLQ.
- All M6 components gated behind
  `@ConditionalOnProperty("conclave.orchestrator.enabled", matchIfMissing=true)`
  so the older M1/M2 ITs that don't spin up Postgres can disable the M6 slice
  with one property (`conclave.orchestrator.enabled=false` in `@SpringBootTest`).
- [application.yaml](orchestrator/src/main/resources/application.yaml) extended
  with the consumer's Avro deserializer config, `spring.datasource.*`, and
  the new `conclave.orchestrator.*` block.
- ADR-005 ([docs/adr/0005-decision-persistence-and-dlq.md](docs/adr/0005-decision-persistence-and-dlq.md))
  records the decisions schema design (JSONB factors, judge_provider
  persistence, verbatim event payload), the DLQ failure-reason vocabulary,
  and the rejected alternatives (normalized factors table, Avro decision schema,
  no-DLQ retry-on-consumer).

**Tests — 85 orchestrator tests total, all green** (`mvn -pl orchestrator verify`):

  - **Unit (71)**: `ContributingFactorRecordTest` (6),
    `DecisionRecordTest` (9), `DecisionOrchestratorPropertiesTest` (7),
    `EnrichedEventJsonEncoderTest` (3 — fraud + security + null-optional),
    `DeliberationRequestTranslatorTest` (5),
    `DeliberationClientTest` (3 — in-process gRPC, happy path / INTERNAL /
    DEADLINE_EXCEEDED), `JdbcDecisionRepositoryTest` (1 — JdbcTemplate mocked,
    `?::jsonb` cast asserted), `DecisionPublisherTest` (2 — fraud/security
    topic routing), `DlqPublisherTest` (4 — failure-reason vocab, truncation),
    `DecisionOrchestratorTest` (9 — every failure path).
    Plus the 34 existing M1/M2 tests unchanged.
  - **Integration (14)**: 12 existing M1/M2 ITs untouched + 2 new M6 ITs:
    `DecisionOrchestratorIT.happy_path_persists_and_emits_decision` and
    `DecisionOrchestratorIT.m5_unavailable_routes_event_to_dlq`. Both spin up
    Kafka + Postgres Testcontainers + a real Netty gRPC mock M5 bound to a
    free loopback port. End-to-end production transports run; the only thing
    mocked is the M5 behavior.
- **Coverage: 90% line / 77% branch** on the orchestrator module (well above
  the 80%/70% gate; ratio comparable to the M1/M2-only baseline of 97%/90%
  because M6 added ~600 statements with no Avro/proto exclusions left to
  inflate the ratio).

**Build settings changed:**
- Root `pom.xml` JaCoCo `<excludes>` extended with
  `io/conclave/deliberation/proto/**/*` for the new generated stubs.
- All M6 components gated behind `conclave.orchestrator.enabled` property
  (`matchIfMissing=true`). Existing M1/M2 ITs add the `=false` override to
  their `@SpringBootTest(properties = ...)` so they boot without a Postgres
  container.

**New gotchas surfaced in [SCRATCHPAD.md](SCRATCHPAD.md):**
- Avro's built-in `JsonEncoder` wraps union types (`{"string": "DE"}`) —
  unusable for cross-language JSON contracts. Write a schema-walking encoder
  instead (we did; see EnrichedEventJsonEncoder).
- `@KafkaListener` SpEL can reference beans by name with `#{@beanName.method()}`
  syntax, but `@ConfigurationProperties` records register under a verbose
  `<prefix>-<FQCN>` name that's not SpEL-friendly. Workaround: expose
  derived String values as named `@Bean` definitions.
- `@SpringBootTest` Spring contexts load ALL @Components in the module —
  adding new Postgres-needing beans breaks older ITs that don't spin up
  Postgres. Gate the new slice behind a property and disable it in the
  older ITs.
- Mockito strict stubbing + JdbcTemplate varargs: `when(jdbc.update(anyString(),
  (Object[])any())).thenReturn(1)` doesn't pattern-match correctly. Drop the
  stubbing entirely if you don't read the return value — default int return
  is 0 which is fine.

**Handoff for Session 7:** Start at M7 (Audit & Decision API). See "Next
Actions" above. The decisions table schema is in place with all columns the
audit API needs; the replay endpoint can use the existing DeliberationClient
bean. The orchestrator module's `io.conclave.audit` package is the new home.
M6 stays gated behind `conclave.orchestrator.enabled`; M7 can ride the same
flag (or get its own — judgment call when the structure is clearer).

### Session 7 — 2026-05-26 — M7 landed (Audit & Decision API)
**Agent:** Claude Opus 4.7
**Started from:** Session 6's commit `d2596cf`.

**Delivered:**
- Added `baseline_entity_id` as a first-class column on the decisions table
  (idempotent `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` migration in
  [SchemaInitializer](orchestrator/src/main/java/io/conclave/orchestrator/storage/SchemaInitializer.java)
  for roll-forward). Threaded through
  [DecisionRecord](orchestrator/src/main/java/io/conclave/orchestrator/domain/DecisionRecord.java),
  [DeliberationClient](orchestrator/src/main/java/io/conclave/orchestrator/client/DeliberationClient.java)
  (stamped from the proto request), and
  [DecisionPublisher](orchestrator/src/main/java/io/conclave/orchestrator/messaging/DecisionPublisher.java)
  (surfaced as `baseline_entity_id` in the Kafka JSON payload). New index
  `idx_decisions_baseline_entity` for the entity-filter query.
- New `io.conclave.audit` package
  ([orchestrator/src/main/java/io/conclave/audit/](orchestrator/src/main/java/io/conclave/audit/)):
  - [DecisionFilter](orchestrator/src/main/java/io/conclave/audit/DecisionFilter.java)
    — `Optional<…>`-heavy record + fluent builder, validates score bounds /
    limit / offset in its compact constructor.
  - [DecisionSummary](orchestrator/src/main/java/io/conclave/audit/DecisionSummary.java)
    (list-view DTO; excludes the heavy `verdictExplanationMd` /
    `contributingFactors` / `enrichedEventJson`),
    [DecisionDetail](orchestrator/src/main/java/io/conclave/audit/DecisionDetail.java)
    (full row), [DecisionPage](orchestrator/src/main/java/io/conclave/audit/DecisionPage.java)
    (paginated envelope with `total` for "page N of M").
  - [DecisionAuditRepository](orchestrator/src/main/java/io/conclave/audit/DecisionAuditRepository.java)
    + [JdbcDecisionAuditRepository](orchestrator/src/main/java/io/conclave/audit/JdbcDecisionAuditRepository.java)
    — read-side, parallel to M6's writer. Hand-rolled dynamic SQL WHERE
    builder, two RowMappers (summary skips JSONB parsing, detail
    reconstructs the full `DecisionRecord`).
  - [AuditService](orchestrator/src/main/java/io/conclave/audit/AuditService.java)
    — list/detail/replay. Replay parses `graphEntityIds` from the stored
    enriched event JSON, rebuilds a `DeliberationRequest`, calls the M6
    `DeliberationClient` bean, returns the fresh `Decision` WITHOUT
    persisting (audit history stays immutable).
  - [AuditController](orchestrator/src/main/java/io/conclave/audit/AuditController.java)
    — REST surface at `/api/v1/decisions`. Eight query-param filters,
    ISO-8601 OR epoch-millis time parsing, stable error codes
    (`decision_not_found`/404, `invalid_argument`/400).
- Global Jackson `property-naming-strategy=SNAKE_CASE` so REST responses
  match the snake_case Kafka payload M6 already emits — the dashboard
  owns one type definition for "Decision".
- ADR-006 ([docs/adr/0006-audit-api-surface.md](docs/adr/0006-audit-api-surface.md))
  records the read/write repository split, replay semantics, hand-rolled
  SQL choice, snake_case decision, and the springdoc-openapi gap (SB4
  release is milestone-only; deferred).

**Tests — 26 new M7 tests, all green** (orchestrator total: 111 = 90 unit + 21 IT):
  - **Unit (18)**: `DecisionFilterTest` (8 — defaults, builder, validation
    matrix including min>max), `AuditServiceTest` (8 — happy paths for
    list/detail/replay, 404 paths, replay tolerates missing /
    malformed `graphEntityIds`), `AuditControllerErrorHandlersTest`
    (2 — 404 + 400 response shape with stable codes).
  - **Integration (7)**: `AuditApiIT` boots the full Spring context under
    fraud profile against Testcontainers Kafka + Postgres + a Netty
    mock M5; seeds rows via the M6 `DecisionRepository` writer; hits the
    REST API via Spring `RestClient` on `@LocalServerPort`. Covers:
    list filtering by domain + score, pagination by entity, detail
    payload shape, 404 on unknown id, replay returns fresh decision
    without persisting (asserts the original row is untouched), replay
    404, list 400 on invalid score bound.
- **Coverage: 91% line / 75% branch** on the orchestrator module
  (post-M7, gate: 80%/70%). M7 added 1135 statements; line coverage
  ticked up and branch ticked down slightly.

**Build settings changed:**
- `spring.jackson.property-naming-strategy: SNAKE_CASE` in
  [application.yaml](orchestrator/src/main/resources/application.yaml).
  Aligns the REST DTOs' wire format with the M6 Kafka payload's
  hand-built keys; the dashboard reads one type def.
- All new M7 components carry the same
  `@ConditionalOnProperty("conclave.orchestrator.enabled", matchIfMissing=true)`
  gate as M6 — so M1/M2 ITs that disable the M6 slice also skip M7
  cleanly. ADR-006 notes a future split could justify a separate
  `conclave.audit.enabled` flag.

**New gotchas surfaced in [SCRATCHPAD.md](SCRATCHPAD.md):**
- `@TestInstance(Lifecycle.PER_CLASS)` reorders the Testcontainers
  extension vs `@DynamicPropertySource` — keep `@BeforeAll`/`@AfterAll`
  static so the containers start before Spring property resolution.
- Spring Boot's Jackson default does NOT snake_case Java records.
  `spring.jackson.property-naming-strategy=SNAKE_CASE` aligns the
  REST DTOs with M6's hand-built Kafka payload shape.
- `RestClient` (the Spring 6.x replacement for `TestRestTemplate`)
  throws typed `HttpClientErrorException.NotFound` / `BadRequest`
  on 4xx by default; assert on those instead of polling status codes.
- springdoc-openapi 2.x targets Spring Boot 3.x only; 3.x line is
  milestone-only. M7 ships without auto-generated OpenAPI; the
  dashboard team uses ADR-006 + controller javadoc as the contract.

**Handoff for Session 8:** Start at M9 (Synthetic Data Generators) — see
"Next Actions" above. M8 is partially done already (schemas + feature
specs + graph templates); finish it together with M9 if it makes the
docker-compose harness easier. M10 (dashboard) consumes the M7 REST API
+ the `decisions.{domain}` Kafka topic; ADR-006 + this PROGRESS entry +
[application.yaml](orchestrator/src/main/resources/application.yaml) are
the contract.

### Session 8 — 2026-05-26 — M9 landed (Synthetic Data Generators)
**Agent:** Claude Opus 4.7
**Started from:** Session 7's commit `5eb565b`.

**Delivered:**
- New `generators/` Maven module — fourth Java sibling. No Spring Boot
  (CLI cold start matters): plain `kafka-clients` + `avro` +
  `kafka-avro-serializer` + `jackson-databind` + `slf4j` / `logback`.
- Avro classes regenerated from `configs/` using the same parent-pom
  `avro-maven-plugin` config the orchestrator module uses, so the
  generators emit the exact bytes M1's consumer can deserialize.
- Java sources in [generators/src/main/java/io/conclave/generators/](generators/src/main/java/io/conclave/generators/):
  - [EventPublisher](generators/src/main/java/io/conclave/generators/EventPublisher.java)
    — owns two producers (Avro for raw, String for labels). Mirrors M1's
    `KafkaProducerConfig` settings exactly: `enable.idempotence=true`,
    `acks=all`, `max.in.flight.requests.per.connection=5`.
  - [GeneratorDomain](generators/src/main/java/io/conclave/generators/GeneratorDomain.java)
    — mirror of `io.conclave.ingest.EventDomain` (kept independent to avoid
    pulling Spring + Postgres + gRPC into a CLI module).
  - [GeneratorRunner](generators/src/main/java/io/conclave/generators/GeneratorRunner.java)
    — drains a `List<Scenario>` into the publisher, counts clean vs
    adversarial, aborts on first publish exception (no partial-truth
    label streams).
  - [CliOptions](generators/src/main/java/io/conclave/generators/CliOptions.java)
    — hand-rolled `--flag value` parser. Eight flags, no picocli.
  - [Labels](generators/src/main/java/io/conclave/generators/Labels.java)
    + [LabelRecord](generators/src/main/java/io/conclave/generators/LabelRecord.java)
    — ground-truth enum + snake_case JSON record published to
    `events.{domain}.labels`.
  - `fraud/` package — `CleanFraudScenario` (organic CNP),
    `CardTestingRingScenario` (one device, many cards, low amounts),
    `FraudAtoScenario` (geo flip + device change), `BustOutScenario`
    (gradual ramp then high-ticket bursts), `FraudGeneratorMain`.
  - `security/` package — `CleanAuthScenario`, `LateralMovementScenario`
    (one principal, many hosts, shared session), `SecurityAtoScenario`
    (SSO→password regime change), `ExfiltrationScenario` (privileged
    reads of sensitive resources), `SecurityGeneratorMain`.
- ADR-007 ([docs/adr/0007-synthetic-data-generators.md](docs/adr/0007-synthetic-data-generators.md))
  records the no-Spring decision, the labels-on-side-topic choice
  (leakage prevention), the `Scenario` interface design, and the
  full pattern catalog mapping each scenario to the detector that
  should fire.
- Makefile additions: `m9-test`, `m9-verify`, `m9-run-fraud`,
  `m9-run-security`. The run targets shell out to `exec-maven-plugin`
  so contributors can `make m9-run-fraud ARGS="--clean 5000 --rings 3"`
  against a local broker.

**Tests — 38 generator tests total, all green** (`mvn -pl generators verify`):

  - **Unit (36)**: `CliOptionsTest` (6), `GeneratorDomainTest` (2),
    `LabelRecordTest` (2), `EventPublisherTest` (3 — Mockito-mocked
    `Producer<String, SpecificRecord>` + `Producer<String, String>`,
    verifies routing, key extraction, flush+close), `GeneratorRunnerTest`
    (3 — counts, ordering, abort-on-failure), `CleanFraudScenarioTest`
    (3 — count, population diversity, determinism),
    `CardTestingRingScenarioTest` (3 — one device per ring, small
    amounts, scenarioId stability), `FraudAtoScenarioTest` (3 — warmup
    then takeover, regime shift, amount escalation), `BustOutScenarioTest`
    (2 — ramp + bust, single cardholder), `FraudGeneratorMainTest`
    (2 — scenario planning), `CleanAuthScenarioTest` (2),
    `LateralMovementScenarioTest` (2), `SecurityAtoScenarioTest` (1),
    `ExfiltrationScenarioTest` (1), `SecurityGeneratorMainTest` (1).
  - **Integration (2)**: `GeneratorIT` boots Testcontainers Kafka, runs
    both planners end-to-end, asserts every raw event has a matching
    label row keyed identically on `events.{domain}.labels`, and that
    each domain's expected scenarios (`CARD_TESTING_RING`, `FRAUD_ATO`,
    `LATERAL_MOVEMENT`, `EXFILTRATION`, `SECURITY_ATO`) all appear.
    One IT class for both domains because the per-class JVM fork
    dominates runtime; total IT wall-clock ~13s.
- **Coverage: 91% line / 88% branch** on the generators module
  (gate: 80%/70%). 546 lines analyzed across 23 classes.

**Full repo green: 219/219 Java tests across 5 modules** (orchestrator 111,
baseline 40, graph 30, generators 38, plus 99 Python tests in agents).

**Build settings changed:**
- Root pom `<modules>` now includes `generators`. No changes needed to
  JaCoCo excludes or Avro plugin config — the parent pluginManagement
  + Avro plugin block both apply automatically.
- Added `org.codehaus.mojo:exec-maven-plugin:3.5.0` to generators pom
  so the `make m9-run-*` targets work via
  `mvn exec:java -Dexec.mainClass=...`.

**New gotchas surfaced in [SCRATCHPAD.md](SCRATCHPAD.md):**
- `KafkaProducer<String, SpecificRecord>` works fine even without Spring's
  generic-aware DI — the raw `KafkaProducer` constructor takes a
  `Properties`/`Map`, not a type-parameterized factory.
- Mock testing `Producer<...>` with Mockito requires `@SuppressWarnings`
  for the unchecked generic mocks. Acceptable in test code.
- AssertJ's `.allMatch(predicate)` short-circuits — useful for "every
  event has the same scenarioId" without writing a forEach loop.
- Testcontainers `KafkaContainer` (`confluentinc/cp-kafka:7.6.0`) prints
  `LEADER_NOT_AVAILABLE` warnings on first publish; benign — the topic
  is being auto-created. Use `await().atMost(60s)` for the consume loop.

**Handoff for Session 9:** Start at M8 (compose + demo Makefile targets)
— see "Next Actions" above. The generators publish to
`events.{domain}.raw` + `events.{domain}.labels`; the M1 producer SDK is
unchanged and a compose stack would expose Kafka on the host network
for both sides. Then M10 (dashboard). The label topic is NOT subscribed
by any in-repo component yet — it's purely for the future eval pipeline.

### Session 9 — 2026-05-26 — M8 landed (Compose demo harness)
**Agent:** Claude Opus 4.7
**Started from:** Session 8's commit `3942834`.

**Delivered:**
- Top-level [docker-compose.yml](docker-compose.yml) declaring the full
  CONCLAVE stack:
  - **Infra:** Kafka 7.6 in KRaft single-node mode with a dual listener
    (`PLAINTEXT://kafka:29092` for compose-internal traffic + `EXTERNAL://localhost:9092`
    for the host-run generators); Confluent Schema Registry 7.6 on host
    port 8085; `pgvector/pgvector:pg16` shared across orchestrator + baseline;
    `neo4j:5-community` for the graph reasoner. Every infra service has a
    healthcheck and the CONCLAVE services declare `depends_on:
    {condition: service_healthy}` so the boot order is deterministic.
  - **CONCLAVE services:** all four built from per-service Dockerfiles
    in [docker/](docker/). Java services (orchestrator, baseline, graph)
    use `eclipse-temurin:25-jre` + a pre-built Spring Boot fat jar (see
    "Pre-built fat jars" in ADR-008). Agents (Python/M5) uses
    `python:3.12-slim` + uv + a build-time `gen_protos.sh` run so the
    container is self-contained.
  - **Env-driven config:** orchestrator switches domain via
    `SPRING_PROFILES_ACTIVE` ({fraud | security}). Other three services
    are domain-agnostic. Schema Registry URL, JDBC URL, gRPC targets all
    plumbed via env vars the application.yaml files already
    accept (no code changes needed in the services).
- [docker-compose.ollama.yml](docker-compose.ollama.yml) overlay: adds
  an Ollama sidecar (auto-pulls qwen3:8b on first run, ~6 GB cached in
  a named volume) and flips agents to `JUDGE_LLM_PROVIDER=ollama`.
  Applied via `-f docker-compose.yml -f docker-compose.ollama.yml`.
- [.env.example](.env.example) for `ANTHROPIC_API_KEY` + optional model
  override. `.env` already gitignored.
- Makefile additions:
  - `demo-build` — `mvn -DskipTests package` on the three Java service
    modules + `docker compose build`.
  - `demo-fraud` / `demo-security` — run `demo-build`, then `docker
    compose up -d` with `SPRING_PROFILES_ACTIVE` set, sleep 15s for
    services to register, then call the matching M9 generator with a
    sensible default burst (200 clean + 2 rings + 1 ATO + 1 extra for
    fraud; same shape for security).
  - `demo-fraud-local` / `demo-security-local` — same flow but with
    the Ollama overlay layered on. Smaller burst (100 clean) since the
    local model is slower per-decision than Haiku.
  - `demo-stop` — `docker compose down` (keeps volumes).
  - `demo-logs` — `docker compose logs -f` on the four CONCLAVE services.
  - `demo-status` — `docker compose ps` + curl the M7 audit API for
    the latest 10 decisions.
- ADR-008 ([docs/adr/0008-compose-demo-harness.md](docs/adr/0008-compose-demo-harness.md))
  records the design: one compose file (no per-domain forks), Ollama
  as an overlay (not a `profiles:` switch), pre-built fat jars (not
  in-Docker Maven), KRaft dual-listener pattern, shared Postgres,
  healthcheck-chained boot order. Rejected alternatives listed.

**Smoke-test status:**
- `docker compose config --quiet` passes on both the base file and the
  base+overlay layered pair.
- Full boot deferred (~2 GB pull on first run, needs Anthropic key for
  the non-local path). The wiring follows the same image versions +
  env-var shapes the Testcontainers ITs (M1, M2, M3, M4, M6, M9)
  already exercise green.

**Build settings changed:**
- No Maven changes — the demo path runs `mvn -DskipTests package`
  which uses the existing module setup. The Java services already had
  `spring-boot-maven-plugin` configured to produce repackaged fat jars.
- `.gitignore` already ignored `.env`, `target/`, and `**/postgres-data/`
  / `**/neo4j-data/` — no changes needed.

**New gotchas surfaced in [SCRATCHPAD.md](SCRATCHPAD.md):**
- KRaft Kafka requires `CLUSTER_ID` pinned to a base64-22 UUID for
  container re-creates against persisted volumes.
- `KAFKA_ADVERTISED_LISTENERS` per-listener is the load-bearing line:
  EXTERNAL must advertise `localhost`, not `kafka:29092`, so host
  clients (the M9 generators) get a reachable broker.
- Pre-built fat jar + JRE COPY beats multi-stage Maven by 3-4 minutes
  per iteration, because the host already has the Maven repo cached.
- Confluent Schema Registry healthcheck via curl on `/subjects` is
  the simplest readiness probe; doesn't require auth setup.

**Handoff for Session 10:** Smoke-test the stack end-to-end (see Next
Actions #1). The compose YAML is syntactically valid but no human has
booted it yet. After that, M10 — Vite + React + shadcn/ui dashboard
against the M7 audit API on `http://localhost:8080/api/v1/`. ADR-006
+ application.yaml are the contract; M6's Kafka payload + M7's REST
DTO share the same snake_case shape so the dashboard owns one type def.

### Session 10 — 2026-05-26 — M10 landed (Dashboard + Marketing site)
**Agent:** Claude Opus 4.7
**Started from:** Session 9's commit `76b6ee6`.

**Delivered:**
- New [dashboard/](dashboard/) Vite + React 19 app — audit UI for the M7
  REST API.
  - **Stack:** Vite 6, React 19, TypeScript strict, Tailwind v4 (CSS-first
    config via `@theme`), TanStack Query v5, react-router v7, lucide-react,
    recharts. JetBrains Mono + Geist Sans via Fontsource. shadcn-style
    primitives inlined (~80 lines) rather than pulled from the shadcn CLI
    to keep the bundle lean. No Framer Motion (audit UI is tabular —
    animation would compete with the data).
  - **DecisionsPage** ([src/pages/DecisionsPage.tsx](dashboard/src/pages/DecisionsPage.tsx))
    — 5-filter bar (domain, verdict label, min/max score, entity), 10-bucket
    score histogram (recharts), paginated decision table with score badge +
    verdict pill + judge provider + relative timestamp. 5-second polling
    via `QueryClient.defaultOptions.queries.refetchInterval` so the
    "incoming events stream" use-case lights up without explicit SSE.
  - **DecisionDetailPage** ([src/pages/DecisionDetailPage.tsx](dashboard/src/pages/DecisionDetailPage.tsx))
    — metadata header (verdict + score + entity + judge + latency + ids +
    timestamp), tiny markdown subset renderer for the verdict explanation
    (`**bold**` + bullets + inline `code`, no `react-markdown` dep),
    signed-weight contributing-factor bars (positive→BLOCK red, negative
    →ALLOW green), copyable JSON viewer for the enriched event, and a
    Replay button that calls `POST /decisions/:id/replay` and shows the
    fresh decision in a separate "not persisted" card (matching the
    ADR-006 audit semantic where replay never mutates history).
  - **Type contract:** [src/lib/types.ts](dashboard/src/lib/types.ts)
    hand-mirrors the M7 Java DTOs (`DecisionSummary`, `DecisionDetail`,
    `ContributingFactor`, `DecisionPage`, error envelope) since
    springdoc-openapi 2.x doesn't support Spring Boot 4 yet (ADR-006
    gap). Cross-referenced via comment block in the file.
  - **Build:** 2572 modules → 720 KB JS / 211 KB gzipped (recharts is
    most of the weight). TypeScript strict passes.
- New [website/](website/) marketing SPA — same Vite + Tailwind v4 stack,
  no router, no data deps. Sections: hero, problem statement, four-agent
  how-it-works, side-by-side fraud/security config cards, latency benchmark
  table (M3 measured, M5/M6 pending eval), quickstart code block, footer.
  Builds to 65 KB gzipped JS + 18 KB gzipped CSS — well within Cloudflare
  Pages free tier.
- [README.md](README.md) at repo root: pitch + quickstart + module map
  + acceptance-criteria status table. Replaces the placeholder pitch
  spec §11 called out as missing.
- [DESIGN.md](DESIGN.md) — architectural decision log entry point. ASCII
  data-flow diagram, table of all 9 ADRs with one-line summaries, the
  seven cross-cutting invariants, and a "where to read the contract"
  staircase.
- ADR-009 ([docs/adr/0009-dashboard-tech-choices.md](docs/adr/0009-dashboard-tech-choices.md))
  records the Tailwind v4 CSS-first choice, shadcn primitives inlined,
  TanStack Query polling vs SSE, no-Framer-Motion, no `react-markdown`,
  Vite dev proxy for `/api`, and the type-drift risk vs OpenAPI codegen.
- Makefile additions: `dashboard-install`, `dashboard-dev`, `dashboard-build`,
  `website-install`, `website-dev`, `website-build`. Each calls `npm` in
  the right working directory; no global npm config required.

**Build/check status:**
- `npm run build` in `dashboard/` → ✓ built (Vite 6, 2.08 s).
- `npm run build` in `website/` → ✓ built (Vite 6, 955 ms).
- TypeScript strict mode passes in both.
- No backend changes; the Java + Python builds remain green at 219+99
  tests.

**New gotchas surfaced in [SCRATCHPAD.md](SCRATCHPAD.md):**
- Tailwind v4 uses `@import "tailwindcss"` + `@theme { ... }` in CSS —
  no `tailwind.config.ts`, no PostCSS config file. The
  `@tailwindcss/vite` plugin handles everything.
- TypeScript needs `"types": ["vite/client"]` in `tsconfig.app.json`
  for `import.meta.env` typing. Without it the build trips
  TS2339 on `import.meta.env`.
- `node:path` + `__dirname` in `vite.config.ts` require
  `@types/node` + `"types": ["node"]` in `tsconfig.node.json`.
- shadcn CLI scaffolds Radix primitives we don't need; inlining the
  three components (Button, Card, Field) we actually use beats
  the CLI for an audit UI.
- Polling (5 s) via `QueryClient.defaultOptions` is enough for the
  spec's "incoming events stream" feel — SSE would need a separate
  Spring endpoint and a parallel subscription pattern; deferred.

**Handoff for Session 11 (or the human owner):** The codebase
implementation is complete. Remaining items are content/deploy —
see Next Actions: smoke-test the stack on a fresh Mac, record demo
GIFs + Loom, deploy `website/` to Cloudflare Pages, run the
benchmark + write the arXiv paper, draft the resume bullet. The
`benchmark/` directory in spec §10 is still empty — that's the
next code-track if/when eval becomes a priority.
