# The local stack (T-9.9).
#
# Two commands that matter: `make up` and `make reset`. Everything else is a
# convenience on top of them.
#
# `up` does not finish when compose does. It waits until the stack is actually
# serving, because the gap between "the containers started" and "the stack
# works" is where the interesting failures live -- Keycloak in particular is
# accepting connections for some time before its realm exists.

SHELL := /bin/sh
COMPOSE := docker compose

.DEFAULT_GOAL := help
.PHONY: help up down reset logs ps psql token realm-export realm-apply realm-reset realm-relink env

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

# The long-running services. minio-init is deliberately not here: it is a
# one-shot job that creates the buckets and exits, and `--wait` treats a
# container that stopped as a service that failed.
WAIT_FOR := postgres keycloak minio nats valkey content-origin

up: ## Start everything and wait until it is actually serving
	$(COMPOSE) up -d
	$(COMPOSE) up -d --no-recreate --wait $(WAIT_FOR)
	@# Health is not the same as serving. Keycloak reports ready while its realm
	@# import is still running, and a service that starts against a realm-less
	@# Keycloak fails in a way that reads as a configuration error.
	@printf 'waiting for the realm'
	@until curl -sf http://localhost:8081/realms/xenopslearn >/dev/null 2>&1; do printf '.'; sleep 2; done
	@echo ' serving'
	@echo
	@echo "  app origin      http://localhost:8080   (services, not running yet)"
	@echo "  web             http://localhost:5173   (frontend, not running yet)"
	@echo "  content origin  http://localhost:8090   -- a DIFFERENT origin, on purpose"
	@echo "  keycloak        http://localhost:8081   admin / admin"
	@echo "  minio console   http://localhost:9001"
	@echo "  nats monitor    http://localhost:8222"
	@echo
	@echo "  users: acme-admin, acme-learner, globex-admin, globex-learner, platform-admin"
	@echo "  password is the username. Development only."

down: ## Stop everything, keep the data
	$(COMPOSE) down

reset: ## Destroy the data and rebuild from the seed
	$(COMPOSE) down -v
	$(MAKE) up

logs: ## Follow logs (make logs S=keycloak for one service)
	$(COMPOSE) logs -f $(S)

ps: ## What is running
	$(COMPOSE) ps

psql: ## Open psql against a module database (make psql D=identity)
	$(COMPOSE) exec postgres psql -U $(or $(D),postgres) -d $(or $(D),postgres)

token: ## Print an access token for a user (make token U=acme-learner)
	@curl -s -X POST http://localhost:8081/realms/xenopslearn/protocol/openid-connect/token \
		-d grant_type=password \
		-d client_id=local-tests \
		-d client_secret=local-development-only-test-secret \
		-d username=$(or $(U),acme-learner) \
		-d password=$(or $(U),acme-learner) \
		| sed 's/.*"access_token":"\([^"]*\)".*/\1/'

# ---------------------------------------------------------------------------
# The realm (T-1.7)
#
# Three paths, and which one is safe depends entirely on whether the Keycloak in
# question has real people in it:
#
#   realm-apply   settings, clients and roles, never a user. The path for any
#                 environment with customers, and the one CI would run.
#   realm-reset   deletes the realm and every account in it, then imports.
#                 Development only, and guarded three ways so it stays that way.
#   realm-relink  the recovery, when a realm came back with new subjects.
#   realm-export  what the running realm actually contains, to diff against the
#                 file before changing either.
#
# docs/runbooks/keycloak-realm.md is the procedure; these are its commands.
# ---------------------------------------------------------------------------

realm-apply: ## Apply the realm file without touching any user -- the safe path
	@bash scripts/realm-apply.sh

realm-reset: ## DESTROY the local realm and import it again -- development only
	@CONFIRM=destroy-xenopslearn bash scripts/realm-reset.sh

realm-relink: ## Report the idp_sub repairs a rebuilt realm needs (APPLY=1 to run them)
	@bash scripts/realm-relink.sh

realm-export: ## Print the running realm as JSON, for diffing against the file
	@bash scripts/realm-export.sh

# ---------------------------------------------------------------------------
# The frontend (T-10.1)
#
# Run on the host rather than in a container, deliberately: what deploys is a
# static build, the dev server is a development tool, and node_modules in a
# bind mount is slow enough on some machines to change how people work. It
# still runs against the real stack -- its /api proxy points at the services
# `make up` started, not at mocks. docs/frontend.md has the rest.
# ---------------------------------------------------------------------------

web: ## Run the frontend dev server on http://localhost:5173 (needs `make up`)
	@cd web && npm install --no-audit --no-fund && npm run dev

# ---------------------------------------------------------------------------
# Services
#
# Every target here resolves the JDK itself rather than trusting JAVA_HOME.
# That is not defensive coding, it is the bug this machine already had: a Java 8
# JRE first on PATH, JAVA_HOME on 21, and the 25 the build needs installed but
# unreferenced. Setting JAVA_HOME does not change which `java` runs, so `java
# -jar` still picked 8 and failed with UnsupportedClassVersionError naming
# neither JDK.
# ---------------------------------------------------------------------------

JAVA_HOME_RESOLVED = $(shell bash scripts/java-home.sh)

.PHONY: java-home build test run web

java-home: ## Report which JDK the build will use, and why
	@echo "required:  Java $$(grep -oE '<java\.version>[0-9]+' services/pom.xml | head -1 | grep -oE '[0-9]+')  (services/pom.xml)"
	@echo "JAVA_HOME: $${JAVA_HOME:-<unset>}"
	@printf "selected:  "; bash scripts/java-home.sh

build: ## Compile and install every service
	@JAVA_HOME="$(JAVA_HOME_RESOLVED)" mvn -f services/pom.xml -DskipTests install

test: ## Run every service's tests
	@JAVA_HOME="$(JAVA_HOME_RESOLVED)" mvn -f services/pom.xml test

run: ## Run one service against the local stack (make run S=identity)
	@JAVA_HOME="$(JAVA_HOME_RESOLVED)" 		"$(JAVA_HOME_RESOLVED)/bin/java" -jar services/$(or $(S),identity)/target/$(or $(S),identity)-0.0.1-SNAPSHOT.jar


# ------------------------------------------------------------------------------
# Credentials.
#
# THIS TARGET REPORTS; IT DOES NOT SOURCE. `make` guarding on the variables
# rather than reading the file itself is deliberate: a Makefile that sources
# credentials means `make` and your shell disagree about what is set, and a
# credential that exists only inside make is one you cannot reproduce by hand at
# the moment something breaks. So the human sources it once per terminal and
# make says so when it is missing -- the stemcell's convention, for that reason.
#
# The file lives OUTSIDE this repository. .gitignore is a rule somebody can
# defeat without meaning to; a path is not.
# ------------------------------------------------------------------------------
# Written as ~ rather than $(HOME). On Windows make expands HOME to the native
# form, whose backslashes are escape characters to the shell that has to run the
# copy -- so the instruction this target prints would not paste back in.
ENV_FILE := ~/.xenopsbase-learn.env

CREDENTIALS := MEDIA_PROVIDER CF_STREAM_ACCOUNT_ID CF_STREAM_API_TOKEN \
	CF_STREAM_CUSTOMER_SUBDOMAIN CF_STREAM_SIGNING_KEY_ID CF_STREAM_SIGNING_KEY_JWK \
	CF_STREAM_WEBHOOK_SECRET REALM_ADMIN_URL REALM_ADMIN_CLIENT_ID REALM_ADMIN_CLIENT_SECRET

env: ## Show which credentials this shell has (names only -- never a value)
	@test -f "$$HOME/.xenopsbase-learn.env" || { \
		echo "No $(ENV_FILE) yet:"; \
		echo "  cp local/env.example $(ENV_FILE)"; \
		echo "  then fill it in and: source $(ENV_FILE)"; \
		echo; }
	@echo "credentials in this shell:"
	@for v in $(CREDENTIALS); do \
		if [ -n "$$(printenv $$v)" ]; then echo "  set    $$v"; else echo "  unset  $$v"; fi; \
	done
	@echo
	@echo "The local stack needs none of these -- make up and make run work empty."
	@echo "To prove the Cloudflare ones really work: bash scripts/cloudflare-check.sh"
