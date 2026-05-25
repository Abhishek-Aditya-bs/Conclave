"""Anthropic-backed judge — Claude Haiku 4.5 with tool-use structured output.

We use the regular ``anthropic`` SDK (not the higher-level Claude Agent SDK
wrapper) because the judge is a single LLM call producing a fixed-schema
payload, not a multi-turn agent loop. Both wrappers go through the same
``api.anthropic.com`` endpoint and therefore draw equally from the
Max-20x Agent SDK credit pool (spec §5 cost note).

Structured output: we declare a single ``record_decision`` tool whose
``input_schema`` matches the Decision shape, then set
``tool_choice = {"type": "tool", "name": "record_decision"}`` so the model
is forced to call it. This is the Anthropic-blessed pattern for JSON
output as of the Messages API.
"""
from __future__ import annotations

import logging
from typing import Any

import anthropic

from deliberation.llm.provider import (
    DECISION_JSON_SCHEMA,
    JUDGE_SYSTEM_PROMPT,
    JudgeInput,
    JudgeOutput,
    LLMProvider,
    LLMProviderError,
    build_user_prompt,
    parse_decision_payload,
)

_LOG = logging.getLogger(__name__)

_TOOL_NAME = "record_decision"
_TOOL = {
    "name": _TOOL_NAME,
    "description": (
        "Record the final risk decision for the event under deliberation. "
        "Call this tool exactly once with a fully-populated Decision object."
    ),
    "input_schema": DECISION_JSON_SCHEMA,
}

# Anthropic's prompt-caching is gated on the system prompt being marked as
# ephemeral. Cache hits cost 1/10th of normal input tokens — meaningful when
# the judge runs at scale because the system prompt is identical per call.
_SYSTEM_BLOCK = [
    {
        "type": "text",
        "text": JUDGE_SYSTEM_PROMPT,
        "cache_control": {"type": "ephemeral"},
    }
]


class AnthropicProvider(LLMProvider):
    """Single-shot judge backed by Anthropic Messages API."""

    name = "anthropic"

    def __init__(
        self,
        model: str,
        *,
        api_key: str,
        max_tokens: int = 1024,
        client: anthropic.Anthropic | None = None,
    ) -> None:
        self.model = model
        self._max_tokens = max_tokens
        self._client = client or anthropic.Anthropic(api_key=api_key)

    def produce_decision(self, judge_input: JudgeInput, *, domain: str) -> JudgeOutput:
        user_prompt = build_user_prompt(judge_input, domain=domain)
        try:
            response = self._client.messages.create(
                model=self.model,
                max_tokens=self._max_tokens,
                system=_SYSTEM_BLOCK,
                tools=[_TOOL],
                tool_choice={"type": "tool", "name": _TOOL_NAME},
                messages=[{"role": "user", "content": user_prompt}],
            )
        except anthropic.APIError as exc:
            raise LLMProviderError(f"Anthropic API call failed: {exc}") from exc

        payload = _extract_tool_input(response, _TOOL_NAME)
        return parse_decision_payload(payload, raw_response=_summarize_response(response))


def _extract_tool_input(response: anthropic.types.Message, tool_name: str) -> dict[str, Any]:
    """Pull the input dict from the tool_use block we forced the model to emit."""
    for block in response.content:
        if getattr(block, "type", None) == "tool_use" and getattr(block, "name", None) == tool_name:
            tool_input = block.input  # type: ignore[attr-defined]
            if not isinstance(tool_input, dict):
                raise LLMProviderError(
                    f"tool_use input was not a dict: {type(tool_input).__name__}"
                )
            return tool_input
    raise LLMProviderError(
        f"No tool_use block with name '{tool_name}' in response: "
        f"{[getattr(b, 'type', '?') for b in response.content]}"
    )


def _summarize_response(response: anthropic.types.Message) -> str:
    """Compact replay-friendly snapshot — id + stop_reason + usage."""
    try:
        return (
            f"id={response.id} stop_reason={response.stop_reason} "
            f"in_tokens={response.usage.input_tokens} "
            f"out_tokens={response.usage.output_tokens}"
        )
    except AttributeError:
        return repr(response)[:200]
