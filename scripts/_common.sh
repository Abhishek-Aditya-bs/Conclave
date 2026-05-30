#!/usr/bin/env bash
# Shared setup for the CONCLAVE scripts. Sourced by the others, not run directly.
#
# - Fails fast (set -euo pipefail).
# - Resolves a JDK 25 into JAVA_HOME (the Maven build enforces [25,26)). Works on
#   macOS (Homebrew on Apple Silicon *or* Intel, /usr/libexec/java_home, SDKMAN)
#   and Linux (/usr/lib/jvm, SDKMAN). A JDK 25 you already point JAVA_HOME at is
#   always honored — export JAVA_HOME yourself to override the search.
# - cd's to the repo root so every command runs from a known location.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

COMPOSE="docker compose"

# When up.sh starts an Ollama server itself, it records that server's PID here so
# down.sh can stop *only that one* — never a host Ollama you were already running.
OLLAMA_PID_FILE="$REPO_ROOT/.conclave-ollama.pid"

info() { printf '\033[1;36m▶ %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m! %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

# --- JAVA_HOME: locate a JDK 25 across macOS + Linux --------------------------

# True if $1 is a JDK home whose `java` reports major version 25.
_is_jdk25() {
  local home="${1:-}"
  [ -n "$home" ] && [ -x "$home/bin/java" ] || return 1
  "$home/bin/java" -version 2>&1 | grep -q 'version "25'
}

# Echo the first JDK 25 home we can find (or nothing). Order: an already-valid
# JAVA_HOME, Homebrew (Apple Silicon + Intel), macOS java_home, SDKMAN, common
# Linux locations, then whatever `java` on PATH happens to resolve to.
_find_jdk25() {
  local c
  if _is_jdk25 "${JAVA_HOME:-}"; then printf '%s' "$JAVA_HOME"; return 0; fi

  if command -v brew >/dev/null 2>&1; then
    c="$(brew --prefix openjdk@25 2>/dev/null || true)"
    _is_jdk25 "$c" && { printf '%s' "$c"; return 0; }
  fi
  for c in /opt/homebrew/opt/openjdk@25 /usr/local/opt/openjdk@25; do
    _is_jdk25 "$c" && { printf '%s' "$c"; return 0; }
  done

  if [ -x /usr/libexec/java_home ]; then
    c="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
    _is_jdk25 "$c" && { printf '%s' "$c"; return 0; }
  fi

  for c in "${HOME}"/.sdkman/candidates/java/25*; do
    _is_jdk25 "$c" && { printf '%s' "$c"; return 0; }
  done

  for c in /usr/lib/jvm/*25* /usr/lib/jvm/java-25-* /usr/java/*25*; do
    _is_jdk25 "$c" && { printf '%s' "$c"; return 0; }
  done

  if command -v java >/dev/null 2>&1; then
    c="$(command -v java)"; c="$(cd "$(dirname "$c")/.." 2>/dev/null && pwd || true)"
    _is_jdk25 "$c" && { printf '%s' "$c"; return 0; }
  fi
  return 1
}

_jdk25="$(_find_jdk25 || true)"
if [ -n "$_jdk25" ]; then
  export JAVA_HOME="$_jdk25"
  export PATH="$JAVA_HOME/bin:$PATH"
else
  warn "Couldn't find a JDK 25 (the Maven build enforces Java 25)."
  warn "  • macOS:  brew install openjdk@25      (or: sdk install java 25-tem)"
  warn "  • Linux:  install Temurin 25, or       sdk install java 25-tem"
  warn "  • Or:     export JAVA_HOME=/path/to/jdk-25   before running."
  # If JAVA_HOME points somewhere (even non-25), keep it on PATH so the build
  # runs and Maven's enforcer prints its precise version error.
  [ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
fi
unset _jdk25
