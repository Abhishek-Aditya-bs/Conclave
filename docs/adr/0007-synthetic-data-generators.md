# ADR-007 — Synthetic data generators (M9)

* **Status.** Accepted (Session 8, 2026-05-26).
* **Module.** M9 — Synthetic Data Generators.
* **Spec hooks.** §6 M9, §7 "Scripted demo scenarios", §13 "Datasets".

## Context

M9 has to produce two things:

1. **Demo traffic** for `make demo-fraud` / `make demo-security` — a believable
   mix of organic events and adversarial patterns so the dashboard has
   something to flag during the Loom video.
2. **Labeled eval streams** for the benchmark pipeline so we can compute
   AUC and precision@FPR=1% on synthetic adversarial cases (orthogonal to
   IEEE-CIS / BETH which the spec earmarks for ground-truth comparison).

Three constraints shape the design:

- The orchestrator (M6) **must not see ground-truth labels** — otherwise
  evaluation leakage invalidates every number we publish.
- Generators are short-lived CLI tools; they shouldn't drag in Spring Boot,
  Postgres, or gRPC just to write events to one topic.
- Publish settings (`acks=all`, idempotent producer) must match M1's
  `KafkaEventProducer` exactly. Diverging here would mean the demo's
  data plane has different durability semantics from the real one.

## Decision

### A separate Maven module, no Spring

`generators/` is a sibling of `orchestrator/`, `baseline/`, `graph/`.
The module declares only what a producer-only CLI needs:
`kafka-clients`, `avro`, `kafka-avro-serializer`, `jackson-databind`,
`slf4j` + `logback-classic`. No Spring Boot starter, no autoconfigure
chain, no datasource. Cold start is ~1s vs the 6–8s a `SpringApplication.run`
would cost — meaningful when a benchmark loop relaunches the generator
hundreds of times.

Avro classes are regenerated from `configs/` using the same parent-pom
`avro-maven-plugin` configuration the orchestrator module uses. We do NOT
depend on the orchestrator JAR — that would pull in every transitive M1/M2/M6/M7
dependency for two record classes.

### Mirror, don't reuse, the M1 publisher

`EventPublisher` constructs its own `KafkaProducer<String, SpecificRecord>`
with the exact settings M1 codifies (`enable.idempotence=true`, `acks=all`,
`max.in.flight.requests.per.connection=5`). The producer-config block in
the orchestrator's `application.yaml` is the source of truth; both sites
reference the M1 spec rule.

We considered making `KafkaEventProducer` a library type that the generators
import, but that would force `orchestrator/` to ship its full dependency
graph to anyone who only wants a producer SDK. Mirror + comment is simpler
than a refactor across four modules.

### Labels on a side topic, not embedded in the event

`events.{domain}.labels` is a parallel JSON topic. Every raw event the
generator publishes gets a same-keyed label row containing
`{event_id, domain, label, scenario_id, reason, emitted_at_ms}`.

Rationale:

- **Leakage-proof by construction.** The M2 feature-extraction topology
  subscribes to `events.{domain}.raw` only; the M6 orchestrator subscribes
  to `events.{domain}.enriched` only. Neither sees the labels topic.
  Adding ground truth as an Avro field on the raw event would have required
  M2 to scrub it — a fragile invariant.
- **Eval-friendly.** A future eval job consumes `decisions.{domain}` +
  `events.{domain}.labels`, joins on `event_id`, and computes
  precision/recall. No replay of the original event needed.
- **Keyed identically.** Same key as the raw event so a downstream
  KTable join is one line.

Rejected alternative — labels in event headers: would have required
the M2 deserializer config to strip headers before emitting enriched
events. More chances to leak.

### Scenario interface, not a single God-class

A `Scenario` is a `Stream<LabeledEvent>` supplier. Each pattern
(`CardTestingRingScenario`, `FraudAtoScenario`, `BustOutScenario`,
`LateralMovementScenario`, `ExfiltrationScenario`, `SecurityAtoScenario`,
plus the `CleanFraudScenario` / `CleanAuthScenario` for negative volume)
implements it independently. The `GeneratorRunner` drains them in
declaration order. This makes new patterns a one-class addition and
makes each pattern unit-testable in isolation (no broker, no I/O).

Rejected — a single class with one method per pattern: would have
collapsed seven scenarios into one giant function with no clear unit
test boundaries.

### Deterministic given a seed

Every scenario takes a `Random`; the CLI's `--seed` flag (default 42)
threads through `Random(seed)` so a generator run is reproducible. Tests
exploit this — `CleanFraudScenarioTest` asserts identical seeds produce
identical event streams (modulo UUIDs).

### Minimal hand-rolled CLI parser

Eight flags, ~120 lines including help text. picocli, jcommander, and
similar libraries would add a 200KB+ transitive dep for what amounts to
a `while (i < args.length)` loop. The parser is a separate class so it's
unit-testable; six tests cover defaults, all-flags, help, unknown flag,
missing value, negative count.

### One IT for both generators, not one per

Per-class Testcontainers JVM forks dominate IT runtime. The two domains
are independent within the test (each runs against fresh topics on the
same broker), so a single `GeneratorIT` class costs one Kafka spin-up
and tests both flows. Total IT runtime: ~13s including container boot.

## Consequences

- **Demo harness wiring is trivial.** `make demo-fraud` runs
  `java -cp generators-*.jar io.conclave.generators.fraud.FraudGeneratorMain
  --clean 2000 --rings 3 --ato 2 --extra 1` and the M1 → M2 → M6 pipeline
  flows downstream untouched.
- **The eval pipeline (future) reads two topics, not one.** It joins
  `decisions.{domain}` ⊳⊲ `events.{domain}.labels` on `event_id`.
- **Producer settings live in two places.** If we ever change M1's
  durability settings, both `KafkaProducerConfig` and
  `EventPublisher#baseProducerProps` must move together. Cross-referenced
  via comments + this ADR.
- **No `LATERAL_MOVEMENT` warmup.** Lateral movement is itself the
  attack — no organic precursor. ATO and bust-out scenarios DO emit a
  CLEAN warmup tail so a future M3-backed eval can measure baseline
  drift before/after the regime change.

## Rejected alternatives

- **Single `generator/` main class in `orchestrator/`.** Tempting because
  it would skip module creation, but it would import all of M1/M2/M6/M7's
  beans into a CLI process that needs none of them.
- **Generators as a Spring Boot app.** Spring autoconfig is built for
  long-lived services; for a short-lived process, the cold start dominates.
- **Labels embedded in Avro raw event.** Leakage risk — would require
  M2 to scrub before emitting enriched.
- **`picocli` / `jcommander`.** Overkill for 8 flags; adds a transitive
  dep the generators wouldn't otherwise need.

## Pattern catalog (current)

| Domain   | Scenario                  | Label                | Detector that should fire |
|----------|---------------------------|----------------------|---------------------------|
| fraud    | `CleanFraudScenario`      | `CLEAN`              | none (negative class)     |
| fraud    | `CardTestingRingScenario` | `CARD_TESTING_RING`  | M4 `FraudCardTestingRingTemplate` |
| fraud    | `FraudAtoScenario`        | `FRAUD_ATO`          | M3 baseline drift + geo flip |
| fraud    | `BustOutScenario`         | `BUST_OUT`           | M3 baseline drift + amount envelope |
| security | `CleanAuthScenario`       | `CLEAN`              | none |
| security | `LateralMovementScenario` | `LATERAL_MOVEMENT`   | M4 `SecurityLateralMovementTemplate` |
| security | `SecurityAtoScenario`     | `SECURITY_ATO`       | M3 baseline drift + IP/method flip |
| security | `ExfiltrationScenario`    | `EXFILTRATION`       | M4 `SecurityPrivilegedAccessTemplate` |

When the eval pipeline lands, this table becomes the per-scenario
expected-fire matrix.
