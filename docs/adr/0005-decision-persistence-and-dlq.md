# ADR-005 — Decision persistence schema + DLQ design (M6)

* **Status.** Accepted (Session 6, 2026-05-26).
* **Module.** M6 — Decision Orchestrator.
* **Spec hooks.** §4 "Decision Store (Postgres) + Audit API + Dashboard", §6 M6.

## Context

M6 consumes enriched events off `events.{domain}.enriched`, calls M5 over
gRPC, and is responsible for two outputs:

1. A **persistent decision row** that the M7 audit API can query and the
   replay endpoint can rehydrate.
2. A **decision event** on `decisions.{domain}` so downstream consumers
   (dashboard, step-up-auth integrations, SOC ticketing) see decisions
   in real time.

Two cross-cutting questions emerged while wiring this:

* What schema captures the M5 Decision proto without locking us into
  the LLM-provider matrix as it evolves?
* How do we handle failure modes without losing events?

## Decision

### Persistence schema

One Postgres table, `decisions`, with the columns:

```sql
decision_id            UUID PRIMARY KEY,
event_id               VARCHAR(128) NOT NULL,
domain                 VARCHAR(16)  NOT NULL,
score                  DOUBLE PRECISION NOT NULL,
verdict_label          VARCHAR(32)  NOT NULL,
verdict_explanation_md TEXT         NOT NULL,
contributing_factors   JSONB        NOT NULL,
latency_ms             BIGINT       NOT NULL,
judge_provider         VARCHAR(32)  NOT NULL,
judge_model            VARCHAR(64)  NOT NULL,
enriched_event_json    TEXT         NOT NULL,
created_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
```

Three indexes:

* `(event_id)` — audit-by-event lookup.
* `(domain, score DESC)` — dashboard ranking by risk within domain.
* `(created_at DESC)` — recent-decisions feed.

**Key shape decisions:**

* `decision_id` is generated on the **orchestrator side**, not as a
  Postgres-side default. The same UUID gets stamped on the decisions
  Kafka payload BEFORE persisting, so a downstream consumer can
  correlate "I saw this decision event" with "row exists in audit DB"
  without round-tripping the application.
* `verdict_label` is a string, NOT an enum at the Postgres level. M5
  owns the verdict vocabulary; if it ever grows a new label
  (`REVIEW_AUTO`, `BLOCK_HARD`), the audit table accommodates without
  a migration. M7's API can validate at its boundary.
* `contributing_factors` is **JSONB**. A normalized child table was
  considered (see Alternatives) and rejected — the array is bounded
  per decision (typically 1–5 factors), JSONB indexing on `name` /
  `weight` is good enough for filter queries, and dropping the JOIN
  keeps the audit hot path under 5ms.
* `enriched_event_json` stores **verbatim** what M6 sent to M5. This
  is the cheap-replay enabler — M7's replay endpoint can re-invoke M5
  with the exact same prompt input without walking back to Kafka.
* `judge_provider` + `judge_model` are persisted so the benchmark
  pipeline can filter rows by backend. Spec §5 caveats: only
  Haiku-backed runs are quotable in published benchmarks, and the
  dashboard surfaces "fallback used" by checking these fields.

### DLQ design

When the orchestrator can't produce a Decision row, it writes a
failure payload to `decisions.{domain}.failed` instead of swallowing
the event. The payload shape:

```json
{
  "event_id": "evt-...",
  "reason": "m5_timeout | m5_internal | m5_unavailable | persistence_failure | translation_failure | unexpected",
  "detail": "<short error excerpt, ≤4KB>",
  "failed_at_epoch_ms": 1716700000000,
  "enriched_event_json": "<verbatim of what we tried to send to M5>"
}
```

Six stable failure categories (see
[DlqPublisher.FailureReason](../../orchestrator/src/main/java/io/conclave/orchestrator/messaging/DlqPublisher.java)).
Alerts depend on these codes; they don't churn.

Special case: **persistence-after-decision failures**. If M5 returned
a Decision but Postgres write fails, the orchestrator STILL emits on
`decisions.{domain}` (downstream consumers shouldn't be starved by a
transient DB outage) AND records the failure on the DLQ so the audit
replay can backfill the row. This is captured by the `PERSISTENCE_FAILURE`
reason with both `enriched_event_json` and the underlying error in
`detail`.

### Why a separate Kafka template

M6 needs two output templates living in the same process:

* `KafkaTemplate<String, SpecificRecord>` (existing, M1) for raw and
  enriched Avro events.
* `KafkaTemplate<String, String>` (new, M6) for JSON-valued
  `decisions.{domain}` and `decisions.{domain}.failed`.

Decision payloads are JSON, not Avro, because the dashboard, the
benchmark scripts, and the DLQ replay tool all want JSON they can
parse without schema-registry plumbing. Defining a separate Avro
schema for `Decision` only to re-serialize to JSON downstream would
be pure ceremony.

Spring's generic-aware DI distinguishes the two templates by their
type parameters, so they coexist cleanly under the same auto-config.

## Alternatives considered

### Normalized `contributing_factors` table

Considered: a child table `decision_factors(decision_id, idx, name,
weight, evidence)` with a FK back to `decisions`. Rejected because:

* Factors are bounded per decision (typically 1–5), so the JOIN cost
  outweighs the relational tidiness.
* JSONB supports indexed queries on `name` and `weight` via
  `jsonb_path_ops` — good enough for the dashboard's "show top 10
  decisions by factor=graph_ring_detected" use case.
* M5 owns the factor vocabulary; promoting it to a relational schema
  would force a migration every time a new factor name appears.

### Avro Decision schema

Considered: define `decisions.avsc` next to `enriched-schema.avsc`,
emit Avro on `decisions.{domain}`, persist the binary blob too.
Rejected because:

* The dashboard (M10) and benchmark scripts both want JSON — they'd
  decode-and-reencode anyway.
* Verdict explanation is free-text Markdown that's awkward in Avro
  (string with no length-vibe-check) and the contributing_factors
  array shape mirrors the proto 1:1 — JSON captures it without a
  parallel schema to keep in sync.
* The "schema-on-read" flexibility matters because the
  judge_provider + judge_model fields evolve faster than the
  enriched-event shape.

### Drop the DLQ; let Kafka consumer commit fail and reprocess

Considered. Rejected because the failures M6 catches (M5 timeout, DB
down) often persist beyond one retry window — looping the same event
through the consumer thread risks stalling the partition for minutes.
A DLQ lets the next event progress, and a separate replay tool
addresses the failure on its own cadence.

### Synchronous M5 retry inside the orchestrator before DLQ

Considered: one retry with exponential backoff before giving up.
Rejected for the initial cut because:

* The end-to-end p99 budget is 750ms; a retry would push the bound
  above 1.2s on the unhappy path.
* Two of the three failure modes (M5 INTERNAL, persistence failure)
  are not transient enough for an inline retry to help.
* The DLQ replay tool is a cleaner unit of retry — runs against
  recovered infrastructure, can batch, has its own SLO.

If demos surface flakes where one retry would have saved an event,
revisit. The
[DeliberationOrchestratorProperties.deliberationDeadlineMs](../../orchestrator/src/main/java/io/conclave/orchestrator/config/DecisionOrchestratorProperties.java)
already includes headroom for one retry's worth of latency budget;
the wiring just doesn't use it yet.

## Consequences

**Wins:**

* M7's audit API will fall out of the schema with no migrations —
  every column the dashboard needs is already there.
* The `judge_provider` + `judge_model` columns make the benchmark
  story honest: filter to Haiku rows only, ignore Ollama
  experiments, comply with spec §5.
* The DLQ keeps the demo robust against a wobbly local Ollama or a
  flaky M5 dev cycle — events surface in
  `decisions.{domain}.failed` instead of disappearing.

**Tradeoffs:**

* JSONB queries on `contributing_factors` are O(n) per row even with
  GIN indexing. Acceptable for ~100K decisions; M7 will revisit if
  the audit dataset grows past a million rows.
* JSON-valued decision topics mean a downstream Kafka consumer has
  to parse JSON; Avro consumers would get schema-typed records for
  free. Worth it for the schema-on-read flexibility.
* `@ConditionalOnProperty(name="conclave.orchestrator.enabled")` on
  every M6 bean is a touch noisy — but it lets the M1/M2 ITs boot
  without a Postgres container. Cleaner would be a profile, but
  profile-stacking with the existing `fraud`/`security` profiles
  gets tangled. Re-evaluate when M7 lands and we have a clearer
  picture of which beans need to coexist.
