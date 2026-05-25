"""Judge node — calls the LLMProvider and emits the final Decision.

If the provider raises ``LLMProviderError`` the node falls back to a
deterministic, low-confidence Decision derived from the structured
findings. The fallback path keeps the deliberation honest about
uncertainty rather than ever returning no-decision.
"""
from __future__ import annotations

import logging
import time
from typing import Any

from deliberation.llm import (
    JudgeInput,
    LLMProvider,
    LLMProviderError,
)
from deliberation.llm.provider import derive_fallback_decision, to_decision
from deliberation.state import DeliberationState

_LOG = logging.getLogger(__name__)


def make_judge_node(provider: LLMProvider):
    def judge_node(state: DeliberationState) -> dict[str, Any]:
        feature_summary = state.get("feature_summary")
        if feature_summary is None:
            raise RuntimeError(
                "judge_node invoked before feature_node — graph wiring is wrong"
            )

        judge_input = JudgeInput(
            feature_summary=feature_summary,
            baseline_finding=state.get("baseline_finding"),
            graph_finding=state.get("graph_finding"),
        )

        domain = state["domain"]
        start_ns = time.perf_counter_ns()
        try:
            output = provider.produce_decision(judge_input, domain=domain)
            errors_update: list[str] = []
        except LLMProviderError as exc:
            _LOG.warning("judge: provider %s failed, using fallback: %s", provider.name, exc)
            output = derive_fallback_decision(judge_input, domain=domain)
            errors_update = [f"judge: provider error -> fallback: {exc}"]
        latency_ms = max(1, (time.perf_counter_ns() - start_ns) // 1_000_000)

        decision = to_decision(
            output,
            latency_ms=int(latency_ms),
            judge_provider=provider.name,
            judge_model=provider.model,
        )
        result: dict[str, Any] = {"decision": decision}
        if errors_update:
            result["errors"] = errors_update
        return result

    return judge_node
