#!/usr/bin/env bash
#
# Regenerate Python gRPC stubs from agents/proto/*.proto.
#
# Output lands in agents/deliberation/_proto/, where it is wired into the
# rest of the package by deliberation/_proto/__init__.py (which adds itself
# to sys.path so protoc's flat-namespace imports resolve).
#
# Run from anywhere — the script resolves its own paths.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGENTS_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

PROTO_DIR="${AGENTS_DIR}/proto"
OUT_DIR="${AGENTS_DIR}/deliberation/_proto"

mkdir -p "${OUT_DIR}"

# Wipe everything except the package __init__.py so we don't accumulate stale
# generated artifacts from removed protos.
find "${OUT_DIR}" -mindepth 1 -maxdepth 1 ! -name '__init__.py' -exec rm -rf {} +

echo "→ generating Python stubs from ${PROTO_DIR}"
python -m grpc_tools.protoc \
    -I "${PROTO_DIR}" \
    --python_out="${OUT_DIR}" \
    --pyi_out="${OUT_DIR}" \
    --grpc_python_out="${OUT_DIR}" \
    "${PROTO_DIR}/baseline.proto" \
    "${PROTO_DIR}/graph.proto" \
    "${PROTO_DIR}/deliberation.proto"

# protoc emits absolute imports like `import baseline_pb2`. Our package
# layout flattens them under deliberation/_proto/, but the *_grpc.py modules
# import the corresponding *_pb2 module as a top-level name. The __init__.py
# (committed) prepends this directory to sys.path so those resolve.
echo "✓ generated:"
ls -1 "${OUT_DIR}" | sed 's/^/  /'
