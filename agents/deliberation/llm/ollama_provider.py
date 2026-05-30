"""Ollama-backed judge — opt-in local-only path.

Uses ``langchain-ollama``'s ``ChatOllama`` with ``format="json"``. We
embed the Decision JSON schema in the prompt because not every Ollama
model honors the OpenAI-style ``response_format=json_schema`` keyword
yet; ``format="json"`` is the lowest common denominator and forces the
model to emit syntactically valid JSON. Validation is then done by our
``parse_decision_payload``.

Caveats apply:
  * Output quality varies by model; benchmarks must NOT be quoted from
    this path.
  * p99 < 600ms latency target does NOT apply.
  * Tool-call reliability degrades sharply outside Qwen3 / Gemma 3+ /
    Llama 3.3+.
"""
from __future__ import annotations

import json
import logging

from langchain_core.messages import HumanMessage, SystemMessage
from langchain_ollama import ChatOllama

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


_SCHEMA_REMINDER = (
    "Return ONLY a JSON object that matches this schema:\n"
    + json.dumps(DECISION_JSON_SCHEMA, indent=2)
    + "\nDo not wrap it in markdown fences. Do not include any prose outside the JSON."
)


class OllamaProvider(LLMProvider):
    """Single-shot judge backed by a local Ollama server."""

    name = "ollama"

    def __init__(
        self,
        model: str,
        *,
        base_url: str = "http://localhost:11434",
        temperature: float = 0.1,
        client: ChatOllama | None = None,
    ) -> None:
        self.model = model
        self._base_url = base_url
        self._client = client or ChatOllama(
            model=model,
            base_url=base_url,
            temperature=temperature,
            format="json",
        )

    def produce_decision(self, judge_input: JudgeInput, *, domain: str) -> JudgeOutput:
        user_prompt = build_user_prompt(judge_input, domain=domain) + "\n\n" + _SCHEMA_REMINDER
        try:
            response = self._client.invoke(
                [
                    SystemMessage(content=JUDGE_SYSTEM_PROMPT),
                    HumanMessage(content=user_prompt),
                ]
            )
        except Exception as exc:  # noqa: BLE001 — surface every failure mode uniformly
            raise LLMProviderError(f"Ollama call failed: {exc}") from exc

        content = _coerce_text_content(response)
        try:
            payload = json.loads(content)
        except json.JSONDecodeError as exc:
            raise LLMProviderError(
                f"Ollama response was not valid JSON: {content[:200]}"
            ) from exc
        if not isinstance(payload, dict):
            raise LLMProviderError(f"Ollama JSON was not an object: {payload!r}")
        return parse_decision_payload(payload, raw_response=content)


def _coerce_text_content(message: object) -> str:
    """LangChain AIMessage.content can be str or list of parts; flatten to str."""
    content = getattr(message, "content", message)
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        chunks: list[str] = []
        for part in content:
            if isinstance(part, str):
                chunks.append(part)
            elif isinstance(part, dict) and "text" in part:
                chunks.append(str(part["text"]))
        return "".join(chunks)
    raise LLMProviderError(f"Unexpected Ollama message content type: {type(content).__name__}")
