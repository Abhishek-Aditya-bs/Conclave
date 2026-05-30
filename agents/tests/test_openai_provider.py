"""OpenAIProvider tests with the ChatOpenAI dependency mocked.

Covers the ``serving`` path that fronts OpenAI plus any OpenAI-compatible
endpoint (Ollama Cloud, OpenRouter, Groq, ...). We never hit a network;
the ChatOpenAI client is injected as a MagicMock.
"""
from __future__ import annotations

import json
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

from deliberation.llm import LLMProviderError
from deliberation.llm.openai_provider import OpenAIProvider
from deliberation.llm.provider import JudgeInput
from deliberation.state import VerdictLabel


def _ai_message(content: str | list[dict | str]) -> object:
    """Stand-in for langchain's AIMessage; only ``.content`` is read."""
    return SimpleNamespace(content=content)


class TestOpenAIProvider:
    def test_parses_json_string_content(
        self, sample_feature_summary, sample_graph_finding
    ) -> None:
        client = MagicMock()
        client.invoke.return_value = _ai_message(
            json.dumps({
                "score": 0.82,
                "verdict_label": "BLOCK",
                "verdict_explanation_md": "Ring detected.",
                "contributing_factors": [
                    {"name": "graph_ring_detected", "weight": 0.9, "evidence": "9 cardholders."}
                ],
            })
        )
        provider = OpenAIProvider(model="gpt-4o-mini", api_key="sk-test", client=client)

        out = provider.produce_decision(
            JudgeInput(
                feature_summary=sample_feature_summary,
                baseline_finding=None,
                graph_finding=sample_graph_finding,
            ),
            domain="fraud",
        )
        assert out.verdict_label == VerdictLabel.BLOCK
        assert out.score == 0.82

    def test_parses_list_content(self, sample_feature_summary) -> None:
        client = MagicMock()
        client.invoke.return_value = _ai_message([
            {"text": '{"score":0.5,"verdict_label":"REVIEW",'},
            {"text": '"verdict_explanation_md":"Mixed signals.",'},
            {"text": '"contributing_factors":[{"name":"high_velocity","weight":0.3,"evidence":"v=17"}]}'},
        ])
        provider = OpenAIProvider(model="gpt-4o-mini", api_key="sk-test", client=client)

        out = provider.produce_decision(
            JudgeInput(feature_summary=sample_feature_summary, baseline_finding=None, graph_finding=None),
            domain="fraud",
        )
        assert out.verdict_label == VerdictLabel.REVIEW

    def test_invalid_json_raises(self, sample_feature_summary) -> None:
        client = MagicMock()
        client.invoke.return_value = _ai_message("not json at all")
        provider = OpenAIProvider(model="gpt-4o-mini", api_key="sk-test", client=client)

        with pytest.raises(LLMProviderError, match="not valid JSON"):
            provider.produce_decision(
                JudgeInput(feature_summary=sample_feature_summary, baseline_finding=None, graph_finding=None),
                domain="fraud",
            )

    def test_non_object_json_raises(self, sample_feature_summary) -> None:
        client = MagicMock()
        client.invoke.return_value = _ai_message('["this", "is", "a", "list"]')
        provider = OpenAIProvider(model="gpt-4o-mini", api_key="sk-test", client=client)
        with pytest.raises(LLMProviderError, match="not an object"):
            provider.produce_decision(
                JudgeInput(feature_summary=sample_feature_summary, baseline_finding=None, graph_finding=None),
                domain="fraud",
            )

    def test_client_exception_wrapped(self, sample_feature_summary) -> None:
        client = MagicMock()
        client.invoke.side_effect = RuntimeError("connection refused")
        provider = OpenAIProvider(model="gpt-4o-mini", api_key="sk-test", client=client)
        with pytest.raises(LLMProviderError, match="OpenAI-compatible call failed"):
            provider.produce_decision(
                JudgeInput(feature_summary=sample_feature_summary, baseline_finding=None, graph_finding=None),
                domain="fraud",
            )
