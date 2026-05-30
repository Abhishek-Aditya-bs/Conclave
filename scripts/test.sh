#!/usr/bin/env bash
# Verify the whole project builds and passes its tests. Does NOT start the app.
#
# Java   : mvn verify (unit + Testcontainers integration tests + coverage gate).
#          Needs the Docker daemon running for Testcontainers.
# Python : uv sync + proto generation + ruff + pytest (coverage gate).
#
#   ./scripts/test.sh          # both
#   ./scripts/test.sh java     # Java only
#   ./scripts/test.sh python   # Python only
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

# Capture the arg without defaulting yet, so we can tell whether the caller
# passed it. Arg present → use it directly with NO prompt (CI stays
# non-interactive). Missing + TTY → prompt; missing + non-TTY → default to all.
WHICH="${1:-}"

if [ -z "$WHICH" ]; then
  if [ -t 0 ]; then
    while [ -z "$WHICH" ]; do
      echo "Select test scope:"
      echo "  1) all     → Java + Python"
      echo "  2) java    → Java only"
      echo "  3) python  → Python only"
      read -rp "Choice [1]: " _which_choice
      _which_choice="${_which_choice:-1}"
      case "$_which_choice" in
        1) WHICH="all" ;;
        2) WHICH="java" ;;
        3) WHICH="python" ;;
        *) warn "Invalid choice, try again" ;;
      esac
    done
  else
    WHICH="all"
    info "No scope arg and stdin is not a TTY → defaulting to all"
  fi
fi

run_java() {
  command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 \
    || die "Docker daemon not running — Java integration tests need it"
  info "Java: mvn verify  (JAVA_HOME=$JAVA_HOME)…"
  mvn verify
  ok "Java green."
}

run_python() {
  info "Python: uv sync + protos + ruff + pytest…"
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
