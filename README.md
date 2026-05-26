# CONCLAVE

> **Multi-agent real-time risk detection. One architecture, multiple domains.**

Four specialized agents — *feature extractor*, *behavioral baseliner*, *graph
reasoner*, *deliberating judge* — coordinate via LangGraph to produce a calibrated
risk score and a human-readable verdict for every event on the stream. The same
architecture ships two reference configurations:

- **Payment fraud** (Stripe-Radar style): card-not-present transactions, cardholder
  / device / IP graph.
- **Security / SOC** (lateral-movement style): auth events, identity / host /
  resource graph.

For the full architecture contract see [spec.md](spec.md). For session-by-session
delivery history see [PROGRESS.md](PROGRESS.md). For the architectural decision log
see [DESIGN.md](DESIGN.md).

---

## Quickstart

```bash
# Prereqs: JDK 25, Maven 3.9+, Docker Desktop, uv (Python toolchain), Node 20+.

git clone https://github.com/<you>/conclave && cd conclave
cp .env.example .env             # fill in ANTHROPIC_API_KEY

make demo-fraud                  # builds + boots the full stack, fires a starter burst
open http://localhost:8080/api/v1/decisions

make demo-stop
make demo-security               # same images, SPRING_PROFILES_ACTIVE=security
```

No Anthropic key? `make demo-fraud-local` boots an Ollama sidecar with
`qwen3:8b` (~6 GB cached on first run) — spec §5 "Local-only mode".

To browse decisions in a UI, in a second terminal: `make dashboard-dev`
opens [http://localhost:5173](http://localhost:5173) and proxies `/api`
to the orchestrator.

---

## Repository layout

```
conclave/
├── spec.md                  contract — read first
├── PROGRESS.md              session log + module status
├── DESIGN.md                ADR index
├── docs/adr/                ADR-001 through ADR-009
├── configs/                 Avro schemas + feature/graph configs per domain
├── orchestrator/            Java/Spring (M1 ingest, M2 stream, M6 orchestrator, M7 audit)
├── baseline/                Java/Spring (M3 — pgvector + langchain4j embeddings)
├── graph/                   Java/Spring (M4 — Neo4j + Cypher templates)
├── agents/                  Python/uv (M5 — LangGraph deliberation, Anthropic | Ollama)
├── generators/              Java (M9 — labeled synthetic streams)
├── dashboard/               Vite + React + Tailwind (M10 — audit UI)
├── website/                 Vite + React + Tailwind (marketing site)
├── docker/                  Per-service Dockerfiles
├── docker-compose.yml       Full stack (Kafka + SR + PG + Neo4j + services)
├── docker-compose.ollama.yml  Local-only overlay (qwen3:8b sidecar)
└── Makefile                 build / test / demo targets
```

---

## Modules (M1 – M10)

| Module | Status | Highlights |
|---|---|---|
| [M1 — Event schemas + ingestion](orchestrator/src/main/java/io/conclave/ingest/) | ✅ | Avro `PaymentEvent` + `AuthEvent`; idempotent + acks=all Kafka producer SDK |
| [M2 — Feature extraction stream](orchestrator/src/main/java/io/conclave/stream/) | ✅ | `FeatureSpec` interface; per-domain stateful enrichment via Kafka Streams |
| [M3 — Behavioral baseline service](baseline/) | ✅ | pgvector + in-JVM MiniLM-L6-v2 embeddings; **p99 lookup = 0.74 ms** |
| [M4 — Graph reasoner service](graph/) | ✅ | Neo4j + 4 fixed Cypher templates; **p99 query = 6 ms** on 100K-edge graph |
| [M5 — LangGraph deliberation](agents/) | ✅ | feature → (baseliner ∥ graph_reasoner) → judge; Haiku 4.5 or Ollama |
| [M6 — Decision orchestrator](orchestrator/src/main/java/io/conclave/orchestrator/) | ✅ | enriched → M5 gRPC → Postgres → `decisions.{domain}`; DLQ on failure |
| [M7 — Audit & decision API](orchestrator/src/main/java/io/conclave/audit/) | ✅ | `GET/POST /api/v1/decisions(/{id}(/replay))`; snake_case wire shape |
| [M8 — Reference configurations](docker-compose.yml) | ✅ | one compose stack, two domains via `SPRING_PROFILES_ACTIVE` |
| [M9 — Synthetic data generators](generators/) | ✅ | 4 + 4 adversarial scenarios; ground-truth labels on side topic |
| [M10 — Dashboard + website](dashboard/) | ✅ | Vite + React + Tailwind; consumes the M7 API |

Last green build: **219/219 Java tests + 99/99 Python tests** across 6 modules;
both frontend bundles build clean. See PROGRESS.md for per-module coverage.

---

## Acceptance criteria (spec §11)

| Criterion | Status |
|---|---|
| `git clone && make demo-fraud` works on a fresh Mac in <5 min | ✅ (depends on local network for image pulls) |
| `make demo-security` switches domains | ✅ |
| `make demo-fraud-local` boots Ollama sidecar (no API key required) | ✅ |
| All 10 modules have green CI | ✅ |
| README with pitch + quickstart | ✅ (this file) |
| DESIGN.md with at least 4 ADRs | ✅ (9 ADRs) |
| README GIFs (fraud + security demos) | ⏳ pending demo recording |
| arXiv preprint | ⏳ post-eval |
| Marketing website live | ✅ [conclave-4q9.pages.dev](https://conclave-4q9.pages.dev) — Cloudflare Pages; redeploy via `make website-deploy` |
| 5-7 min Loom video | ⏳ pending demo recording |
| Resume bullet | ⏳ |

---

## Developer commands

```bash
make help                 # list every target
make verify               # full Java build + tests + coverage gate
make m5-test              # Python tests
make m9-verify            # generators unit + integration tests
make demo-build           # mvn package + docker compose build (no boot)
make demo-fraud           # full demo, fraud domain
make demo-security        # full demo, security domain
make demo-fraud-local     # full demo, Ollama judge
make demo-stop            # stop the stack (keeps volumes)
make demo-logs            # tail logs from the four services
make dashboard-dev        # Vite dev server on :5173 (proxies /api → :8080)
make website-build        # static build of marketing site
```

---

## License

MIT. See [LICENSE](LICENSE) (TODO: file pending). Spec discipline rules in
[spec.md §0](spec.md#0-quick-orientation-read-first).
