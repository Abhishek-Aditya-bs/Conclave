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
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z_-]+:.*?## / {printf "  \033[1m%-20s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

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
