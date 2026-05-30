#!/usr/bin/env bash
# Build and start the full CONCLAVE stack.
#
#   ./scripts/up.sh [local|serving] [fraud|security]
#
#   mode (1st arg, default: serving)
#     local    → judge runs on YOUR Ollama (host), no API key. Model defaults to
#                gemma4:e4b (override with JUDGE_LLM_MODEL in .env).
#     serving  → judge runs on a cloud API key. Which provider (anthropic|openai)
#                and which model come from .env (JUDGE_LLM_PROVIDER, JUDGE_LLM_MODEL).
#
#   domain (2nd arg, default: fraud)
#     fraud | security — the reference configuration the orchestrator boots.
#
# Examples:
#   ./scripts/up.sh local fraud
#   ./scripts/up.sh serving security
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

MODE="${1:-serving}"
DOMAIN="${2:-fraud}"

case "$MODE"   in local|serving) ;; *) die "mode must be 'local' or 'serving' (got '$MODE')";; esac
case "$DOMAIN" in fraud|security) ;; *) die "domain must be 'fraud' or 'security' (got '$DOMAIN')";; esac

command -v docker >/dev/null 2>&1 || die "docker not found on PATH"
docker info >/dev/null 2>&1 || die "Docker daemon not running — start Docker Desktop first"
[ -f .env ] || warn ".env not found (cp .env.example .env). Serving mode needs an API key there."

export SPRING_PROFILES_ACTIVE="$DOMAIN"

if [ "$MODE" = "local" ]; then
  # Force the host-Ollama path regardless of what .env says.
  export JUDGE_LLM_PROVIDER="ollama"
  export OLLAMA_BASE_URL="${OLLAMA_BASE_URL:-http://host.docker.internal:11434}"
  info "LLM mode: local  → host Ollama (${OLLAMA_BASE_URL}), model=${JUDGE_LLM_MODEL:-gemma4:e4b}"
  if ! curl -fsS http://localhost:11434/api/tags >/dev/null 2>&1; then
    warn "Ollama isn't answering on localhost:11434. Start it before sending events"
    warn "(open the Ollama app, or run 'ollama serve'), else the judge uses its safe fallback."
  fi
else
  info "LLM mode: serving → provider='${JUDGE_LLM_PROVIDER:-anthropic (from .env)}' (see .env)"
fi

info "Building Java service jars  (JAVA_HOME=$JAVA_HOME)…"
mvn -q -pl orchestrator,baseline,graph -am -DskipTests package

info "Building Docker images…"
$COMPOSE build

info "Starting stack: domain=$DOMAIN  mode=$MODE…"
$COMPOSE up -d

ok "Stack is up."
echo "  Audit API   : http://localhost:8080/api/v1/decisions"
echo "  Neo4j UI    : http://localhost:7474   (neo4j / conclave-graph)"
echo
echo "  Send events : ./scripts/seed.sh $DOMAIN"
echo "  Dashboard   : ./scripts/dashboard.sh   (http://localhost:5173)"
echo "  Tail logs   : ./scripts/logs.sh"
echo "  Tear down   : ./scripts/down.sh"
