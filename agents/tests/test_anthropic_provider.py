"""AnthropicProvider tests with the SDK fully mocked."""
from __future__ import annotations

from types import SimpleNamespace
from unittest.mock import MagicMock

import anthropic
import pytest

from deliberation.llm import LLMProviderError
from deliberation.llm.anthropic_provider import AnthropicProvider
from deliberation.llm.provider import JudgeInput
from deliberation.state import VerdictLabel


def _make_message(*, tool_input: dict | None, tool_name: str = "record_decision"):
    """Build a fake Anthropic Messages.create response.

    Mirrors the shape we read in ``_extract_tool_input``: a list of
    content blocks where the one we care about has ``type='tool_use'``
    and exposes ``name`` + ``input``.
    """
    blocks = []
    if tool_input is not None:
        blocks.append(SimpleNamespace(type="tool_use", name=tool_name, input=tool_input))
    return SimpleNamespace(
        id="msg_test",
        stop_reason="tool_use",
        content=blocks,
        usage=SimpleNamespace(input_tokens=200, output_tokens=80),
    )


class TestAnthropicProvider:
    def test_produces_decision_from_tool_call(self, sample_feature_summary, sample_baseline_finding, sample_graph_finding) -> None:
        client = MagicMock()
        client.messages.create.return_value = _make_message(
            tool_input={
                "score": 0.83,
                "verdict_label": "BLOCK",
                "verdict_explanation_md": "Block — device fans out to 7 cardholders.",
                "contributing_factors": [
                    {"name": "graph_ring_detected", "weight": 0.8, "evidence": "ring"},
                ],
            }
        )
        provider = AnthropicProvider(
            model="claude-haiku-4-5-20251001",
            api_key="sk-test",
            client=client,
        )

        out = provider.produce_decision(
            JudgeInput(
                feature_summary=sample_feature_summary,
                baseline_finding=sample_baseline_finding,
                graph_finding=sample_graph_finding,
            ),
            domain="fraud",
        )

        assert out.verdict_label == VerdictLabel.BLOCK
        assert out.score == 0.83
        assert out.contributing_factors[0].name == "graph_ring_detected"

        # Sanity-check that the request was shaped correctly.
        call = client.messages.create.call_args
        assert call.kwargs["model"] == "claude-haiku-4-5-20251001"
        assert call.kwargs["tool_choice"] == {"type": "tool", "name": "record_decision"}
        # Prompt caching is opted in via cache_control on the system block.
        system_block = call.kwargs["system"][0]
        assert system_block["cache_control"] == {"type": "ephemeral"}

    def test_api_error_wrapped_as_provider_error(self, sample_feature_summary) -> None:
        client = MagicMock()
        # APIError is the abstract base; we use a subclass to be safe.
        err = anthropic.APIConnectionError(request=MagicMock())
        client.messages.create.side_effect = err
        provider = AnthropicProvider(model="x", api_key="k", client=client)

        with pytest.raises(LLMProviderError, match="Anthropic API call failed"):
            provider.produce_decision(
                JudgeInput(feature_summary=sample_feature_summary, baseline_finding=None, graph_finding=None),
                domain="fraud",
            )

    def test_missing_tool_use_block_raises(self, sample_feature_summary) -> None:
        client = MagicMock()
        client.messages.create.return_value = _make_message(tool_input=None)
        provider = AnthropicProvider(model="x", api_key="k", client=client)

        with pytest.raises(LLMProviderError, match="No tool_use block"):
            provider.produce_decision(
                JudgeInput(feature_summary=sample_feature_summary, baseline_finding=None, graph_finding=None),
                domain="fraud",
            )

    def test_tool_input_not_dict_raises(self, sample_feature_summary) -> None:
        client = MagicMock()
        client.messages.create.return_value = _make_message(tool_input="not a dict")  # type: ignore[arg-type]
        provider = AnthropicProvider(model="x", api_key="k", client=client)
        with pytest.raises(LLMProviderError, match="not a dict"):
            provider.produce_decision(
                JudgeInput(feature_summary=sample_feature_summary, baseline_finding=None, graph_finding=None),
                domain="fraud",
            )
