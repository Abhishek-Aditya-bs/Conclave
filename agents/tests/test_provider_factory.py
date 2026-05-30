"""LLMProvider factory tests — env-driven dispatch."""
from __future__ import annotations

from unittest.mock import patch

import pytest

from deliberation.llm import (
    DEFAULT_ANTHROPIC_MODEL,
    DEFAULT_OLLAMA_MODEL,
    DEFAULT_OPENAI_MODEL,
    build_provider_from_env,
)


class TestBuildProviderFromEnv:
    def test_default_is_anthropic(self) -> None:
        # No JUDGE_LLM_PROVIDER set → anthropic. Must have key.
        provider = build_provider_from_env({"ANTHROPIC_API_KEY": "sk-test"})
        assert provider.name == "anthropic"
        assert provider.model == DEFAULT_ANTHROPIC_MODEL

    def test_anthropic_uses_lock_model_unless_overridden(self) -> None:
        provider = build_provider_from_env(
            {"JUDGE_LLM_PROVIDER": "anthropic", "ANTHROPIC_API_KEY": "sk-test"}
        )
        # Haiku 4.5 is the lock.
        assert provider.model == "claude-haiku-4-5-20251001"

    def test_anthropic_model_can_be_overridden(self) -> None:
        provider = build_provider_from_env(
            {
                "JUDGE_LLM_PROVIDER": "anthropic",
                "JUDGE_LLM_MODEL": "claude-sonnet-test",
                "ANTHROPIC_API_KEY": "sk-test",
            }
        )
        assert provider.model == "claude-sonnet-test"

    def test_anthropic_without_key_raises(self) -> None:
        with pytest.raises(ValueError, match="ANTHROPIC_API_KEY"):
            build_provider_from_env({"JUDGE_LLM_PROVIDER": "anthropic"})

    def test_ollama_path_uses_gemma_default(self) -> None:
        # Patch ChatOllama so we don't try to hit a real server during construction.
        with patch("deliberation.llm.ollama_provider.ChatOllama") as mocked:
            provider = build_provider_from_env({"JUDGE_LLM_PROVIDER": "ollama"})
            assert provider.name == "ollama"
            assert provider.model == DEFAULT_OLLAMA_MODEL
            mocked.assert_called_once()
            kwargs = mocked.call_args.kwargs
            assert kwargs["model"] == DEFAULT_OLLAMA_MODEL
            assert kwargs["base_url"] == "http://localhost:11434"
            assert kwargs["format"] == "json"

    def test_ollama_model_can_be_overridden(self) -> None:
        with patch("deliberation.llm.ollama_provider.ChatOllama"):
            provider = build_provider_from_env(
                {"JUDGE_LLM_PROVIDER": "ollama", "JUDGE_LLM_MODEL": "gemma3:12b"}
            )
            assert provider.model == "gemma3:12b"

    def test_ollama_base_url_can_be_overridden(self) -> None:
        with patch("deliberation.llm.ollama_provider.ChatOllama") as mocked:
            build_provider_from_env(
                {"JUDGE_LLM_PROVIDER": "ollama", "OLLAMA_BASE_URL": "http://remote:1234"}
            )
            kwargs = mocked.call_args.kwargs
            assert kwargs["base_url"] == "http://remote:1234"

    def test_openai_path_uses_default_model_and_base_url(self) -> None:
        with patch("deliberation.llm.openai_provider.ChatOpenAI") as mocked:
            provider = build_provider_from_env(
                {"JUDGE_LLM_PROVIDER": "openai", "OPENAI_API_KEY": "sk-openai-test"}
            )
            assert provider.name == "openai"
            assert provider.model == DEFAULT_OPENAI_MODEL
            mocked.assert_called_once()
            kwargs = mocked.call_args.kwargs
            assert kwargs["model"] == DEFAULT_OPENAI_MODEL
            assert kwargs["base_url"] == "https://api.openai.com/v1"

    def test_openai_without_key_raises(self) -> None:
        with pytest.raises(ValueError, match="OPENAI_API_KEY"):
            build_provider_from_env({"JUDGE_LLM_PROVIDER": "openai"})

    def test_openai_base_url_and_model_overridable(self) -> None:
        # e.g. Ollama Cloud / OpenRouter via the same OpenAI-compatible path.
        with patch("deliberation.llm.openai_provider.ChatOpenAI") as mocked:
            provider = build_provider_from_env(
                {
                    "JUDGE_LLM_PROVIDER": "openai",
                    "OPENAI_API_KEY": "sk-cloud",
                    "OPENAI_BASE_URL": "https://ollama.com/v1",
                    "JUDGE_LLM_MODEL": "gpt-oss:20b",
                }
            )
            assert provider.model == "gpt-oss:20b"
            kwargs = mocked.call_args.kwargs
            assert kwargs["base_url"] == "https://ollama.com/v1"

    def test_unknown_provider_rejected(self) -> None:
        with pytest.raises(ValueError, match="invalid"):
            build_provider_from_env({"JUDGE_LLM_PROVIDER": "cohere"})

    def test_provider_name_is_case_insensitive(self) -> None:
        provider = build_provider_from_env(
            {"JUDGE_LLM_PROVIDER": "ANTHROPIC", "ANTHROPIC_API_KEY": "sk-test"}
        )
        assert provider.name == "anthropic"
