"""OllamaProvider tests with the ChatOllama dependency mocked."""
from __future__ import annotations

import json
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

from deliberation.llm import LLMProviderError
from deliberation.llm.ollama_provider import OllamaProvider
from deliberation.llm.provider import JudgeInput
from deliberation.state import VerdictLabel


def _ai_message(content: str | list[dict | str]) -> object:
    """Stand-in for langchain's AIMessage; only ``.content`` is read."""
    return SimpleNamespace(content=content)


class TestOllamaProvider:
    def test_parses_json_string_content(
        self, sample_feature_summary, sample_baseline_finding
    ) -> None:
        client = MagicMock()
        client.invoke.return_value = _ai_message(
            json.dumps({
                "score": 0.25,
                "verdict_label": "ALLOW",
                "verdict_explanation_md": "Looks normal.",
                "contributing_factors": [
                    {"name": "no_anomaly_observed", "weight": 0.0, "evidence": "Nothing unusual."}
                ],
            })
        )
        provider = OllamaProvider(model="qwen3:8b", client=client)

        out = provider.produce_decision(
            JudgeInput(
                feature_summary=sample_feature_summary,
                baseline_finding=sample_baseline_finding,
                graph_finding=None,
            ),
            domain="fraud",
        )
        assert out.verdict_label == VerdictLabel.ALLOW
        assert out.score == 0.25

    def test_parses_list_content(self, sample_feature_summary) -> None:
        client = MagicMock()
        client.invoke.return_value = _ai_message([
            {"text": '{"score":0.5,"verdict_label":"REVIEW",'},
            {"text": '"verdict_explanation_md":"Mixed signals.",'},
            {"text": '"contributing_factors":[{"name":"high_velocity","weight":0.3,"evidence":"v=17"}]}'},
        ])
        provider = OllamaProvider(model="qwen3:8b", client=client)

        out = provider.produce_decision(
            JudgeInput(feature_summary=sample_feature_summary, baseline_finding=None, graph_finding=None),
            domain="fraud",
        )
        assert out.verdict_label == VerdictLabel.REVIEW

    def test_invalid_json_raises(self, sample_feature_summary) -> None:
        client = MagicMock()
        client.invoke.return_value = _ai_message("not json at all")
        provider = OllamaProvider(model="qwen3:8b", client=client)

        with pytest.raises(LLMProviderError, match="not valid JSON"):
            provider.produce_decision(
                JudgeInput(feature_summary=sample_feature_summary, baseline_finding=None, graph_finding=None),
                domain="fraud",
            )

    def test_non_object_json_raises(self, sample_feature_summary) -> None:
        client = MagicMock()
        client.invoke.return_value = _ai_message('["this", "is", "a", "list"]')
        provider = OllamaProvider(model="qwen3:8b", client=client)
        with pytest.raises(LLMProviderError, match="not an object"):
            provider.produce_decision(
                JudgeInput(feature_summary=sample_feature_summary, baseline_finding=None, graph_finding=None),
                domain="fraud",
            )

    def test_client_exception_wrapped(self, sample_feature_summary) -> None:
        client = MagicMock()
        client.invoke.side_effect = RuntimeError("connection refused")
        provider = OllamaProvider(model="qwen3:8b", client=client)
        with pytest.raises(LLMProviderError, match="Ollama call failed"):
            provider.produce_decision(
                JudgeInput(feature_summary=sample_feature_summary, baseline_finding=None, graph_finding=None),
                domain="fraud",
            )
