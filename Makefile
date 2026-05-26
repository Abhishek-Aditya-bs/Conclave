# CONCLAVE Makefile
#
# All targets pin JAVA_HOME to the keg-only openjdk@25 install so a
# contributor doesn't need to touch their shell config. Override
# JAVA_HOME on the command line if you've installed Java 25 elsewhere.

JAVA_HOME ?= /opt/homebrew/opt/openjdk@25
export JAVA_HOME
export PATH := $(JAVA_HOME)/bin:$(PATH)

MVN := mvn

.PHONY: help build test verify coverage clean check-java

help: ## Show this help.
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z0-9_-]+:.*?## / {printf "  \033[1m%-20s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

check-java: ## Verify Java 25 is on the PATH.
	@java -version 2>&1 | head -1 | grep -q '"25' || (echo "❌ Java 25 not found. JAVA_HOME=$(JAVA_HOME)" && exit 1)
	@echo "✅ $$(java -version 2>&1 | head -1)"

build: check-java ## Compile all modules.
	$(MVN) -q compile

test: check-java ## Run unit tests only (fast, no Docker).
	$(MVN) -q test

verify: check-java ## Full build: compile + unit tests + integration tests + coverage threshold.
	$(MVN) verify

coverage: verify ## Run verify then open the aggregated JaCoCo HTML report.
	@open orchestrator/target/site/jacoco-merged/index.html 2>/dev/null || \
	  echo "Report at: orchestrator/target/site/jacoco-merged/index.html"

clean: ## Remove all build output.
	$(MVN) -q clean

# ---- Module shortcuts ----
.PHONY: m1-test m1-verify m9-test m9-verify m9-run-fraud m9-run-security
m1-test: check-java ## Run M1 unit tests only.
	$(MVN) -pl orchestrator -q test

m1-verify: check-java ## Run M1 unit + integration tests.
	$(MVN) -pl orchestrator verify

# ---- M9 (synthetic data generators) ----
#
# `make m9-run-fraud` / `make m9-run-security` assume a Kafka broker is
# already up on localhost:9092 (or KAFKA_BOOTSTRAP_SERVERS) with the
# Confluent Schema Registry reachable at SCHEMA_REGISTRY_URL.
m9-test: check-java ## Run M9 unit tests only.
	$(MVN) -pl generators -am -q test

m9-verify: check-java ## Run M9 unit + integration tests (Testcontainers Kafka).
	$(MVN) -pl generators -am verify

m9-run-fraud: check-java ## Launch the fraud generator against the local broker.
	$(MVN) -pl generators -am -q compile
	$(MVN) -pl generators -q exec:java \
	  -Dexec.mainClass=io.conclave.generators.fraud.FraudGeneratorMain \
	  -Dexec.args="$(ARGS)"

m9-run-security: check-java ## Launch the security generator against the local broker.
	$(MVN) -pl generators -am -q compile
	$(MVN) -pl generators -q exec:java \
	  -Dexec.mainClass=io.conclave.generators.security.SecurityGeneratorMain \
	  -Dexec.args="$(ARGS)"

# ---- M5 (Python LangGraph) ----
#
# M5 lives outside Maven on purpose — it is a Python sidecar managed by uv,
# not a JAR. These targets assume `uv` is on PATH (install with
# `brew install uv` or `curl -LsSf https://astral.sh/uv/install.sh | sh`).
UV := uv

.PHONY: check-uv m5-install m5-gen-proto m5-lint m5-test m5-coverage m5-run

check-uv: ## Verify uv is on the PATH.
	@command -v $(UV) >/dev/null 2>&1 || (echo "❌ uv not found. brew install uv" && exit 1)
	@echo "✅ $$($(UV) --version)"

m5-install: check-uv ## Sync M5 Python deps via uv.
	cd agents && $(UV) sync --extra dev

m5-gen-proto: m5-install ## Regenerate Python gRPC stubs from agents/proto/*.proto.
	cd agents && $(UV) run ./scripts/gen_protos.sh

m5-lint: m5-install ## Lint M5 with ruff.
	cd agents && $(UV) run ruff check .

m5-test: m5-gen-proto ## Run M5 pytest suite with coverage gate (80% line).
	cd agents && $(UV) run pytest

m5-coverage: m5-test ## Open the M5 HTML coverage report.
	@open agents/htmlcov/index.html 2>/dev/null || \
	  echo "Report at: agents/htmlcov/index.html"

m5-run: m5-install ## Launch the M5 gRPC server (needs M3 + M4 reachable).
	cd agents && $(UV) run python -m deliberation.server.entrypoint

# ---- M8 (docker-compose demo harness) ----
#
# Brings up the full data plane (Kafka + Schema Registry + Postgres pgvector
# + Neo4j) + all four CONCLAVE services. Domain (fraud | security) selected
# via SPRING_PROFILES_ACTIVE on the orchestrator. The other three services
# are domain-agnostic.
#
# `make demo-fraud` / `make demo-security` use the Anthropic judge (needs
# ANTHROPIC_API_KEY in .env). The `-local` variants layer
# docker-compose.ollama.yml on top to swap in an Ollama sidecar with
# qwen3:8b. See docker-compose.yml + .env.example.
#
# After the stack is up, the targets run the M9 generator from the host
# (against localhost:9092) so events flow through the live pipeline.
COMPOSE := docker compose
COMPOSE_OLLAMA := $(COMPOSE) -f docker-compose.yml -f docker-compose.ollama.yml

.PHONY: demo-build demo-fraud demo-security demo-fraud-local demo-security-local \
        demo-stop demo-logs demo-status

demo-build: check-java ## Package service jars + build the four CONCLAVE Docker images.
	$(MVN) -pl orchestrator,baseline,graph -am -DskipTests -q package
	$(COMPOSE) build

demo-fraud: demo-build ## Boot the full stack in fraud mode and emit a starter event burst.
	SPRING_PROFILES_ACTIVE=fraud $(COMPOSE) up -d
	@echo "▶ stack up. waiting 15s for services to register…" && sleep 15
	@$(MAKE) m9-run-fraud ARGS="--clean 200 --rings 2 --ato 1 --extra 1"
	@echo "✓ demo running. dashboard contract: http://localhost:8080/api/v1/decisions"
	@echo "  tail logs: make demo-logs"
	@echo "  stop:      make demo-stop"

demo-security: demo-build ## Boot the full stack in security mode and emit a starter event burst.
	SPRING_PROFILES_ACTIVE=security $(COMPOSE) up -d
	@echo "▶ stack up. waiting 15s for services to register…" && sleep 15
	@$(MAKE) m9-run-security ARGS="--clean 200 --rings 1 --ato 1 --extra 1"
	@echo "✓ demo running. dashboard contract: http://localhost:8080/api/v1/decisions"

demo-fraud-local: demo-build ## Same as demo-fraud but with the Ollama sidecar (no API key).
	JUDGE_LLM_PROVIDER=ollama JUDGE_LLM_MODEL=qwen3:8b SPRING_PROFILES_ACTIVE=fraud \
	  $(COMPOSE_OLLAMA) up -d
	@echo "▶ stack up (ollama path). first boot pulls qwen3:8b (~6 GB) — may take a few minutes."
	@echo "  tail Ollama progress: docker logs -f conclave-ollama"
	@sleep 20
	@$(MAKE) m9-run-fraud ARGS="--clean 100 --rings 1 --ato 1 --extra 0"

demo-security-local: demo-build ## Same as demo-security but with the Ollama sidecar (no API key).
	JUDGE_LLM_PROVIDER=ollama JUDGE_LLM_MODEL=qwen3:8b SPRING_PROFILES_ACTIVE=security \
	  $(COMPOSE_OLLAMA) up -d
	@echo "▶ stack up (ollama path). first boot pulls qwen3:8b (~6 GB) — may take a few minutes."
	@sleep 20
	@$(MAKE) m9-run-security ARGS="--clean 100 --rings 1 --ato 1 --extra 0"

demo-stop: ## Stop and remove all stack containers (keeps volumes).
	$(COMPOSE_OLLAMA) down

demo-logs: ## Tail logs from every CONCLAVE service in one terminal.
	$(COMPOSE) logs -f orchestrator baseline graph agents

demo-status: ## Show stack status + decision counts.
	@$(COMPOSE) ps
	@echo ""
	@echo "Recent decisions (last 10):"
	@curl -fsS "http://localhost:8080/api/v1/decisions?limit=10" 2>/dev/null \
	  | python3 -m json.tool 2>/dev/null \
	  || echo "  (orchestrator REST not reachable; is the stack up?)"

# ---- M10 (dashboard + marketing website) ----
.PHONY: dashboard-install dashboard-dev dashboard-build website-install website-dev website-build

dashboard-install: ## Install dashboard npm deps.
	cd dashboard && npm install --no-audit --no-fund

dashboard-dev: dashboard-install ## Run the dashboard dev server (proxies /api → orchestrator).
	cd dashboard && npm run dev

dashboard-build: dashboard-install ## Build the dashboard for production (Cloudflare Pages).
	cd dashboard && npm run build

website-install: ## Install marketing-site npm deps.
	cd website && npm install --no-audit --no-fund

website-dev: website-install ## Run the marketing site dev server on :5174.
	cd website && npm run dev

website-build: website-install ## Build the marketing site for production.
	cd website && npm run build

# ---- Cloudflare Pages deploy (marketing website) ----
#
# One-time setup (already done for the production project `conclave`):
#   npx wrangler login                              # OAuth in browser
#   npx wrangler pages project create conclave \
#     --production-branch main
#
# Then `make website-deploy` does build + upload. Production URL:
# https://conclave-4q9.pages.dev (the trailing slug was assigned by
# Cloudflare because the bare "conclave" project name was already taken).
.PHONY: website-deploy
website-deploy: website-build ## Deploy the marketing site to Cloudflare Pages (needs `wrangler login`).
	npx wrangler pages deploy website/dist \
	  --project-name conclave \
	  --branch main \
	  --commit-dirty=true
