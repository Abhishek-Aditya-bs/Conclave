#!/usr/bin/env bash
# Build and start the full CONCLAVE stack — friendly when interactive, fully
# scriptable when not. (This is the single entry point: it absorbed the old
# start.sh wizard.)
#
#   ./scripts/up.sh [mode] [domain]
#
# Run it with NO arguments in a terminal and it walks you through a picker
# (judge model → domain). Pass arguments — or run without a TTY, e.g. in CI —
# and every prompt is skipped.
#
#   mode    (1st arg)  local | serving
#     local    → judge runs on YOUR host Ollama. No API key, but slow per call.
#     serving  → judge runs on a cloud API key; provider/model come from .env.
#   domain  (2nd arg)  fraud | security   (default: fraud)
#
# Interactive default: the judge is google/gemini-3.1-flash-lite via OpenRouter
# (fast + cheap). Ollama is offered too, but because it's slow it is NOT the
# default. Cloud judges read their key from the git-ignored .env — if it's
# missing, up.sh stops and tells you exactly what to add (it never edits .env).
#
# Examples:
#   ./scripts/up.sh                  # interactive picker (gum if installed)
#   ./scripts/up.sh serving fraud    # cloud judge from .env, fraud domain
#   ./scripts/up.sh local security   # host Ollama judge, security domain
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

have_gum()    { command -v gum >/dev/null 2>&1; }
interactive() { [ -t 0 ] && [ -t 1 ]; }

# Read a KEY=value out of .env (first match), if present.
envval()  { [ -f .env ] && sed -n "s/^$1=//p" .env | head -1 || true; }
# A credential is present if it's exported in the shell OR sits in .env.
has_cred() { local n="$1" v="${!1:-}"; [ -n "$v" ] || v="$(envval "$n")"; [ -n "$v" ]; }

# choose "<header>" "opt1" "opt2" …  → echoes the chosen option. Uses gum when
# available (first option pre-selected), otherwise a plain numbered menu. The
# menu UI goes to the terminal / stderr; only the choice lands on stdout.
choose() {
  local header="$1"; shift
  local -a opts=("$@")
  if have_gum; then
    gum choose --header "$header" --selected "${opts[0]}" "${opts[@]}"
    return
  fi
  local i
  printf '%s\n' "$header" >&2
  for i in "${!opts[@]}"; do printf '  %d) %s\n' "$((i + 1))" "${opts[$i]}" >&2; done
  local sel
  while :; do
    read -rp "Choice [1]: " sel </dev/tty || true
    sel="${sel:-1}"
    case "$sel" in
      *[!0-9]*|'') ;;
      *) if [ "$sel" -ge 1 ] && [ "$sel" -le "${#opts[@]}" ]; then
           printf '%s' "${opts[$((sel - 1))]}"; return 0
         fi ;;
    esac
    printf '  invalid choice, try again\n' >&2
  done
}

# Interactive judge picker. Sets MODE / PROVIDER / MODEL / BASE_URL.
pick_backend() {
  local choice
  choice="$(choose "Judge model — how should the deliberating judge run?" \
    "gemini-3.1-flash-lite  ·  low-cost + fast, via OpenRouter (recommended)" \
    "gemini-3.5-flash  ·  near-Pro quality, via OpenRouter" \
    "gpt-5.4-mini  ·  OpenAI mini, via OpenAI" \
    "claude-haiku  ·  Anthropic Haiku" \
    "ollama  ·  runs locally, no API key (slower per call)" \
    "from .env  ·  use the JUDGE_LLM_* already configured in .env")"
  case "${choice%% *}" in
    gemini-3.1-flash-lite) MODE=serving; PROVIDER=openai;    MODEL="google/gemini-3.1-flash-lite"; BASE_URL="https://openrouter.ai/api/v1" ;;
    gemini-3.5-flash)      MODE=serving; PROVIDER=openai;    MODEL="google/gemini-3.5-flash";      BASE_URL="https://openrouter.ai/api/v1" ;;
    gpt-5.4-mini)          MODE=serving; PROVIDER=openai;    MODEL="gpt-5.4-mini";                 BASE_URL="https://api.openai.com/v1" ;;
    claude-haiku)          MODE=serving; PROVIDER=anthropic; MODEL="claude-haiku-4-5-20251001";    BASE_URL="" ;;
    ollama)                MODE=local;   PROVIDER=ollama;    MODEL="";                             BASE_URL="" ;;
    from)                  MODE=serving; PROVIDER="";        MODEL="";                             BASE_URL="" ;;  # honor .env as-is
    *)                     die "unrecognized choice: $choice" ;;
  esac
}

pick_domain() {
  local d
  d="$(choose "Domain — which reference configuration?" \
    "fraud  ·  card-not-present payments" \
    "security  ·  identity / access (SOC)")"
  case "${d%% *}" in
    fraud)    DOMAIN=fraud ;;
    security) DOMAIN=security ;;
    *)        die "unrecognized domain: $d" ;;
  esac
}

# Offer the installed Ollama models (gemma4:e4b first, as the default). Echoes
# the chosen tag; falls back to gemma4:e4b if the list can't be read.
pick_ollama_model() {
  local -a models=() ordered=() m
  while IFS= read -r m; do [ -n "$m" ] && models+=("$m"); done \
    < <(ollama list 2>/dev/null | awk 'NR>1 {print $1}')
  [ "${#models[@]}" -eq 0 ] && { printf 'gemma4:e4b'; return; }
  for m in "${models[@]}"; do [ "$m" = "gemma4:e4b" ] && ordered+=("$m"); done
  for m in "${models[@]}"; do [ "$m" != "gemma4:e4b" ] && ordered+=("$m"); done
  choose "Ollama judge model" "${ordered[@]}"
}

# Ensure a reachable Ollama for local mode. STRICT: dies if the CLI is missing,
# if the server can't be brought up, or if no model is installed. If it starts
# the server itself, it records the PID so down.sh can stop exactly that one.
ensure_ollama() {
  command -v ollama >/dev/null 2>&1 || die "$(printf 'Ollama selected but the "ollama" CLI is not installed.\n  • macOS:  brew install ollama   (or install the Ollama app)\n  • Linux:  curl -fsSL https://ollama.com/install.sh | sh\nInstall it, then re-run ./scripts/up.sh.')"

  local probe="http://localhost:11434/api/tags"
  if curl -fsS "$probe" >/dev/null 2>&1; then
    info "Ollama already running on :11434 (left as-is)."
  else
    local log="${TMPDIR:-/tmp}/conclave-ollama.log"
    info "Ollama not responding — starting it (OLLAMA_HOST=0.0.0.0)…"
    OLLAMA_HOST=0.0.0.0 nohup ollama serve >"$log" 2>&1 &
    echo "$!" >"$OLLAMA_PID_FILE"
    local tries=0
    until curl -fsS "$probe" >/dev/null 2>&1; do
      tries=$((tries + 1))
      if [ "$tries" -ge 30 ]; then
        rm -f "$OLLAMA_PID_FILE"
        die "Ollama did not come up within 30s (see $log). Start it manually, then re-run."
      fi
      sleep 1
    done
    ok "Ollama is up on :11434 — started by CONCLAVE, so ./scripts/down.sh will stop it. (log: $log)"
  fi

  if ! ollama list 2>/dev/null | awk 'NR>1' | grep -q .; then
    die "$(printf 'Ollama is running but no models are installed.\nPull one, then re-run ./scripts/up.sh:\n  ollama pull gemma4:e4b')"
  fi
}

# Start the audit dashboard (Vite dev server) on :5173 in the background. It's a
# host-side dev server, not a compose container — down.sh stops it (by port). All
# non-fatal: a dashboard hiccup never blocks the core stack. No-op if it's already
# listening, or if npm isn't installed.
start_dashboard() {
  local port=5173 url="http://localhost:5173"
  if curl -fsS "$url" >/dev/null 2>&1; then
    info "Dashboard already running on :${port} (left as-is)."
    return 0
  fi
  command -v npm >/dev/null 2>&1 || {
    warn "npm not found — skipping dashboard. Start it later with ./scripts/dashboard.sh"
    return 0
  }
  if [ ! -d dashboard/node_modules ]; then
    info "Installing dashboard deps (first run)…"
    ( cd dashboard && npm install --no-audit --no-fund ) || {
      warn "Dashboard 'npm install' failed — skipping. Run ./scripts/dashboard.sh by hand."
      return 0
    }
  fi
  local log="${TMPDIR:-/tmp}/conclave-dashboard.log"
  info "Starting dashboard dev server on :${port}…"
  ( cd dashboard && nohup npm run dev >"$log" 2>&1 & )
  local tries=0
  until curl -fsS "$url" >/dev/null 2>&1; do
    tries=$((tries + 1))
    if [ "$tries" -ge 20 ]; then
      warn "Dashboard not up yet (see $log) — it may still be starting on ${url}."
      return 0
    fi
    sleep 1
  done
  ok "Dashboard up → ${url}  (log: $log)"
}

# ──────────────────────────────────────────────────────────────────────────────

MODE="${1:-}"; DOMAIN="${2:-}"
PROVIDER=""; MODEL=""; BASE_URL=""

if have_gum && interactive; then
  gum style --border rounded --margin "1 0" --padding "1 3" --border-foreground 212 \
    "CONCLAVE" "real-time risk decisions — accurate · relational · explainable"
fi

# --- mode / judge backend -----------------------------------------------------
if [ -z "$MODE" ]; then
  if interactive; then
    pick_backend
  else
    MODE=serving
    info "No mode arg and no TTY → defaulting to mode=serving (judge from .env)."
  fi
fi
case "$MODE" in local|serving) ;; *) die "mode must be 'local' or 'serving' (got '$MODE')";; esac

# --- domain -------------------------------------------------------------------
if [ -z "$DOMAIN" ]; then
  if interactive; then
    pick_domain
  else
    DOMAIN=fraud
    info "No domain arg and no TTY → defaulting to domain=fraud."
  fi
fi
case "$DOMAIN" in fraud|security) ;; *) die "domain must be 'fraud' or 'security' (got '$DOMAIN')";; esac

# --- preflight ----------------------------------------------------------------
command -v docker >/dev/null 2>&1 || die "docker not found on PATH"
docker info >/dev/null 2>&1        || die "Docker daemon not running — start Docker Desktop first"
[ -f .env ] || warn ".env not found (cp .env.example .env). Serving mode reads your API key from it."

export SPRING_PROFILES_ACTIVE="$DOMAIN"

# --- resolve + validate the judge --------------------------------------------
if [ "$MODE" = "local" ]; then
  export JUDGE_LLM_PROVIDER="ollama"
  export OLLAMA_BASE_URL="${OLLAMA_BASE_URL:-http://host.docker.internal:11434}"
  # Local CPU Ollama judges are slow (~60-90s). Give the deliberation gRPC call a
  # generous 3-minute deadline so decisions get persisted rather than cancelled.
  export DELIBERATION_DEADLINE_MS="${DELIBERATION_DEADLINE_MS:-180000}"

  ensure_ollama  # strict: dies on missing CLI / dead server / no models

  # Model: interactive pick → JUDGE_LLM_MODEL from env/.env → gemma4:e4b default.
  [ -z "$MODEL" ] && MODEL="${JUDGE_LLM_MODEL:-}"
  if interactive && [ -z "$MODEL" ]; then MODEL="$(pick_ollama_model)"; fi
  export JUDGE_LLM_MODEL="${MODEL:-gemma4:e4b}"
  info "Judge: local Ollama (${OLLAMA_BASE_URL}), model=${JUDGE_LLM_MODEL}"
else
  # serving — provider/model from the interactive pick, else straight from .env.
  [ -n "$PROVIDER" ] && export JUDGE_LLM_PROVIDER="$PROVIDER"
  [ -n "$MODEL" ]    && export JUDGE_LLM_MODEL="$MODEL"
  [ -n "$BASE_URL" ] && export OPENAI_BASE_URL="$BASE_URL"

  # Which provider are we really using? pick → .env → compose default (anthropic).
  use_provider="${PROVIDER:-$(envval JUDGE_LLM_PROVIDER)}"
  use_provider="${use_provider:-anthropic}"
  case "$use_provider" in
    anthropic) need_key=ANTHROPIC_API_KEY ;;
    openai)    need_key=OPENAI_API_KEY ;;
    *)         need_key="" ;;
  esac

  # Error with instructions (never edit .env, never prompt for the key).
  if [ -n "$need_key" ] && ! has_cred "$need_key"; then
    case "$need_key" in
      OPENAI_API_KEY)
        die "$(printf 'This judge uses an OpenAI-compatible API but OPENAI_API_KEY is not set.\nAdd your key (and base URL) to .env, then re-run ./scripts/up.sh:\n  OPENAI_API_KEY=…              # OpenAI sk-… or OpenRouter sk-or-…\n  OPENAI_BASE_URL=%s' "${BASE_URL:-${OPENAI_BASE_URL:-https://api.openai.com/v1}}")" ;;
      ANTHROPIC_API_KEY)
        die "$(printf 'This judge uses Anthropic (Claude) but ANTHROPIC_API_KEY is not set.\nAdd it to .env, then re-run ./scripts/up.sh:\n  ANTHROPIC_API_KEY=sk-ant-…')" ;;
    esac
  fi
  info "Judge: serving → provider=${use_provider}, model=${MODEL:-${JUDGE_LLM_MODEL:-<provider default from .env>}}"
fi

# --- build + boot -------------------------------------------------------------
info "Building Java service jars  (JAVA_HOME=${JAVA_HOME:-<unset>})…"
mvn -q -pl orchestrator,baseline,graph -am -DskipTests package

info "Building Docker images…"
$COMPOSE build

info "Starting stack: domain=${DOMAIN}  mode=${MODE}…"
$COMPOSE up -d

# Bring up the host-side audit dashboard too, so `up` gives you the full demo.
start_dashboard

ok "Stack is up."
echo "  Audit API   : http://localhost:8080/api/v1/decisions"
echo "  Neo4j UI    : http://localhost:7474   (neo4j / conclave-graph)"
echo "  Dashboard   : http://localhost:5173"
echo
echo "  Send events : ./scripts/seed.sh $DOMAIN"
echo "  Tail logs   : ./scripts/logs.sh"
echo "  Tear down   : ./scripts/down.sh"
