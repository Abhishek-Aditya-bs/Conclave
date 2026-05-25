# ADR-004 — Judge-LLM provider factory (M5)

* **Status.** Accepted (Session 5, 2026-05-25).
* **Module.** M5 — LangGraph Deliberation Orchestrator.
* **Spec hooks.** §5 "Local-only mode (Ollama)", §6 M5, rule #6 (judge model lock).

## Context

The judge agent is the only piece of CONCLAVE that calls an LLM. We
need two things from its design:

1. **A stable default that matches the spec.** Shipped demos, the Loom
   video, and published benchmarks must run on `claude-haiku-4-5-20251001`
   via the Anthropic Claude API. This is a hard spec lock (rule #6).
2. **A genuinely interchangeable local-only path.** Self-hosters and
   privacy-sensitive evaluators must be able to run CONCLAVE end-to-end
   with no Anthropic API key — switching only the judge backend, never
   the surrounding graph.

A `JUDGE_LLM_PROVIDER` env-var contract is already promised in the spec.
The question is *how* the rest of M5 should consume it.

## Decision

A small **strategy interface** with two implementations, instantiated
from an env-keyed factory:

```python
class LLMProvider(ABC):
    name: str   # "anthropic" | "ollama"
    model: str

    def produce_decision(
        self, judge_input: JudgeInput, *, domain: str,
    ) -> JudgeOutput: ...
```

* `AnthropicProvider` — uses the regular `anthropic` Python SDK against
  Claude Haiku 4.5. Structured output is produced by declaring a single
  `record_decision` tool whose `input_schema` mirrors the `Decision`
  proto, then setting `tool_choice = {"type": "tool", "name":
  "record_decision"}` so the model is forced to call it.
* `OllamaProvider` — uses `langchain-ollama`'s `ChatOllama` with
  `format="json"`. The shared `DECISION_JSON_SCHEMA` is embedded in the
  user prompt; the response is parsed and structurally validated by the
  same `parse_decision_payload` function the Anthropic path uses.

Both providers route through one parser. The judge node only knows the
provider's `name` and `model` (for stamping into the `Decision`) — it
never branches on which backend produced a verdict.

If a provider can't produce a parseable Decision the judge node catches
the `LLMProviderError` and falls back to `derive_fallback_decision`, a
deterministic heuristic over the structured findings. The fallback is
flagged in `verdict_explanation_md` and stamped with the same provider
identity so the audit UI can show "fallback used".

The factory reads:

```bash
JUDGE_LLM_PROVIDER   # default: anthropic
JUDGE_LLM_MODEL      # default: claude-haiku-4-5-20251001 (anthropic) | qwen3:8b (ollama)
ANTHROPIC_API_KEY    # required when provider=anthropic
OLLAMA_BASE_URL      # default: http://localhost:11434
```

Imports of the two provider modules are **lazy** inside the factory —
the Anthropic path never imports `langchain-ollama` and vice versa, so
a missing optional dep doesn't break the chosen backend.

## Alternatives considered

### Use the Claude Agent SDK (`claude-agent-sdk`) instead of bare `anthropic`

The spec talks about the Claude Agent SDK in the cost note (§5) because
that's the SDK whose calls draw from the Max-20x Agent SDK credit pool.
Rejected for the wrapper choice — *not* for the billing posture:

* The judge is a single LLM call producing a fixed-schema payload, not
  a multi-turn agent loop. The Agent SDK's `query(...)` is designed for
  tool-driven agentic conversations and is awkward when you just want
  one structured response.
* Both wrappers ultimately POST to `api.anthropic.com`, so billing is
  endpoint-driven, not wrapper-driven. The Agent SDK credit-pool
  semantics apply equally to bare `anthropic` SDK calls.
* The bare SDK gives us first-class control over prompt-caching
  (`cache_control`), which is concretely useful here because the system
  prompt is identical across every event and we expect cache hits to
  save ~90% of input-token spend.

If a future Agent SDK feature (e.g. native batched judging, structured
output without tool-use ceremony) makes the wrapper a clear win, revisit.

### One mega-provider with conditional branches inside

Considered — it'd be one file instead of three. Rejected because the
two backends have meaningfully different control flow: tool-use vs.
JSON-format, Anthropic API errors vs. arbitrary Ollama-server errors,
prompt-cache vs. no prompt-cache. The strategy interface keeps each
implementation honest about its own contract.

### Embed the prompt + schema inside each provider

Considered. Rejected because the user prompt is identical across
backends (same evidence package, same vocabulary), and centralizing
`build_user_prompt` lets us unit-test the prompt shape once. The
provider modules end up small (~80 lines each) and focused on the
backend-specific binding.

### Use langchain's `with_structured_output` for both backends

Tempting because it unifies the API. Rejected because:

* On the Anthropic side it pulls in `langchain-anthropic` for what
  amounts to a thin wrapper over the same tool-use pattern we'd write
  by hand — extra dependency without pulling its weight.
* On the Ollama side `with_structured_output` is model-dependent
  (newer models support `format="json_schema"`, older ones don't), so
  we'd still need the manual schema-in-prompt fallback for the
  qwen3:8b / llama3.3:8b path the spec recommends.

## Consequences

**Wins:**

* The factory is the single place where env-var parsing, provider
  selection, and lazy imports live. Adding a third backend (vLLM,
  Bedrock, etc.) is a ~50-line file + a branch in the factory.
* Tests can substitute a `MagicMock()` for the provider and assert on
  the judge node's behavior without touching either real backend.
* The Anthropic path has prompt-caching baked in (system prompt marked
  ephemeral); when the judge runs at scale on the orchestrator we
  should see substantial cost savings.

**Tradeoffs:**

* Two provider modules to maintain. The shared parser and prompt builder
  keep the duplication minimal but it's not zero.
* The Ollama path's quality is model-dependent and OUTSIDE the
  benchmark numbers the paper will publish. The README and spec already
  surface this; we re-state it in `OllamaProvider`'s docstring so it's
  unmissable.
* `LLMProviderError → fallback` means a flaky backend produces
  low-confidence verdicts rather than failures. That's the right
  product behavior (the deliberation always returns a Decision) but
  reviewers reading audit rows must see the "fallback used" marker —
  the audit UI in M7 needs to surface that.
