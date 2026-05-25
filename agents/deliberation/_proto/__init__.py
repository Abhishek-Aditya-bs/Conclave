"""Generated gRPC stubs for M5.

The grpc_tools.protoc compiler emits flat-namespace imports
(e.g. ``import baseline_pb2`` inside ``baseline_pb2_grpc.py``), but our
package puts those modules under ``deliberation._proto``. Adding this
directory to ``sys.path`` keeps the generated imports working without
needing to post-process the protoc output on every regeneration.

Regenerate with ``scripts/gen_protos.sh``. Do not hand-edit anything in
this directory other than this file.
"""
from __future__ import annotations

import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)
