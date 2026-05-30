"""Server entrypoint — ``python -m deliberation.server.entrypoint``.

Reads configuration from environment variables and starts the gRPC
server. Designed to be the PID-1 in a container.

Env vars:
  * ``DELIBERATION_PORT``        — default 9093
  * ``DELIBERATION_HOST``        — bind address, default 0.0.0.0 (all
                                   interfaces, required so the orchestrator can
                                   reach this server across the compose
                                   network). Set 127.0.0.1 for a local-only
                                   bind when running on a workstation.
  * ``BASELINE_SERVICE_TARGET``  — default localhost:9091 (matches the
                                   baseline service's default gRPC port)
  * ``GRAPH_SERVICE_TARGET``     — default localhost:9092 (matches the graph service)
  * ``JUDGE_LLM_PROVIDER``       — see deliberation.llm.provider
  * ``JUDGE_LLM_MODEL``          — see deliberation.llm.provider
  * ``ANTHROPIC_API_KEY``        — required when provider=anthropic
  * ``OLLAMA_BASE_URL``          — default http://localhost:11434
"""
from __future__ import annotations

import logging
import os
import signal
import sys
from concurrent import futures

import grpc

from deliberation._proto import deliberation_pb2_grpc
from deliberation.clients import BaselineClient, GraphClient
from deliberation.graph import build_graph
from deliberation.llm import build_provider_from_env
from deliberation.server.service import DeliberationService

_LOG = logging.getLogger(__name__)


def main() -> int:  # pragma: no cover — exercised manually + by e2e harness
    logging.basicConfig(
        level=os.environ.get("LOG_LEVEL", "INFO"),
        format="%(asctime)s %(levelname)-5s %(name)s :: %(message)s",
    )

    port = int(os.environ.get("DELIBERATION_PORT", "9093"))
    # Bind address is configurable rather than hardcoded. Default stays 0.0.0.0
    # because the orchestrator reaches this gRPC server across the compose
    # network — a 127.0.0.1 bind would refuse those cross-container connections.
    # Override DELIBERATION_HOST=127.0.0.1 to expose only on the loopback when
    # running the server directly on a host.
    host = os.environ.get("DELIBERATION_HOST", "0.0.0.0")
    baseline_target = os.environ.get("BASELINE_SERVICE_TARGET", "localhost:9091")
    graph_target = os.environ.get("GRAPH_SERVICE_TARGET", "localhost:9092")

    _LOG.info("Building LLM provider from environment ...")
    provider = build_provider_from_env()

    _LOG.info("Connecting clients: baseline=%s graph=%s", baseline_target, graph_target)
    baseline_client = BaselineClient(baseline_target)
    graph_client = GraphClient(graph_target)

    _LOG.info("Compiling deliberation graph ...")
    compiled = build_graph(
        baseline_client=baseline_client,
        graph_client=graph_client,
        llm_provider=provider,
    )
    servicer = DeliberationService(compiled)

    server = grpc.server(futures.ThreadPoolExecutor(max_workers=16))
    deliberation_pb2_grpc.add_DeliberationServiceServicer_to_server(servicer, server)
    listen_addr = f"{host}:{port}"
    server.add_insecure_port(listen_addr)

    server.start()
    _LOG.info("CONCLAVE deliberation orchestrator listening on %s", listen_addr)

    # Graceful shutdown on SIGTERM / SIGINT.
    def _shutdown(*_: object) -> None:
        _LOG.info("Shutdown signal received; draining ...")
        server.stop(grace=5).wait()
        baseline_client.close()
        graph_client.close()
        sys.exit(0)

    signal.signal(signal.SIGTERM, _shutdown)
    signal.signal(signal.SIGINT, _shutdown)
    server.wait_for_termination()
    return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
