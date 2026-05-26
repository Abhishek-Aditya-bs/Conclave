# ADR-006 — Audit API surface (M7)

* **Status.** Accepted (Session 7, 2026-05-26).
* **Module.** M7 — Audit & Decision API.
* **Spec hooks.** §6 M7, §4 "Decision Store + Audit API + Dashboard".

## Context

M7 is the read-side counterpart to M6. Three things must be true:

1. M10's dashboard can browse and drill into decisions, filtering by
   the dimensions analysts actually use (domain, score range, time
   window, entity, verdict label, judge backend).
2. A "replay" path re-runs the deliberation against the persisted
   evidence so reviewers can answer "what would the judge say now,
   with a different backend, or after a prompt tweak". Replay must
   not mutate audit history.
3. The wire shape stays uniform with the `decisions.{domain}` Kafka
   payload M6 emits — the dashboard reads both, and shouldn't have to
   own two type definitions for the same decision concept.

## Decision

### Three endpoints

```
GET  /api/v1/decisions                    — list, paginated, filtered
GET  /api/v1/decisions/{decisionId}       — full row
POST /api/v1/decisions/{decisionId}/replay — re-run M5, no persist
```

Implementation lives in `orchestrator/src/main/java/io/conclave/audit/`
inside the existing `orchestrator/` module — same process as M6 so the
audit API shares the M6 `DeliberationClient` bean for replay and uses
the same JDBC connection pool.

### Filter shape

`DecisionFilter` is a builder-driven `Optional<…>`-heavy record that
maps 1:1 onto query params:

| Query param | Predicate |
|---|---|
| `domain` | `domain = ?` |
| `verdict_label` | `verdict_label = ?` |
| `baseline_entity_id` | `baseline_entity_id = ?` |
| `min_score` | `score >= ?` |
| `max_score` | `score <= ?` |
| `since` | `created_at >= ?` |
| `until` | `created_at < ?` |
| `judge_provider` | `judge_provider = ?` |
| `limit` / `offset` | offset pagination |

Ordering is hardcoded to `created_at DESC` — the `idx_decisions_created_at`
index covers it, newer decisions always lead, and the dashboard never
needs an alternative.

Defaults: `limit=50`, capped at `MAX_LIMIT=500`. Score bounds + offset
+ limit are validated in the record's compact constructor; the
controller's `IllegalArgumentException` handler turns those into 400s.

### Read-side repository, separate from the writer

`DecisionAuditRepository` lives next to `DecisionRepository` (the M6
writer) as a parallel interface, NOT as additional methods on the
existing repo. Two reasons:

1. The audit hot path doesn't accumulate write methods as M7's surface
   grows (e.g. future `findByEvent`, `findByTimeBucket`).
2. The REST controller only needs the read surface — minimal injectable
   API, easier to mock for service-layer tests.

`JdbcDecisionAuditRepository` hand-rolls the dynamic WHERE clause (one
`appendWhere` method, predicate-by-predicate). We don't pull in jOOQ
for what amounts to eight optional predicates; the abstraction adds
more weight than it saves.

### Replay semantics

`replay(decisionId)`:

1. Load the existing row by id.
2. Reconstruct a `DeliberationRequest` from the stored fields
   (`eventId`, `domain`, `baselineEntityId`, `enrichedEventJson`) plus
   `graphEntityIds` parsed from the JSON.
3. Call the existing M6 `DeliberationClient` bean.
4. Return the fresh `Decision` directly — never persist.

The audit row stays immutable; replay is a "what if" tool. Want to
backfill a row that failed? That's the DLQ replay tool's job
(M6 territory), not the audit API.

### Snake_case wire format

`spring.jackson.property-naming-strategy=SNAKE_CASE` is set globally.
The audit API's `DecisionSummary` / `DecisionDetail` records serialize
their `eventId` / `judgeProvider` fields as `event_id` / `judge_provider`
— exact match with the `decisions.{domain}` Kafka payload M6 already
emits. The dashboard owns ONE type definition for "Decision".

### Error response shape

Two stable error codes:

* `decision_not_found` — 404, body `{"code":"decision_not_found","message":"..."}`
* `invalid_argument` — 400, same shape

The dashboard branches on `code`; `message` is for the user's
diagnostic view. Other exceptions surface as Spring Boot's default
500 — fine; if an exhaustive vocabulary becomes useful, extend the
handler matrix.

## Alternatives considered

### Cursor-based pagination

Considered for the list endpoint. Rejected because offset pagination
is good enough for ~100K decisions (the demo + paper scale), avoids
having to surface an opaque cursor token in the URL, and works with
the dashboard's "page N of M" UI without a second round trip.
Re-evaluate if audit scale grows past a million rows.

### Full-text search on `verdict_explanation_md`

Considered. Rejected for v1 — the dashboard's filter set doesn't ask
for it, and Postgres tsvector indexes would add ~25% to the row size
on a column that's typically 2-4 sentences. Add later if the demo's
analyst workflow needs it.

### Persist replayed decisions

Considered: every replay creates an additional row tagged
`source=replay`. Rejected because it muddies the audit semantics
("which row is the canonical decision for event X?"). The replay
endpoint is for exploration; persisting belongs to the live event
path only.

### One repository for read + write

The path of least dependency cost. Rejected so the read API's
injectable surface stays small and writer changes don't churn the
read tests.

### springdoc-openapi for auto-generated OpenAPI

Considered — the spec lists "OpenAPI spec generated" in the M7
done-criteria. Rejected for the v1 cut because springdoc-openapi 2.x
targets Spring Boot 3 and the 3.x line is milestone-only. Hand-rolled
DTOs + this ADR + the controller javadoc cover the contract for the
dashboard team; revisit when springdoc ships a stable Spring Boot 4
release.

## Consequences

**Wins:**

* The dashboard (M10) has a complete API: list, detail, replay. No
  blocker.
* Replay against a different backend (set `JUDGE_LLM_PROVIDER=ollama`
  on the M5 process, then call replay) gives a concrete A/B story for
  the paper without persisting noise.
* `baseline_entity_id` is now a first-class column AND a query
  parameter — "show me every decision for cardholder-9" is one HTTP
  call, no JSONB pathing required.

**Tradeoffs:**

* Hand-rolled WHERE assembly is fine at this scope but accumulates
  cost as predicates grow. Above ~12 predicates the right move is
  jOOQ or a Specification pattern.
* Replay piggybacks on the M6 `DeliberationClient` bean — when M5 is
  down, replay 503s the same way live deliberation does. That's
  honest behavior but worth a dashboard banner.
* No springdoc yet means the dashboard team works from this ADR + the
  controller javadoc. Acceptable v1; revisit on a Spring Boot 4
  springdoc release.
* The `conclave.orchestrator.enabled` flag now gates M7 too. If a
  future split puts M7 in its own deployment, a separate
  `conclave.audit.enabled` flag may be cleaner.
