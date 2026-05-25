"""Judge-LLM abstraction layer.

The judge node is the only piece of M5 that talks to an LLM. We hide
that behind ``LLMProvider`` so the rest of the graph (and the tests) are
provider-agnostic — see ADR-004 for rationale.
"""

from .provider import (
    DEFAULT_ANTHROPIC_MODEL,
    DEFAULT_OLLAMA_MODEL,
    JudgeInput,
    JudgeOutput,
    LLMProvider,
    LLMProviderError,
    build_provider_from_env,
)

__all__ = [
    "DEFAULT_ANTHROPIC_MODEL",
    "DEFAULT_OLLAMA_MODEL",
    "JudgeInput",
    "JudgeOutput",
    "LLMProvider",
    "LLMProviderError",
    "build_provider_from_env",
]
