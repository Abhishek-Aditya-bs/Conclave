"""OpenAI-compatible judge — the ``serving`` path for any OpenAI-style API.

One provider class covers OpenAI proper *plus* every OpenAI-compatible
Chat Completions endpoint:

  * OpenAI       — ``OPENAI_BASE_URL=https://api.openai.com/v1``
  * Ollama Cloud — ``OPENAI_BASE_URL=https://ollama.com/v1``
  * OpenRouter   — ``OPENAI_BASE_URL=https://openrouter.ai/api/v1``
  * Groq / Together / vLLM / LM Studio / ... — point the base URL at them.

The endpoint is chosen with ``OPENAI_BASE_URL``, the credential with
``OPENAI_API_KEY``, and the model id with ``JUDGE_LLM_MODEL`` (whatever
that endpoint exposes — ``gpt-4o-mini``, a hosted ``gemma3``, etc.).

Structured output uses JSON mode (``response_format={"type":
"json_object"}``) plus the shared Decision schema embedded in the prompt,
then validated by ``parse_decision_payload``. This is the same
lowest-common-denominator approach the Ollama provider uses, so it works
across compatible backends that don't all implement strict
``json_schema`` / tool-calling.
"""
from __future__ import annotations

import json
import logging

from langchain_core.messages import HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI

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


# The literal word "JSON" must appear in the prompt for OpenAI's json_object
# mode to engage; the schema dump below satisfies that for every backend.
_SCHEMA_REMINDER = (
    "Return ONLY a JSON object that matches this schema:\n"
    + json.dumps(DECISION_JSON_SCHEMA, indent=2)
    + "\nDo not wrap it in markdown fences. Do not include any prose outside the JSON."
)


class OpenAIProvider(LLMProvider):
    """Single-shot judge backed by an OpenAI-compatible Chat Completions API."""

    name = "openai"

    def __init__(
        self,
        model: str,
        *,
        api_key: str,
        base_url: str = "https://api.openai.com/v1",
        temperature: float = 0.1,
        client: ChatOpenAI | None = None,
    ) -> None:
        self.model = model
        self._base_url = base_url
        self._client = client or ChatOpenAI(
            model=model,
            api_key=api_key,
            base_url=base_url,
            temperature=temperature,
            model_kwargs={"response_format": {"type": "json_object"}},
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
            raise LLMProviderError(f"OpenAI-compatible call failed: {exc}") from exc

        content = _coerce_text_content(response)
        try:
            payload = json.loads(content)
        except json.JSONDecodeError as exc:
            raise LLMProviderError(
                f"OpenAI-compatible response was not valid JSON: {content[:200]}"
            ) from exc
        if not isinstance(payload, dict):
            raise LLMProviderError(f"OpenAI-compatible JSON was not an object: {payload!r}")
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
    raise LLMProviderError(f"Unexpected message content type: {type(content).__name__}")
