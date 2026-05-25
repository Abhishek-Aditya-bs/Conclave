"""LangGraph state-graph assembly.

Wiring:

::

    START → feature → baseliner ─┐
                    └ graph_reasoner ─→ judge → END

The two middle nodes fan out from ``feature`` in parallel. LangGraph
runs both, merges their partial state updates (each owns a distinct
key — ``baseline_finding`` vs ``graph_finding`` — so there's no
reducer conflict), and joins at ``judge``. The ``errors`` key uses the
list-concatenation reducer declared in ``state.py``.

Two factories:
  * ``build_graph(...)`` — production wiring with real clients + provider.
  * ``build_graph_with_mocks(...)`` — explicit node injection for tests.
"""
from __future__ import annotations

from typing import Any

from langgraph.graph import END, START, StateGraph

from deliberation.clients import BaselineClient, GraphClient
from deliberation.llm import LLMProvider
from deliberation.nodes import feature_node
from deliberation.nodes.baseliner import make_baseliner_node
from deliberation.nodes.graph_reasoner import make_graph_reasoner_node
from deliberation.nodes.judge import make_judge_node
from deliberation.state import DeliberationState


def build_graph(
    *,
    baseline_client: BaselineClient | None,
    graph_client: GraphClient | None,
    llm_provider: LLMProvider,
):
    """Build the deliberation graph with real clients."""
    return _build(
        baseliner=make_baseliner_node(baseline_client),
        graph_reasoner=make_graph_reasoner_node(graph_client),
        judge=make_judge_node(llm_provider),
    )


def build_graph_with_nodes(
    *,
    feature: Any = feature_node,
    baseliner: Any,
    graph_reasoner: Any,
    judge: Any,
):
    """Test-friendly variant: inject every node directly.

    Used by ``tests/test_graph_e2e.py`` so we don't pay the cost of
    wrapping mock clients in the production factories.
    """
    return _build(
        feature=feature,
        baseliner=baseliner,
        graph_reasoner=graph_reasoner,
        judge=judge,
    )


def _build(
    *,
    feature: Any = feature_node,
    baseliner: Any,
    graph_reasoner: Any,
    judge: Any,
):
    builder = StateGraph(DeliberationState)
    builder.add_node("feature", feature)
    builder.add_node("baseliner", baseliner)
    builder.add_node("graph_reasoner", graph_reasoner)
    builder.add_node("judge", judge)

    builder.add_edge(START, "feature")
    # Parallel fanout: both nodes depend on `feature`, so LangGraph
    # schedules them on the same super-step.
    builder.add_edge("feature", "baseliner")
    builder.add_edge("feature", "graph_reasoner")
    # Join: judge only fires once both upstream nodes have produced an
    # update. Listing both sources here is the LangGraph idiom for an
    # AND-join.
    builder.add_edge("baseliner", "judge")
    builder.add_edge("graph_reasoner", "judge")
    builder.add_edge("judge", END)
    return builder.compile()
