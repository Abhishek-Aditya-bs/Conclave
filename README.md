<p align="center">
  <img src="assets/hero.svg" alt="CONCLAVE — multi-agent real-time risk detection" width="100%">
</p>

<h1 align="center">CONCLAVE</h1>

<p align="center">
  <b>Real-time risk decisions that are accurate, relational, and explainable.</b><br>
  Four specialized agents deliberate over every event and return a calibrated verdict — with the reasoning attached.
</p>

<p align="center">
  <a href="https://conclave-4q9.pages.dev"><b>🌐 Live site</b></a> &nbsp;·&nbsp;
  <a href="#-quickstart"><b>Quickstart</b></a> &nbsp;·&nbsp;
  <a href="#-how-it-works"><b>How it works</b></a> &nbsp;·&nbsp;
  <a href="#-architecture"><b>Architecture</b></a> &nbsp;·&nbsp;
  <a href="#-one-architecture-two-domains"><b>Domains</b></a> &nbsp;·&nbsp;
  <a href="#-whats-novel"><b>Novelty</b></a>
</p>

---

## The problem

Real-time risk (payment fraud, account takeover, intrusions) is hard because a single event is only suspicious in **context** — and a score nobody can explain is a score nobody can act on.

```
                         a new event hits the wire
                                   │
                  ┌────────────────┼────────────────┐
                  ▼                ▼                ▼
        ┌───────────────┐ ┌───────────────┐ ┌───────────────┐
        │ is this NORMAL│ │ is it part of │ │ can you EXPLAIN│
        │ for THIS      │ │ a bad PATTERN?│ │ the call to an│
        │ entity?       │ │ ring · lateral│ │ analyst — now?│
        └───────┬───────┘ └───────┬───────┘ └───────┬───────┘
           behavioral         relational         calibrated +
            anomaly           structure          explainable
```

Most systems answer one of these well and bolt the rest on. CONCLAVE answers **all three, together, per event.**

---

## 💡 What CONCLAVE does

One event goes in. **Four agents** look at it from different angles, a **judge** weighs the evidence, and a verdict comes out — score, label, and a human-readable explanation.

```
                          ┌──────────────────────────────┐
   enriched event  ─────► │           FEATURE            │  distill the event
                          └───────────────┬──────────────┘
                              ┌────────────┴───────────┐
                              ▼                         ▼
                   ┌────────────────────┐    ┌────────────────────┐
                   │ BEHAVIORAL         │    │ GRAPH REASONER      │
                   │ BASELINER          │    │ Neo4j templates:    │
                   │ cosine vs the      │    │ rings · lateral     │
                   │ entity's rolling   │    │ movement ·          │
                   │ embedding profile  │    │ privileged access   │
                   └─────────┬──────────┘    └──────────┬─────────┘
                             └───────────┬──────────────┘
                                         ▼
                              ┌──────────────────────┐
                              │   DELIBERATING JUDGE  │  weighs every signal
                              │   (LLM)               │
                              └──────────┬───────────┘
                                         ▼
                       ⟶   ALLOW · REVIEW · BLOCK   +   score ∈ [0,1]
                            +  contributing factors  +  plain-English why
```

If a model is unavailable, the judge **falls back** to a deterministic verdict derived from the same signals — the pipeline never returns "no decision."

---

## 🚀 Quickstart

```bash
# Prereqs: Docker Desktop · JDK 25 · Maven 3.9+ · uv (Python) · Node 20+

git clone https://github.com/anthropics/conclave && cd conclave
cp .env.example .env                  # serving mode: drop in a cloud API key

./scripts/up.sh local fraud           # build + boot the whole stack
./scripts/seed.sh fraud               # fire a labeled, multi-day synthetic burst
open http://localhost:8080/api/v1/decisions

./scripts/dashboard.sh                # live decision explorer → http://localhost:5173
./scripts/down.sh                     # tear everything down (containers + volumes)
```

**Two knobs, both on the command line:**

| Dimension | Values | Set by |
|---|---|---|
| **LLM mode** | `local` (your Ollama) · `serving` (cloud key) | 1st arg to `up.sh` |
| **Domain** | `fraud` · `security` | 2nd arg to `up.sh` |

In `serving` mode, one setting picks **Anthropic (Claude)** or any **OpenAI-compatible** endpoint (OpenAI, Ollama Cloud, OpenRouter, Groq, …).

---

## 🧠 How it works

The judge never sees raw data — it sees **distilled, structured evidence** from three independent agents, then deliberates.

**Behavioral baseliner** — every entity carries a rolling *behavioral fingerprint*: an exponential-moving-average of its past event embeddings (MiniLM, 384-d, in Postgres + `pgvector`). A new event is embedded and compared by **cosine similarity** to that fingerprint:

```
  entity profile ●━━━━━━━━━━━►                 (EMA of past behavior)
                  ╲  θ
   new event  ●━━━━╲━━►   small θ → looks like history   → low  anomaly
   new event  ●━━►  ╲     large θ → out of character     → high anomaly
```

A count-aware warmup keeps brand-new entities from being judged against a single first event, and scoring is **read-only** — a suspicious event never poisons the profile it's measured against.

**Graph reasoner** — entities live in a graph (Neo4j). Parametrized templates surface structure a single event can't show: a device fanning out across many cards (card-testing ring), one identity hopping across hosts (lateral movement), access to sensitive resources, and more.

**Deliberating judge** — an LLM receives the feature digest, the behavioral signal (cosine + anomaly), and the graph signal, then emits a calibrated `score`, a `verdict`, ranked `contributing_factors`, and a short Markdown explanation an analyst can act on.

---

## 🏗 Architecture

```
  PRODUCERS            ORCHESTRATOR  (Kafka Streams)          AGENTS  (LangGraph)            AUDIT
  ─────────            ──────────────────────────────        ───────────────────           ─────────────
                                                              ┌─ behavioral baseliner ─┐
  events.{d}.raw ─► enrich + feature-extract ─► Deliberate ─► │  graph reasoner         │ ─► Decision ─► Postgres
        │                      │                              └─ deliberating judge ────┘       │
        │                      ▼                                    │        │       │          ▼
   (generators)        events.{d}.enriched                    pgvector    Neo4j    LLM     REST /api/v1/decisions
                                                              (baselines) (graph)          + live dashboard

  d = fraud | security        infra: Kafka · Schema Registry · Postgres(pgvector) · Neo4j
```

| Service | Role | Stack |
|---|---|---|
| **orchestrator** | ingest, enrichment, feature extraction, decision persistence, audit API | Java · Spring Boot · Kafka Streams |
| **baseline** | rolling behavioral embeddings + cosine scoring | Java · Spring · Postgres + pgvector · MiniLM |
| **graph** | relational pattern templates | Java · Spring · Neo4j |
| **agents** | the LangGraph deliberation + LLM judge | Python · LangGraph · gRPC |
| **dashboard** | live decision explorer | React · Vite |

---

## 🔀 One architecture, two domains

The *same* agents and pipeline serve very different problems — you only swap the configuration.

|  | 💳 Payment fraud | 🛡 Security / SOC |
|---|---|---|
| **Event** | card-not-present payment | auth / resource-access event |
| **Entity** | cardholder / card | principal / identity |
| **Graph** | cardholder · device · IP · merchant | principal · host · resource |
| **Patterns** | card-testing ring · bust-out · ATO | lateral movement · exfiltration · ATO |

---

## ✨ What's novel

- **Three signal classes, fused per event** — behavioral (embedding cosine), relational (graph templates), and a deliberating LLM judge — instead of a single opaque score.
- **Behavioral baselines as vectors** — per-entity rolling embeddings with `pgvector` cosine search; a transaction is anomalous when it stops *looking like the customer*, not just when an amount crosses a threshold.
- **Explainable by construction** — every decision ships a calibrated score, ranked contributing factors, and a plain-English rationale, persisted for audit.
- **Domain-agnostic** — fraud and security run on identical code; the abstraction is the product.
- **Graceful under failure** — any agent (or the LLM) can be down and the pipeline still returns a safe, deterministic decision.

---

## 🗂 Project layout

```
conclave/
├── orchestrator/   ingest · Kafka Streams enrichment · audit API   (Java)
├── baseline/       rolling embeddings + cosine scoring             (Java · pgvector)
├── graph/          relational pattern templates                    (Java · Neo4j)
├── agents/         LangGraph deliberation + LLM judge              (Python)
├── generators/     synthetic multi-day, multi-distribution data    (Java)
├── dashboard/      live decision explorer                          (React)
├── website/        marketing site                                  (React)
├── docker/         one Dockerfile per service
├── scripts/        up · down · seed · dashboard · logs · test
└── docker-compose.yml
```

---

## ⚙️ Configuration

Everything is environment-driven (see [`.env.example`](.env.example)). The essentials:

| Variable | Purpose | Default |
|---|---|---|
| `JUDGE_LLM_PROVIDER` | `anthropic` · `openai` · `ollama` | `anthropic` |
| `JUDGE_LLM_MODEL` | model id (per-provider default if blank) | — |
| `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` | serving-mode credentials | — |
| `OLLAMA_BASE_URL` | local-mode endpoint | `http://localhost:11434` |
| `conclave.baseline.ema-decay` | behavioral memory (≈ `1/(1−decay)` events) | `0.85` |

---

## 🧪 Develop & test

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn verify   # Java services (JDK 25)
cd agents && uv run pytest                              # Python deliberation
./scripts/test.sh                                       # full suite
```

---

<p align="center">
  <sub>Built to show that risk detection can be accurate, relational, <b>and</b> explainable — at once.</sub>
</p>
