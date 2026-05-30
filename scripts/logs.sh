#!/usr/bin/env bash
# Tail logs from the four CONCLAVE services in one stream.
# Pass service names to narrow it down, e.g. ./scripts/logs.sh agents orchestrator
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

SERVICES=("$@")
[ ${#SERVICES[@]} -eq 0 ] && SERVICES=(orchestrator baseline graph agents)

exec $COMPOSE logs -f "${SERVICES[@]}"
