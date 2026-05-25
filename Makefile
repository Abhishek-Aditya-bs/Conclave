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
.PHONY: m1-test m1-verify
m1-test: check-java ## Run M1 unit tests only.
	$(MVN) -pl orchestrator -q test

m1-verify: check-java ## Run M1 unit + integration tests.
	$(MVN) -pl orchestrator verify

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
