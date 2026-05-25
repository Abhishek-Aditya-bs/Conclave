# CONCLAVE — Multi-Agent Real-Time Risk Detection Platform

> **Tagline.** Four specialized agents deliberate over every event and emit a calibrated risk score with a natural-language verdict — fraud-grade for payments, SOC-grade for security, one architecture.

---

## 0. Quick orientation (read first)

**Owner.** Abhishek Aditya — JPMC SE-2 (3 YoE Java/Spring + AI). Actively interviewing for AI-native engineering roles in India and Germany.

**Why this project exists.** One of four portfolio-grade projects. The four together: **WARDEN** (LLM inference platform), **CONCLAVE** (this), **AEGIS** (self-healing coordination), **PRISM** (unified observability + agent eval).

**What "done" means.** Live GitHub repo with: working `docker compose` demo of BOTH reference configurations (fraud + security), polished README with GIF, marketing website, arXiv preprint, 5-7 min Loom video. Engineer-grade shippable, not novel-algorithm research.

**Discipline rules (non-negotiable).**
1. Java 25 + Spring Boot 4.0 on the data plane. Python only for LangGraph agents.
2. No Go. No MCP.
3. The architecture is one architecture; the **two domains (fraud + security) are configurations**, not forks of code.
4. Every README claim backed by a benchmark or demo step.
5. Polish > breadth.
6. Judge LLM **for shipped demos, benchmarks, and the Loom video** is **Claude Haiku 4.5** (`claude-haiku-4-5-20251001`). SDK calls draw from the Anthropic Max-20x Agent SDK credit pool (see §5 cost note); do not silently swap to Opus or Sonnet without updating this spec. A second, **opt-in local-only path via Ollama** is supported for self-hosters who can't or won't use the Anthropic API (see §5 "Local-only mode" and M5 contract) — published benchmark numbers must come from the Haiku run, not the Ollama run.

**Things to NOT do.**
- Don't fork the code per domain. The whole point is one architecture, two configs.
- Don't replace the judge agent with a single LLM call — the deliberation is the contribution.
- Don't add a third domain. Two is enough to prove domain-agnosticism.

---

## 1. Project statement

CONCLAVE is a real-time multi-agent risk detection platform that consumes an event stream (payments, auth events, API calls) and emits explainable decisions with sub-100ms p99. Four specialized agents — **feature extractor**, **behavioral baseliner**, **graph reasoner**, **deliberating judge** — coordinate via LangGraph to produce a calibrated risk score and a human-readable verdict.

Two reference configurations ship in the same repo, demonstrating the architecture is domain-agnostic:

- **Config A — Payment fraud** (Stripe-Radar-style): card-not-present transactions, cardholder/device/IP graph
- **Config B — Security / SOC** (login + lateral-movement style): auth events, identity/host graph

---

## 2. The problem worth solving

Real-time risk detection across fraud, abuse, and security incidents has historically been served by opaque gradient-boosted tree models trained per domain — accurate but uninterpretable, with each domain reimplementing identical streaming and feature plumbing. Recent LLM work has produced post-hoc rationales but not restructured the underlying decision architecture.

CONCLAVE proposes a *multi-agent deliberation* architecture as a first-class alternative: specialized agents reason over a per-event evidence package, the judge issues both a calibrated score and a natural-language verdict, and the **same architecture serves multiple risk domains** through configuration alone.

---

## 3. Why "CONCLAVE"?

A conclave is a private assembly that deliberates to reach a verdict — exactly what the four agents do, once per event. Pronounceable (KON-klayv), unambiguous metaphor, no major product conflicts.

---

## 4. Architecture

### Event lifecycle

```
   ┌────────────────┐        ┌───────────────────────────────────┐
   │ Event Producer │───────►│  Kafka topic: events.{fraud|sec}  │
   └────────────────┘        └────────────────────┬──────────────┘
                                                  │
                              ┌───────────────────▼──────────────────┐
                              │  Feature Extraction Stream Job       │
                              │  (Kafka Streams / Flink)             │
                              │  → events.enriched                   │
                              └───────────────────┬──────────────────┘
                                                  │
                              ┌───────────────────▼──────────────────┐
                              │  CONCLAVE Orchestrator (Spring Boot) │
                              │  consumes enriched event             │
                              └───────────────────┬──────────────────┘
                                                  │
                              ┌───────────────────▼──────────────────┐
                              │  LangGraph Deliberation Graph        │
                              │                                      │
                              │   ┌─────────────────────────────┐    │
                              │   │  Feature Agent              │    │
                              │   │  (summarizes features)      │    │
                              │   └─────────────┬───────────────┘    │
                              │                 │ parallel fanout    │
                              │     ┌───────────┴───────────┐        │
                              │     ▼                       ▼        │
                              │ ┌─────────────┐    ┌─────────────┐   │
                              │ │  Behavioral │    │   Graph     │   │
                              │ │  Baseliner  │    │  Reasoner   │   │
                              │ │ (pgvector)  │    │  (Neo4j)    │   │
                              │ └─────┬───────┘    └──────┬──────┘   │
                              │       │                   │           │
                              │       └─────────┬─────────┘           │
                              │                 ▼                     │
                              │     ┌───────────────────────────┐     │
                              │     │   Judge Agent (Claude)    │     │
                              │     │   verdict + NL reason     │     │
                              │     └─────────────┬─────────────┘     │
                              └───────────────────┼──────────────────┘
                                                  │
                              ┌───────────────────▼──────────────────┐
                              │  Decision Store (Postgres)            │
                              │  + Audit API + Dashboard              │
                              └──────────────────────────────────────┘
```

### Key design decisions (capture as ADRs)

1. **The orchestrator is Java/Spring; the agents are Python/LangGraph.** Java handles the high-throughput Kafka consumer and the decision-API write path. Python handles the agent graph because LangGraph is where stateful agent reasoning shines.
2. **Three context-gathering agents run in parallel; the judge runs after.** Latency-optimal. Each context agent has a strict tool budget (1-2 tool calls max).
3. **Behavioral baseliner uses pgvector embeddings, not statistical baselines.** A per-entity 90-day embedding captures spending pattern / login pattern more flexibly than hand-built stat features.
4. **Graph reasoner uses Cypher templates with bounded depth.** No free-form query generation. Depth-bounded to keep latency predictable.
5. **Judge produces a *calibrated* score, not a binary.** Downstream consumers (Stripe-style step-up auth, SOC triage) need a score, not just block/allow.
6. **Domain-specific code lives only in (a) feature extractors and (b) graph schemas.** Everything else is shared.

---

## 5. Tech stack (locked)

| Layer | Choice | Version | Rationale |
|---|---|---|---|
| Data-plane language | Java | **25 LTS** | Current LTS; virtual threads for Kafka consumers |
| Web framework | Spring Boot | **4.0** (4.0.6+) | Spring Boot 3.5 hits EOL **June 30, 2026**; 4.0 is GA on Spring Framework 7 with JSpecify null-safety, modularized JARs, first-class Java 25 support |
| Stream processing | Kafka Streams | **3.7+** | Lower ops burden than Flink for the demo; Java-native |
| Message bus | Apache Kafka | **3.7+** | Standard |
| Graph DB | Neo4j Community | **5+** | Cypher templates; embedded mode possible for tests |
| Vector store | Postgres + pgvector | PG 16 | Behavioral embeddings |
| Relational store | Postgres | 16 | Decisions, audit, tenants |
| Control-plane language | Python | 3.12+ | LangGraph + Claude Agent SDK |
| Agent orchestration | LangGraph | latest | Stateful graph for the four-agent deliberation |
| Judge LLM (default) | **Claude Haiku 4.5** via Claude Agent SDK | `claude-haiku-4-5-20251001` | Sufficient reasoning for structured-evidence verdicts at ~12× lower cost vs Opus; fits the Max-20x Agent SDK credit pool (see cost note below) |
| Judge LLM (local opt-in) | **Ollama** via `langchain-ollama` | Ollama 0.22.1+ | Self-hosters / privacy-sensitive users; pluggable behind a `JUDGE_LLM_PROVIDER` env var. Recommended models: `qwen3:8b` (most stable tool calling, ~6 GB VRAM), `gemma3:12b` (native function calling + JSON mode), `llama3.3:8b` (general-purpose). 70B+ variants supported but require workstation-class GPU |
| Observability | OpenTelemetry + LangSmith | latest | OTel system, LangSmith agent-specific |
| Dashboard | **Vite + React + Tailwind + shadcn/ui + Framer Motion** | React 19 | Hosted on **Cloudflare Pages** (free tier); dark/light theme with shadcn `zinc` palette (Vercel-inspired black & white); **JetBrains Mono** for code/tabular data, **Geist Sans** for UI |
| Container orchestration | docker-compose | latest | Mac demo |
| Load gen | Java/Spring CLI + custom generators | — | Domain-specific synthetic patterns |
| Paper | LaTeX (ACM sigconf) | — | arXiv |
| Website | **Vite + React + Tailwind + shadcn/ui on Cloudflare Pages** | — | Same stack as dashboard for consistency; static single-page, fully covered by Cloudflare free tier |

### Why **not** Spring AI (call out for reviewers)

Spring AI 1.0 GA shipped May 2025 and 2.0 GA is due May 28, 2026 — but CONCLAVE deliberately keeps the LLM call path in Python. Reasons:

1. The **stateful** four-agent graph (parallel fanout, conditional edges, replay) is exactly what LangGraph is built for; Spring AI's `ChatClient` is single-call oriented.
2. Anthropic's **Claude Agent SDK** is first-party Python — tool-use, prompt caching, streaming, and the new SDK-credit billing path are all most directly exercised there.
3. Keeping the Java side LLM-free preserves the "Java = data plane, Python = control plane" separation that makes the architecture interview-legible.

If a future Spring AI version adds a real stateful-graph primitive, revisit.

### Local-only mode (Ollama)

For users running CONCLAVE entirely on their own hardware (no Anthropic key, no outbound LLM calls), the judge agent in M5 supports an alternative **Ollama** backend selected via env vars:

```bash
JUDGE_LLM_PROVIDER=ollama          # default: anthropic
JUDGE_LLM_MODEL=qwen3:8b           # any tool-calling-capable Ollama model
OLLAMA_BASE_URL=http://localhost:11434
```

Implementation: M5's deliberation graph constructs the judge node through a small `LLMProvider` factory (`anthropic | ollama`). The Claude Agent SDK path uses `claude-haiku-4-5-20251001`; the Ollama path uses `langchain-ollama`'s `ChatOllama` with `format="json"` for the structured verdict and native tool-calling for the optional retrieval tools. The behavioral baseliner (M3) already runs sentence-transformers locally, so the only remote dependency that gets swapped out is the judge.

**Recommended Ollama models** (verified against [Ollama tools index](https://ollama.com/search?c=tools) as of May 2026):

| Model | Tag | VRAM (Q4_K_M) | Notes |
|---|---|---|---|
| **Qwen3 8B** | `qwen3:8b` | ~6 GB | Most stable tool calling; best default for a laptop |
| **Gemma 3 12B** | `gemma3:12b` | ~10 GB | Native function calling + JSON mode; good calibration |
| **Llama 3.3 8B** | `llama3.3:8b` | ~6 GB | General-purpose fallback |
| **Qwen3 32B** | `qwen3:32b` | ~22 GB | Workstation; closer to Haiku-grade reasoning |

**Caveats — surface these clearly in the README and the paper:**

1. **Published benchmarks** (AUC, precision@FPR=1%, human-eval verdict quality) **use the Haiku 4.5 configuration.** Ollama runs will produce different numbers; we do not claim parity.
2. **Score calibration** is empirical and tuned for Claude. A swap to a smaller local model may produce miscalibrated risk scores; the README's "calibration on a held-out 10K-event set" target applies to the Haiku path only.
3. **Latency targets** in M5 (p99 <600ms) assume the Anthropic API. Local inference latency depends entirely on user hardware and is excluded from the SLA.
4. **Tool-use reliability** drops outside Qwen3 / Gemma 3+ / Llama 3.3+. Older or smaller models may emit malformed tool calls; the judge node has a one-shot retry with a stricter prompt before falling back to a no-tool verdict.

`make demo-fraud-local` and `make demo-security-local` boot the same compose stack but set `JUDGE_LLM_PROVIDER=ollama` and start an Ollama sidecar container with `qwen3:8b` pre-pulled.

### Cost note — Anthropic Max-20x Agent SDK credits

Starting **June 15, 2026** Anthropic partitions Max subscription usage into two pools: a first-party pool (Claude.ai chat, Claude Code CLI) and an **Agent SDK credit pool** (third-party tool usage via the Claude API). Max-20x ($200/mo) includes **$200/month of Agent SDK credit**. CONCLAVE's M5 deliberation orchestrator uses the Claude Agent SDK, so judge-agent calls draw from this pool rather than incurring additional API charges on top of the subscription. Combined with Haiku 4.5 as the judge model, demo and benchmark runs should comfortably fit inside the monthly allocation. Refs: [Anthropic billing changes (Zed)](https://zed.dev/blog/anthropic-subscription-changes), [IntuitionLabs pricing breakdown](https://intuitionlabs.ai/articles/claude-pricing-plans-api-costs).

---

## 6. Work breakdown — 10 independently testable modules

Each module: **Contract → Done means → Deps**.

### M1 — Event Schemas & Ingestion
- **Contract.** Avro schemas for two event families: `PaymentEvent` (card-not-present txn) and `AuthEvent` (login/access). Kafka topics: `events.fraud.raw`, `events.security.raw`. Producer SDK in Java for tests.
- **Done.** Schema registry running; producer SDK publishes valid events; round-trip Avro encode/decode test passes.
- **Deps.** None.

### M2 — Feature Extraction Stream Job
- **Contract.** Kafka Streams topology: raw event → enriched event with computed features (velocity, geo distance, BIN risk, baseline behavioral embedding lookup ID, graph entity IDs). One topology per domain, both defined by a `FeatureSpec` interface so the topology shell is shared.
- **Done.** End-to-end test: publish 1000 raw events, observe enriched output on `events.{domain}.enriched`. Backpressure under load works.
- **Deps.** M1.

### M3 — Behavioral Baseline Service
- **Contract.** REST + gRPC service: `getBaseline(entityId, domain) → Embedding` and `updateBaseline(entityId, event)`. Backed by pgvector. Per-entity 90-day rolling embedding computed via sentence-transformers (or OpenAI embeddings) over textualized event sequences.
- **Done.** Unit tests for baseline computation; integration test over a 90-day synthetic stream; p99 lookup <20ms.
- **Deps.** None.

### M4 — Graph Reasoner Service
- **Contract.** Wraps Neo4j with a fixed set of Cypher templates per domain (`fraud_neighborhood`, `auth_lateral_paths`, etc.). Returns structured `GraphFinding` records. Depth-bounded, latency-bounded.
- **Done.** Templates documented; unit tests against an in-memory graph seed; p99 query <50ms on a 1M-edge graph.
- **Deps.** None.

### M5 — LangGraph Deliberation Orchestrator (Python sidecar)
- **Contract.** LangGraph state graph: feature → (parallel: behavioral baseliner | graph reasoner) → judge. Judge LLM is **pluggable via a `LLMProvider` factory** keyed off `JUDGE_LLM_PROVIDER` (default `anthropic` → Claude Agent SDK + Haiku 4.5; alternative `ollama` → `langchain-ollama` against a local Ollama server, see §5 "Local-only mode"). Output: `Decision { score [0,1], verdict_label, verdict_explanation_md, contributing_factors[] }`. Exposed via gRPC.
- **Done.** Given a canned enriched event, the graph returns a decision with all fields populated against **both** backends (Anthropic and at least one Ollama model, `qwen3:8b`). Latency target p99 <600ms applies to the Anthropic path only; Ollama path is hardware-dependent and excluded from the SLA. Provider switch is covered by integration tests.
- **Deps.** M3, M4.

### M6 — Decision Orchestrator (Java/Spring)
- **Contract.** Consumes `events.{domain}.enriched`, calls M5 over gRPC, persists decision to Postgres, emits `decisions.{domain}` topic for downstream consumers.
- **Done.** End-to-end: raw event in → decision row written + decision event emitted. Failure modes (M5 timeout, DB down) gracefully degrade.
- **Deps.** M5.

### M7 — Audit & Decision API
- **Contract.** REST API: list decisions (filter by score range, time, entity), get decision detail (full evidence package + judge reasoning), replay decision (re-run the deliberation on the original evidence).
- **Done.** OpenAPI spec generated; demo dashboard consumes the API.
- **Deps.** M6.

### M8 — Reference Configurations: Fraud + Security
- **Contract.** Two complete configurations:
  - **Fraud** — payment event schema, BIN/velocity/geo features, cardholder-device-IP graph schema, fraud-specific Cypher templates
  - **Security** — auth event schema, identity/host features, identity-host-resource graph schema, lateral-movement Cypher templates
- **Done.** Both configurations boot from the same binary via env vars. End-to-end demo runs in both.
- **Deps.** M2, M4.

### M9 — Synthetic Data Generators
- **Contract.** Two generators with realistic adversarial patterns:
  - **Fraud generator** — clean txns + card-testing rings + ATO patterns + bust-out fraud
  - **Security generator** — clean auth + lateral movement + exfiltration patterns + ATO
- **Done.** Generators produce labeled streams; ground-truth labels available for eval.
- **Deps.** M1.

### M10 — Audit Dashboard + Demo Harness
- **Contract.** **Vite + React + Tailwind + shadcn/ui** dashboard: incoming events stream, decisions feed, decision-detail view (evidence + verdict + judge reasoning), score histogram. `make demo-fraud` and `make demo-security` switch configurations. Deployed to **Cloudflare Pages** (the live preview); local dev via `pnpm dev`.
- **Done.** `git clone && make demo-fraud` runs end-to-end in <5 min on a fresh Mac. Cloudflare Pages preview deploys on every push.
- **Deps.** All previous.

---

## 7. Demo plan

### The video (5-7 min, Loom)

**Part 1 — Fraud config (3 min).**
1. Open dashboard; show clean event stream.
2. Run fraud generator with card-testing burst; watch CONCLAVE flag the ring.
3. Click into a flagged decision; show the four-agent evidence + judge verdict + plain-English reasoning.
4. Show the graph view of the cardholder-device-IP subgraph that drove the decision.

**Part 2 — Same architecture, security config (3 min).**
1. `make switch-config security`.
2. Run security generator with lateral-movement scenario; watch CONCLAVE flag.
3. Click into the decision; show how the *same architecture* now reasons over auth events.

**Part 3 — Close (1 min).**
- Architecture diagram. "One architecture, two domains, four deliberating agents."

### Scripted demo scenarios

| Scenario | What it shows |
|---|---|
| `scenarios/fraud-card-testing.sh` | Ring of low-value test charges across many cards from one device |
| `scenarios/fraud-ato.sh` | Account takeover: legitimate user pattern broken |
| `scenarios/security-lateral.sh` | Login → SSH → privilege escalation chain |
| `scenarios/security-exfil.sh` | Unusual data volume to external endpoint |

### Datasets

- **IEEE-CIS Fraud Detection** (Kaggle) — baseline comparison for fraud config
- **BETH (BPF Extended Tracking Honeypot)** dataset — baseline for security config
- Synthetic adversarial streams from M9

---

## 8. Website plan

### Single-page structure

1. **Hero.** "Multi-agent risk detection. One architecture, multiple domains." Animated diagram of the four-agent deliberation.
2. **The Problem.** Tabular models are accurate but opaque; per-domain pipelines are redundant.
3. **How It Works.** Animated event-to-decision lifecycle.
4. **Side-by-Side Configs.** Two cards: fraud + security, both running on the same architecture.
5. **Benchmarks.** Score-AUC vs. XGBoost baseline (both domains); explanation-usefulness human rating; p99 latency.
6. **Read the Paper.** arXiv link + embedded PDF.
7. **Quickstart.** `make demo-fraud` / `make demo-security`.
8. **Footer.** Built by + MIT license.

### Tech
- **Vite + React 19 + Tailwind + shadcn/ui + Framer Motion** (same stack as the M10 dashboard for consistency)
- **JetBrains Mono** for code blocks and tabular data (the IntelliJ font); **Geist Sans** for UI text; both via Fontsource so they bundle offline-first
- Vercel-inspired neutral palette: shadcn `zinc` scale, near-black `#0a0a0a` background in dark mode, near-white `#fafafa` in light mode, with a single accent color reserved for risk-score gradients only
- Dark mode default, animated toggle in the header
- **Deploy: Cloudflare Pages** — single static SPA, free tier covers all expected traffic, custom domain via Cloudflare DNS

### Aesthetic reference
[Modal](https://modal.com), [Datadog's product pages](https://datadoghq.com), [Wiz](https://wiz.io). Technical, dense, no fluff.

---

## 9. Research paper plan

### Title
*CONCLAVE: A Domain-Agnostic Multi-Agent Architecture for Real-Time Explainable Risk Detection*

### Target venues
- **Primary:** arXiv (cs.LG or cs.CR)
- **Stretch:** USENIX Security workshop, FinML workshop at NeurIPS / ICML, KDD applied track
- 10-12 pages, ACM sigconf

### Sections

1. **Introduction.** The tabular-model-and-rationale paradigm; the case for first-class multi-agent deliberation.
2. **Background & related work.** Stripe Radar architecture, payment fraud ML literature, SOC anomaly detection, multi-agent LLM systems (CrewAI, AutoGen), explainable AI for risk.
3. **System design.** Four-agent architecture, deliberation graph, evidence package, calibration approach.
4. **Implementation.** Java 25 + Spring Boot + Kafka Streams + LangGraph + Neo4j + pgvector; LOC; deployment footprint.
5. **Evaluation.**
   - Detection: AUC + precision@FPR=1% on IEEE-CIS fraud and a streaming reformulation of BETH; comparison vs. XGBoost baseline and vs. single-LLM rationale baseline.
   - Explainability: human eval of verdict quality (Likert, with 10-20 raters).
   - Latency: p50/p99 end-to-end and per agent.
   - Cross-domain transfer: training feature extractor on fraud, swapping for security — show common backbone holds.
6. **Limitations & honesty.** Single LLM judge as bottleneck; calibration is empirical not theoretical; sample size for human eval.
7. **Conclusion & future work.**

### Honest framing
**Engineering paper, not novel-algorithm paper.** Contribution: architecture pattern + open-source artifact. Frame this clearly.

---

## 10. Repository layout

```
conclave/
├── README.md                       # Pitch + GIF + quickstart + benchmarks
├── DESIGN.md                       # Architecture + decisions
├── docker-compose.yml              # Full stack (Kafka + Neo4j + PG + dashboard)
├── Makefile                        # demo-fraud, demo-security, eval targets
├── .github/workflows/              # CI
├── orchestrator/                   # Java/Spring service
│   ├── pom.xml
│   ├── src/main/java/io/conclave/
│   │   ├── ingest/                 # M1: schemas + Kafka producers
│   │   ├── stream/                 # M2: feature extraction topology
│   │   ├── orchestrator/           # M6: decision orchestrator
│   │   ├── audit/                  # M7: audit + decision API
│   │   └── observability/
│   └── src/test/java/
├── baseline/                       # Java baseline service
│   └── src/main/java/io/conclave/baseline/   # M3
├── graph/                          # Java graph reasoner service
│   └── src/main/java/io/conclave/graph/      # M4
├── agents/                         # Python LangGraph deliberation
│   ├── pyproject.toml
│   └── deliberation/               # M5
├── configs/                        # M8: domain configurations
│   ├── fraud/
│   │   ├── schema.avsc
│   │   ├── features.yaml
│   │   └── graph-templates.cypher
│   └── security/
│       ├── schema.avsc
│       ├── features.yaml
│       └── graph-templates.cypher
├── generators/                     # M9: synthetic data
│   ├── fraud/
│   └── security/
├── dashboard/                      # M10: Vite + React + shadcn/ui (Cloudflare Pages)
├── benchmark/                      # eval scripts, dataset adapters
├── docs/                           # ADRs, diagrams
├── paper/                          # arXiv LaTeX
└── website/
```

---

## 11. Acceptance criteria — when is CONCLAVE "done"?

- [ ] `git clone && make demo-fraud` works on fresh Mac in <5 min (default Anthropic + Haiku 4.5 backend)
- [ ] `make switch-config security && make demo-security` works
- [ ] `make demo-fraud-local` works on a fresh Mac with an Ollama sidecar (`qwen3:8b` auto-pulled) and no Anthropic key required
- [ ] All 10 modules have green CI
- [ ] README has: pitch, two GIFs (fraud + security), benchmarks table, paper link, video link
- [ ] arXiv preprint posted
- [ ] Marketing website live
- [ ] 5-7 min Loom video
- [ ] DESIGN.md with at least 4 ADRs
- [ ] Resume bullet drafted (capability-focused: "multi-agent real-time risk platform processing X events/sec with explainable decisions across fraud and security domains")

---

## 12. Out of scope (do NOT build)

- A third domain configuration. Two is the proof.
- Online learning / model updates from decisions. The judge LLM weights are not fine-tuned by CONCLAVE — only the *backend provider* is configurable (see M5).
- A graph DB UI. Use Neo4j Browser if needed.
- Tenant multi-tenancy. Single tenant.
- Production-grade auth.
- A cloud-hosted demo. Local docker-compose only.
- MCP server bindings.

---

## 13. Key references

**Comparable systems (study, don't copy).**
- Stripe Radar — public talks + https://stripe.com/radar
- AWS Fraud Detector
- Open-source: Feathr, Hopsworks (feature stores)

**Datasets.**
- IEEE-CIS Fraud Detection — Kaggle
- BETH (BPF Extended Tracking Honeypot) — security telemetry
- PaySim — synthetic payment data

**Papers.**
- "Practical Approach for High-Performance Fraud Detection" — Stripe engineering blog
- "Explainable AI for risk" survey literature
- Multi-agent LLM papers: AutoGen (arXiv 2308.08155), CrewAI documentation

**Frameworks.**
- Spring Boot — https://spring.io
- Kafka Streams — https://kafka.apache.org/documentation/streams/
- Neo4j + Cypher — https://neo4j.com
- LangGraph — https://langchain-ai.github.io/langgraph/
- Claude Agent SDK — https://github.com/anthropics/claude-agent-sdk-python
- pgvector — https://github.com/pgvector/pgvector

---

**End of spec.** A fresh Claude session reading this should be able to start on M1 immediately. Open this file, read it once, ask zero clarifying questions, ship.
