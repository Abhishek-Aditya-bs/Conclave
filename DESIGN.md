# CONCLAVE — Design

This file is the architectural decision log for CONCLAVE. The
authoritative system contract is [spec.md](spec.md); this file points
at the per-decision ADRs under [docs/adr/](docs/adr/) and gives a
short orientation for each.

For session-by-session delivery history see [PROGRESS.md](PROGRESS.md).
For in-flight notes and gotchas see [SCRATCHPAD.md](SCRATCHPAD.md).

---

## The architectural shape (spec §4 in one page)

```
        events.{fraud|security}.raw
                    │
                    ▼
         M2 Feature Extraction (Kafka Streams)
                    │
                    ▼
        events.{fraud|security}.enriched
                    │
                    ▼
         M6 Decision Orchestrator (Spring/Java)
                    │
                    ▼     (gRPC)
         M5 LangGraph Deliberation (Python)
                    │
              ┌─────┴─────┐
              ▼           ▼   (parallel)
         M3 Baseline   M4 Graph Reasoner
         (pgvector)    (Neo4j)
              │           │
              └─────┬─────┘
                    ▼
                  Judge
            (Claude Haiku 4.5
             or Ollama)
                    │
                    ▼
         Postgres `decisions` table
                    │
                    ├──► decisions.{fraud|security}   (Kafka, for downstream consumers)
                    └──► M7 Audit API (REST)
                             │
                             ▼
                       M10 Dashboard
```

Java holds the **data plane** (stream processing, state stores, the gRPC
client to M5, the decision write path, the audit API). Python holds the
**control plane** (the LangGraph deliberation, the judge LLM call).
Domain-specific code lives only in (a) feature extractors and (b) graph
schemas/templates — everything else is shared.

---

## Architectural decision records

| ADR | Topic | Module |
|---|---|---|
| [ADR-001](docs/adr/0001-feature-spec-abstraction.md) | `FeatureSpec` abstraction + shared Kafka Streams topology shell | M2 |
| [ADR-002](docs/adr/0002-baseline-storage-and-embedding.md) | Postgres + pgvector storage; in-JVM langchain4j MiniLM embeddings; EMA rolling update | M3 |
| [ADR-003](docs/adr/0003-graph-templates-and-schema.md) | Fixed Cypher templates, depth-bounded; per-domain graph schemas; raw Neo4j driver | M4 |
| [ADR-004](docs/adr/0004-judge-llm-provider-factory.md) | `LLMProvider` factory: Anthropic (Haiku 4.5, tool-use) vs Ollama (`format=json`); shared parser | M5 |
| [ADR-005](docs/adr/0005-decision-persistence-and-dlq.md) | Decisions JSONB schema; six failure-reason DLQ codes; verbatim event payload | M6 |
| [ADR-006](docs/adr/0006-audit-api-surface.md) | `GET/POST /api/v1/decisions` surface; read/write repo split; replay-without-persist semantics; snake_case | M7 |
| [ADR-007](docs/adr/0007-synthetic-data-generators.md) | No-Spring CLIs; ground-truth labels on side topic (leakage-proof); `Scenario` interface | M9 |
| [ADR-008](docs/adr/0008-compose-demo-harness.md) | One compose file (env-switched), Ollama overlay, pre-built fat jars, KRaft dual-listener | M8 |
| [ADR-009](docs/adr/0009-dashboard-tech-choices.md) | Tailwind v4 CSS-first, shadcn primitives inlined, TanStack Query polling, no Framer Motion | M10 |

Spec §0 calls for at least 4 ADRs; we have 9.

---

## Cross-cutting invariants

These hold across every module. Violating any of them is a spec break.

1. **Java 25 + Spring Boot 4.0** on the data plane. Python only for the
   M5 LangGraph agents.
2. **No Go. No MCP.** Spec §0 rule #2.
3. **One architecture, two configurations.** No domain-forked code.
   Domain-specific lives only in feature extractors + graph schemas.
4. **Coverage thresholds enforced at the build:** 80 % line / 70 %
   branch (JaCoCo); 80 % line (pytest-cov). Both fail the build.
5. **Judge LLM for published runs = Claude Haiku 4.5.** Spec §0 rule #6.
   The Ollama path is opt-in for self-hosters; its numbers are not
   benchmark-quotable.
6. **Wire format = snake_case.** M6's Kafka decisions payload and M7's
   REST DTOs share the same shape so the dashboard owns one type
   definition.
7. **Ground-truth labels never reach the orchestrator.** M9's labels
   ship on `events.{domain}.labels`; M2/M6 never subscribe.

---

## Where to read the contract

If you have 10 minutes: [spec.md](spec.md) §§ 1, 4, 6.

If you have 30 minutes: spec.md + the ADRs that map to whichever module
you're touching (table above).

If you have a green field and need to extend the system: open an ADR
**before** writing the code, draft the decision + rejected alternatives,
then implement. ADR-001 through ADR-009 are templates you can copy.
