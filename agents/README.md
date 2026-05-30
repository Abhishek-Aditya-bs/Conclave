# CONCLAVE — Deliberation Orchestrator

Python sidecar implementing the **four-agent LangGraph deliberation**. Java handles
the data plane (Kafka, Postgres, Neo4j); this service handles the agent reasoning
and emits the final `Decision`.

## Layout

```
agents/
├── pyproject.toml                # uv-managed (Python 3.12+)
├── proto/                        # source-of-truth proto contracts
│   ├── baseline.proto            # mirrored from baseline/src/main/proto
│   ├── graph.proto               # mirrored from graph/src/main/proto
│   └── deliberation.proto        # the deliberation service's own gRPC surface
├── deliberation/
│   ├── _proto/                   # generated; do not edit
│   ├── state.py                  # LangGraph DeliberationState + Decision dataclass
│   ├── nodes/                    # feature, baseliner, graph_reasoner, judge
│   ├── llm/                      # provider factory: anthropic | ollama
│   ├── clients/                  # gRPC clients for baseline + graph
│   ├── graph.py                  # LangGraph state-graph construction
│   └── server/                   # gRPC server wiring
├── scripts/gen_protos.sh         # regenerates _proto/
└── tests/                        # pytest suite
```

## Quickstart

```bash
cd agents
uv sync --extra dev           # install runtime + dev deps
uv run ./scripts/gen_protos.sh  # regenerate Python stubs from proto/
uv run pytest                 # 80% line coverage gate enforced
```

To launch the gRPC server:

```bash
uv run python -m deliberation.server.entrypoint  # listens on :9093
```

## Judge LLM contract

The judge node is provider-agnostic, selected by `JUDGE_LLM_PROVIDER`:

```bash
# Serving — Anthropic (Claude Haiku 4.5)
export JUDGE_LLM_PROVIDER=anthropic            # default
export JUDGE_LLM_MODEL=claude-haiku-4-5-20251001
export ANTHROPIC_API_KEY=...

# Serving — OpenAI-compatible (OpenAI, Ollama Cloud, OpenRouter, Groq, …)
export JUDGE_LLM_PROVIDER=openai
export JUDGE_LLM_MODEL=gpt-4o-mini             # or any model the endpoint serves
export OPENAI_API_KEY=...
export OPENAI_BASE_URL=https://api.openai.com/v1   # swap for ollama.com/v1 etc.

# Local — your host Ollama (any model you've pulled)
export JUDGE_LLM_PROVIDER=ollama
export JUDGE_LLM_MODEL=gemma4:e4b
export OLLAMA_BASE_URL=http://localhost:11434
```

Published benchmarks use the Anthropic path only. The Ollama
and OpenAI-compatible paths are opt-in and excluded from the p99 < 600ms SLA.

## Why this is Python, not Java

LangGraph is a Python-native stateful graph framework with parallel branches,
reducers, and replay. Spring AI's `ChatClient` is single-call oriented; until
Spring AI ships a real stateful-graph primitive, the agent layer stays in
Python.
