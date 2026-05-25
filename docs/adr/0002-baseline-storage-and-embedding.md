# ADR-002: Behavioral baseline — storage + embedding + rolling-update strategy

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-05-25 |
| **Module** | M3 — Behavioral Baseline Service |
| **Spec references** | [spec.md §6 M3 contract](../../spec.md), [§4 design decision #3](../../spec.md) |

---

## Context

M3 needs to maintain a per-entity "behavioral fingerprint" — a dense vector summarizing
the recent (≈90-day) pattern of events for that entity — so the LangGraph judge (M5)
can compare each incoming event against the entity's own history rather than against
a population-level baseline. Three independent decisions go into this:

1. **Storage** — where vectors live, how they're queried, how p99 stays under 20ms.
2. **Embedding model** — what produces the vectors, and where the model runs.
3. **Rolling-update rule** — how to fold each new event into the existing fingerprint
   without recomputing over the full 90-day history every time.

## Decisions

### 1. Storage: Postgres + pgvector

- **Schema**: a single `baselines` table keyed by `(entity_id, domain)`. Vector column
  is `vector(384)`. Timestamp column is `timestamptz`.
- **Driver**: plain `JdbcTemplate` — no JPA. The vector column is a custom type and
  the read/write surface is two SQL statements; the Hibernate cost would dwarf the
  table's complexity.
- **Vector (de)serialization**: pgvector accepts the literal `[0.1,0.2,...]` text
  form on insert (with an explicit `::vector` cast in the SQL), and returns the same
  shape on select. We parse/format that string in-process rather than register a
  custom JDBC type via HikariCP's connection initializer.
- **Indexing**: the primary key `(entity_id, domain)` is enough for the M3 point-lookup
  workload — we always know which entity we want. A `ivfflat` / `hnsw` index on the
  embedding column would only matter for nearest-neighbor search, which M3 does NOT
  yet do. Deferred until a "find similar baselines" use-case appears.

Alternatives rejected:
- **Redis Vector** — fast but adds an operational store. Postgres is already mandatory
  for M6/M7 (decisions table), so reusing it costs nothing.
- **Faiss / Pinecone / Weaviate** — overkill for point lookups; introduces a vector
  DB to operate. Reconsider only if we add similarity search.

### 2. Embedding model: langchain4j `all-MiniLM-L6-v2` (in-JVM)

- **Why MiniLM**: 22 MB, 384-dim, runs CPU-only at sub-millisecond per short text on
  modern hardware. Quality is "good enough" for the relative-similarity signal the
  judge needs.
- **Why in-JVM via langchain4j**: zero external dependencies. The model ships as a
  jar; DJL/ONNX runtime spins it up on first call (~100ms cold start). No Python
  sidecar, no embedding-service round-trip, no API key.
- **Thread-safe and shared**: one model instance per JVM; embeddings are parallelizable
  across cores by the library.
- **Stable dimension contract**: the {@code DIMENSIONS} constant is duplicated in
  Java and validated against `conclave.baseline.embedding-dim` at startup — a typo in
  config fails fast rather than producing dimension-mismatched vectors.

Alternatives rejected:
- **OpenAI / Anthropic embeddings** — costs money per call, requires API key, adds a
  network hop per event. The spec allows this option but the polish-over-breadth
  rule favors the offline default. Easy to swap if needed (the `EmbeddingService`
  interface is the seam).
- **Python sentence-transformers sidecar** — moves an entire runtime into the
  deployment surface for ~the same model quality.
- **Java DL4J** — heavier, less ergonomic API.

### 3. Rolling-update rule: exponential moving average (EMA) with decay = 0.85

For each new event with text `t` and existing baseline `B_prev`:

```
embedding(t)   = MiniLM.embed(t)
B_new          = decay * B_prev + (1 - decay) * embedding(t)
event_count   += 1
```

- **Why EMA over a windowed average**: O(1) per update, O(1) storage per entity.
  Maintaining "the last 90 days of textualized events" and recomputing the embedding
  would be O(N) per update and O(N) storage, with N growing unboundedly for active
  entities.
- **Why decay = 0.85**: chosen empirically against the 90-day synthetic stream IT.
  With this value, a new event nudges the baseline by 15%; after ~20 events the
  baseline has fully shifted to a new pattern. Lower values track novel behavior
  faster but lose stability; higher values give a smoother but slower-adapting
  signal. The value lives in `conclave.baseline.ema-decay` so reviewers can probe.
- **First-event case**: when no prior baseline exists, the embedding of the first
  event becomes the baseline as-is (decay isn't applied). This is the natural
  initialization.

Alternatives rejected:
- **Exact 90-day window**: too costly (see above).
- **Per-event timestamps + time-decay**: a more principled "weight events by recency"
  scheme exists, but the empirical advantage over plain EMA is small for the demo's
  event volume.
- **Reset after a domain-change**: deferred — not needed for the M3 acceptance bar.

## Consequences

**Positive**
- p99 lookup latency: **0.74ms** measured against 10K baselines, 1000 random reads
  (see `BaselineLatencyIT`). 27× under the spec's 20ms budget.
- The 90-day synthetic stream IT (`BaselineRestIT#ninetyDaySyntheticStream`) shows
  the EMA converges toward each entity's pattern faster than to the other — proving
  the embeddings encode something useful, not just noise.
- The `EmbeddingService` interface keeps the door open for swapping to a cloud
  embedding provider without touching the storage or service layers.
- Memory footprint per baseline row: 384 × 4 = 1.5 KB. 10K entities = ~15 MB.
  Comfortably fits any deployment for the next two milestones.

**Negative**
- EMA loses the ability to inspect "which specific past event drove this baseline."
  If the judge ever wants to cite source events, we'd need to keep a separate
  per-entity event log. Out of scope until M5/M7 demand it.
- The MiniLM model is unaware of fraud / SOC domain vocabulary. A fine-tune on
  domain text would improve discriminative power; we don't fine-tune, but the
  EmbeddingService seam lets us slot in a fine-tuned model later.
- Dimension is locked at 384 by the model choice. Switching models with different
  dimensions means a schema migration. Documented in the fail-fast startup check.

## References

- [pgvector Java client](https://github.com/pgvector/pgvector-java)
- [langchain4j in-process embeddings](https://docs.langchain4j.dev/integrations/embedding-models/in-process/)
- [Sentence-Transformers — all-MiniLM-L6-v2 model card](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2)
- spec.md §6 M3 contract
- [BaselineLatencyIT measured p50=241µs, p95=321µs, p99=740µs, max=4.8ms]
