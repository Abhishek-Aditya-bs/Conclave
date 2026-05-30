"""gRPC clients for the baseline and graph services.

Each client wraps the generated stub with a thin, Pythonic surface that
returns plain Python dataclasses instead of proto messages. This keeps
the LangGraph node code unaware of protobuf entirely.
"""

from .baseline_client import BaselineClient
from .graph_client import GraphClient

__all__ = ["BaselineClient", "GraphClient"]
