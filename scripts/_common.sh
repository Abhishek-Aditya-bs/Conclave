#!/usr/bin/env bash
# Shared setup for the CONCLAVE scripts. Sourced by the others, not run directly.
#
# - Fails fast (set -euo pipefail).
# - Pins JAVA_HOME to the keg-only openjdk@25 (the default `java` here is 24).
#   Override by exporting JAVA_HOME before running any script.
# - cd's to the repo root so every command runs from a known location.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@25}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

COMPOSE="docker compose"

info() { printf '\033[1;36m▶ %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m! %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
