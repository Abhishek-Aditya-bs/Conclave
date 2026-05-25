# ADR-003: Graph reasoner — schema, template strategy, depth bound

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-05-25 |
| **Module** | M4 — Graph Reasoner Service |
| **Spec references** | [spec.md §6 M4 contract](../../spec.md), [§4 design decision #4](../../spec.md) |

---

## Context

The judge agent (M5) needs structured graph-based evidence to reason about whether an
event looks suspicious. We need: a fixed schema per domain, a small set of queries that
surface specific patterns, and tight latency guarantees so the per-event reasoning
loop stays under its overall budget. Three independent decisions:

1. **Schema** — what nodes and relationships exist per domain.
2. **Template strategy** — fixed templates vs. LLM-generated queries.
3. **Driver** — raw `neo4j-java-driver` vs. Spring Data Neo4j vs. embedded Neo4j.

## Decisions

### 1. Schema

Two domain schemas. Both are anchor-on-a-stable-entity-id designs: every template
roots its MATCH at a single indexed node and walks outward.

**Fraud:**

```
(:Cardholder {id})       --[:USED_DEVICE]--> (:Device {fingerprint})
(:Cardholder {id})       --[:OWNS]-->         (:Card {token})
(:Card {token})          --[:USED_AT]-->      (:Merchant {id, mcc, country})
(:Cardholder {id})       --[:USED_IP]-->      (:Ip {address})
```

**Security:**

```
(:Principal {id})        --[:ACCESSED]-->     (:Host {id, role})
(:Principal {id})        --[:ACCESSED]-->     (:Resource {id, sensitivity})
(:Principal {id})        --[:FROM]-->         (:Ip {address})
(:Host {id})             --[:HOSTS]-->        (:Resource {id})
```

Indexes (via `SchemaInitializer`):
- Fraud: `Cardholder.id`, `Device.fingerprint`, `Card.token`, `Merchant.id`, `Ip.address`
- Security: `Principal.id`, `Host.id`, `Resource.id`

These are the only properties the templates query; secondary indexes are deferred
until concrete query patterns appear.

### 2. Fixed templates, not free-form Cypher

The spec is explicit: "Wraps Neo4j with a fixed set of Cypher templates per domain."
We honour this with four templates in M4:

| Template | Domain | Risk lens | Depth |
|---|---|---|---|
| `fraud_card_testing_ring` | fraud | device → many cardholders/cards | 2 hops |
| `fraud_cardholder_neighborhood` | fraud | descriptive context for the judge | 2 hops |
| `security_lateral_movement` | security | one principal → many hosts | 1 hop |
| `security_privileged_access` | security | sensitive-resource access | 1 hop |

Every Cypher string is a compile-time constant. Params are passed via `$name`
placeholders. Path lengths are explicit (`*1..2`, never unbounded) — see the
`GraphTemplate` interface contract.

Rejected: dynamic / LLM-generated Cypher. Two reasons:
- **Predictable latency**: bounded queries with indexed anchor points consistently
  clear the 50ms p99 budget. Free-form queries do not.
- **Auditability**: every template lives in code, is unit-testable, and its
  description is surfaced via `list_templates`. The judge agent's evidence
  trail names which template fired, which the auditor can trace back to a
  reviewable query.

### 3. Raw neo4j-java-driver, not Spring Data Neo4j

Same logic as M3's choice of raw `JdbcTemplate` over JPA: the service surface is
"run one of N pre-written queries." Spring Data's OGM and repository abstractions
add complexity and unpredictable query rewriting without buying us anything for
this access pattern.

The driver is wired as a single `Driver` bean in `Neo4jConfig`. Each template call
opens a fresh `Session` (cheap) and lets the driver pool the underlying connection.

## Consequences

**Positive**
- p99 query latency: **6ms** measured against a 100,000-edge graph (5,000
  cardholders × 5,000 devices), 200 queries. Spec budget is 50ms; we're **~8× under**.
  The graph indexed-lookup pattern is essentially flat above ~10K nodes, so the
  result generalizes to the 1M-edge target.
- Adding a new template = one `@Component` + one fixed Cypher string + indexes
  (if not already present) + a unit/IT pair. No service-layer changes.
- Risk signals are pure functions of query output, so they're easy to tune and
  unit-test exhaustively (boundary cases).
- The 2-hop bound on the neighborhood template is the natural ceiling for the
  judge's evidence package — 3-hop traversals would explode in result size for
  high-degree entities (a single popular merchant) and bring latency back into
  the 100ms range.

**Negative**
- No similarity / nearest-neighbor queries yet. If a future use case needs
  "find principals whose access pattern resembles this one," we'll need either
  Neo4j Bloom or a separate vector store. Out of scope for M4.
- Schema migrations require ADR amendments — the indexes live in code, not
  in a migration tool. Acceptable for the demo; would graduate to Liquibase
  Neo4j / a manual schema-management process in production.
- Per-template seed data lives in the ITs. If reviewers want to replay a
  finding manually they need to peek at the IT seed script. Could add a
  Neo4j Browser bookmark or a `make seed-graph` target later.

## Alternatives rejected

- **Spring Data Neo4j (OGM)** — over-engineered for fixed-template access.
- **Embedded Neo4j** — fine for tests, but tests already use Testcontainers and
  prod needs a real server; running an embedded engine in the service JVM adds
  a runtime dependency for no gain.
- **LLM-generated Cypher** — predictable-latency + auditability win clearly here.
- **GraphQL over Cypher** — different layering, doesn't address the latency
  bound, adds another schema surface to maintain.

## References

- [Neo4j Java Driver docs](https://neo4j.com/docs/java-manual/current/)
- spec.md §4 (architecture), §6 M4 (contract)
- [GraphLatencyIT measured p50=3ms, p95=4ms, p99=6ms, max=7ms on a ~100K-edge graph]
