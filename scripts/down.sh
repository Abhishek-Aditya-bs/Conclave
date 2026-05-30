#!/usr/bin/env bash
# Tear EVERYTHING down — the one-stop reset.
#
# Stops and removes all CONCLAVE containers, the compose network, AND the named
# volumes (Postgres + Neo4j data), stops the host-side dashboard dev server, and
# stops the Ollama server *only if up.sh started it* (so a host Ollama you were
# already running is left untouched). After this the next ./scripts/up.sh starts
# from a clean slate.
#
#   ./scripts/down.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

# The audit dashboard runs as a host-side Vite dev server (scripts/dashboard.sh),
# NOT a compose container — so `compose down` can't see it. Stop its listener on
# :5173 here too. Only the LISTEN socket is targeted (the dev server itself), so
# connected browser tabs are left alone.
stop_dashboard() {
  local port=5173 pids=""
  if command -v lsof >/dev/null 2>&1; then
    pids="$(lsof -ti "tcp:${port}" -sTCP:LISTEN 2>/dev/null || true)"
  elif command -v fuser >/dev/null 2>&1; then
    pids="$(fuser "${port}/tcp" 2>/dev/null | tr -s ' ' || true)"
  fi
  if [ -n "${pids// /}" ]; then
    info "Stopping dashboard dev server on :${port} (pid: ${pids})…"
    # shellcheck disable=SC2086  -- pids may be space-separated
    kill ${pids} 2>/dev/null || true
  else
    info "No dashboard dev server on :${port} (nothing to stop)."
  fi
}

# Stop the Ollama server ONLY if up.sh started it (PID recorded in
# $OLLAMA_PID_FILE). A host Ollama you launched yourself never gets a PID file,
# so it is deliberately left running.
stop_ollama() {
  if [ ! -f "$OLLAMA_PID_FILE" ]; then
    info "No Ollama was started by up.sh — leaving any host Ollama running."
    return 0
  fi
  local pid; pid="$(cat "$OLLAMA_PID_FILE" 2>/dev/null || true)"
  rm -f "$OLLAMA_PID_FILE"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    info "Stopping the Ollama server CONCLAVE started (pid ${pid})…"
    kill "$pid" 2>/dev/null || true
    local n=0
    while kill -0 "$pid" 2>/dev/null && [ "$n" -lt 10 ]; do sleep 0.5; n=$((n + 1)); done
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
    ok "Ollama stopped."
  else
    info "Ollama started by up.sh is already stopped."
  fi
}

info "Stopping + removing all CONCLAVE containers, networks, and volumes…"
$COMPOSE down -v --remove-orphans

stop_dashboard
stop_ollama

ok "All down. Data volumes removed — next ./scripts/up.sh is a fresh start."
