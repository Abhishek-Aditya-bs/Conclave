#!/usr/bin/env bash
# Publish a labeled synthetic event burst into the running stack.
#
#   ./scripts/seed.sh [fraud|security] [extra generator flags…]
#
# Requires the stack to be up (./scripts/up.sh). Points the generator at the
# real Schema Registry on :8085 so the orchestrator can decode the events.
#
# Examples:
#   ./scripts/seed.sh fraud
#   ./scripts/seed.sh security --clean 500 --rings 2 --ato 1 --extra 1
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

DOMAIN="${1:-fraud}"
shift || true
case "$DOMAIN" in fraud|security) ;; *) die "domain must be 'fraud' or 'security' (got '$DOMAIN')";; esac

# Point the generator at the live broker + registry exposed by docker-compose.
export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
export SCHEMA_REGISTRY_URL="${SCHEMA_REGISTRY_URL:-http://localhost:8085}"

if [ "$DOMAIN" = "fraud" ]; then
  MAIN="io.conclave.generators.fraud.FraudGeneratorMain"
  # Multi-day population (40 customers × 14 days × 3/day) builds real per-customer
  # baselines BEFORE the adversarial campaigns deviate from them — so the M3
  # cosine-similarity score has converged history to compare against.
  DEFAULT_ARGS="--customers 40 --days 14 --events-per-day 3 --distribution mix --clean 200 --rings 2 --ato 1 --extra 1"
else
  MAIN="io.conclave.generators.security.SecurityGeneratorMain"
  # Multi-day principal population (30 principals × 14 days × 3/day) builds real
  # per-principal baselines before the adversarial campaigns deviate from them.
  DEFAULT_ARGS="--customers 30 --days 14 --events-per-day 3 --clean 200 --rings 1 --ato 1 --extra 1"
fi
ARGS="${*:-$DEFAULT_ARGS}"

info "Seeding $DOMAIN events  (bootstrap=$KAFKA_BOOTSTRAP_SERVERS, registry=$SCHEMA_REGISTRY_URL)"
info "Generator args: $ARGS"
mvn -q -pl generators -am compile
mvn -q -pl generators exec:java -Dexec.mainClass="$MAIN" -Dexec.args="$ARGS"

ok "Seed complete."
echo "  Decisions: curl -s 'http://localhost:8080/api/v1/decisions?limit=10' | python3 -m json.tool"
