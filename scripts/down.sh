#!/usr/bin/env bash
# Tear EVERYTHING down — the one-stop reset.
#
# Stops and removes all CONCLAVE containers, the compose network, AND the
# named volumes (Postgres + Neo4j data). After this the next ./scripts/up.sh
# starts from a completely clean slate.
#
#   ./scripts/down.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

info "Stopping + removing all CONCLAVE containers, networks, and volumes…"
$COMPOSE down -v --remove-orphans

ok "All down. Data volumes removed — next ./scripts/up.sh is a fresh start."
