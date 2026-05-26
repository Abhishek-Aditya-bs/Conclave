# Runtime image for the M5 LangGraph deliberation orchestrator (Python).
#
# Built with uv-managed deps; deliberately small base. Generates protobuf
# stubs at build time so the container is self-contained (the source-of-truth
# .proto files ship in the image at /app/proto).
FROM python:3.12-slim

# uv is the project's single Python toolchain — install via the official
# script rather than apt (the apt package lags releases significantly).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && curl -LsSf https://astral.sh/uv/install.sh | sh \
    && mv /root/.local/bin/uv /usr/local/bin/uv

WORKDIR /app

# Copy lockfile + pyproject FIRST so the dep layer caches independently of
# the source code.
COPY agents/pyproject.toml agents/uv.lock* agents/.python-version /app/
RUN uv sync --no-dev --frozen --no-install-project 2>/dev/null \
    || uv sync --no-dev --no-install-project

# Now copy the source + protos and regenerate the stubs.
COPY agents/deliberation /app/deliberation
COPY agents/proto /app/proto
COPY agents/scripts /app/scripts
RUN uv run ./scripts/gen_protos.sh

# Default ports: gRPC server on 9093.
EXPOSE 9093

# All runtime config from env vars (see deliberation/server/entrypoint.py docs).
CMD ["uv", "run", "python", "-m", "deliberation.server.entrypoint"]
