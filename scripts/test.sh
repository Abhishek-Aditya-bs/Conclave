#!/usr/bin/env bash
# Verify the whole project builds and passes its tests. Does NOT start the app.
#
# Java   : mvn verify (unit + Testcontainers integration tests + coverage gate).
#          Needs the Docker daemon running for Testcontainers.
# Python : uv sync + proto generation + ruff + pytest (coverage gate).
#
#   ./scripts/test.sh          # both
#   ./scripts/test.sh java     # Java only
#   ./scripts/test.sh python   # Python (M5) only
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

WHICH="${1:-all}"

run_java() {
  command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 \
    || die "Docker daemon not running — Java integration tests need it"
  info "Java: mvn verify  (JAVA_HOME=$JAVA_HOME)…"
  mvn verify
  ok "Java green."
}

run_python() {
  info "Python (M5): uv sync + protos + ruff + pytest…"
  ( cd agents \
      && uv sync --extra dev \
      && uv run ./scripts/gen_protos.sh \
      && uv run ruff check . \
      && uv run pytest -q )
  ok "Python green."
}

case "$WHICH" in
  all)    run_java; run_python ;;
  java)   run_java ;;
  python) run_python ;;
  *)      die "usage: ./scripts/test.sh [all|java|python]" ;;
esac

ok "Done."
