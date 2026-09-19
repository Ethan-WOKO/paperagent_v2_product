#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="${APP_DIR:-$(cd -- "$SCRIPT_DIR/../.." && pwd)}"
COMPOSE_FILE="$APP_DIR/docker-compose.prod.yml"
ENV_FILE="$APP_DIR/.env"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

require_app_files() {
  [[ -f "$COMPOSE_FILE" ]] || fail "Missing compose file: $COMPOSE_FILE"
  [[ -f "$ENV_FILE" ]] || fail "Missing environment file: $ENV_FILE"
  command -v docker >/dev/null 2>&1 || fail "Docker is not installed or not on PATH"
  docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is unavailable"
}

env_value() {
  local key="$1"
  local line
  line="$(grep -E "^${key}=" "$ENV_FILE" | tail -n 1 || true)"
  printf '%s' "${line#*=}" | tr -d '\r'
}

is_true() {
  [[ "${1,,}" == "true" || "$1" == "1" ]]
}

sandbox_enabled() {
  is_true "$(env_value YANBAN_SANDBOX_ENABLED)"
}

reactplan_enabled() {
  local value
  value="$(env_value YANBAN_AGENT_REACTPLAN_ENABLED)"
  is_true "${value:-true}"
}

require_env_value() {
  local key="$1"
  local value
  value="$(env_value "$key")"
  [[ -n "$value" ]] || fail "$key must be set in $ENV_FILE"
  [[ "$value" != replace-* && "$value" != your_* ]] || fail "$key still contains an example value"
}

require_secret_min_length() {
  local key="$1"
  local minimum="$2"
  local value
  value="$(env_value "$key")"
  [[ -n "$value" && "$value" != replace-* && "$value" != your_* ]] ||
    fail "$key must be replaced with a deployment secret"
  [[ ${#value} -ge $minimum ]] || fail "$key must contain at least $minimum characters"
}

validate_deployment_env() {
  if reactplan_enabled; then
    is_true "$(env_value YANBAN_AGENT_ENGINE_GATEWAY_ENABLED)" ||
      fail "YANBAN_AGENT_ENGINE_GATEWAY_ENABLED must be true when ReAct is enabled"
    require_secret_min_length YANBAN_AGENT_REACTPLAN_ENGINE_SERVICE_TOKEN 32
    require_secret_min_length YANBAN_AGENT_ENGINE_GATEWAY_TASK_GRANT_SECRET 32
    require_env_value DEEPSEEK_API_KEY
    sandbox_enabled || fail "YANBAN_SANDBOX_ENABLED must be true for the production ReAct stack"
  fi

  if ! sandbox_enabled; then
    return
  fi

  local provider broker_url broker_token db_name db_user db_password
  provider="$(env_value YANBAN_SANDBOX_PROVIDER)"
  broker_url="$(env_value YANBAN_SANDBOX_BROKER_URL)"
  broker_token="$(env_value YANBAN_SANDBOX_BROKER_TOKEN)"
  db_name="$(env_value YANBAN_SANDBOX_DB_NAME)"
  db_user="$(env_value YANBAN_SANDBOX_DB_USER)"
  db_password="$(env_value YANBAN_SANDBOX_DB_PASSWORD)"

  [[ "${provider,,}" == "e2b" ]] || fail "Server sandbox provider must be e2b"
  [[ "$broker_url" == "http://sandbox-broker:8091" ]] ||
    fail "YANBAN_SANDBOX_BROKER_URL must be http://sandbox-broker:8091 for Compose deployment"
  [[ ${#broker_token} -ge 32 ]] || fail "YANBAN_SANDBOX_BROKER_TOKEN must contain at least 32 characters"
  [[ "$db_name" =~ ^[A-Za-z0-9_]{1,64}$ ]] || fail "YANBAN_SANDBOX_DB_NAME is invalid"
  [[ "$db_user" =~ ^[A-Za-z0-9_]{1,32}$ ]] || fail "YANBAN_SANDBOX_DB_USER is invalid"
  [[ ${#db_password} -ge 16 && ${#db_password} -le 128 ]] ||
    fail "YANBAN_SANDBOX_DB_PASSWORD must contain 16 to 128 characters"
  [[ "$db_password" =~ ^[A-Za-z0-9._~-]+$ ]] ||
    fail "YANBAN_SANDBOX_DB_PASSWORD may contain only letters, digits, dot, underscore, tilde, and hyphen"

  require_env_value MYSQL_ROOT_PASSWORD
  require_env_value E2B_API_KEY
  require_env_value YANBAN_E2B_TEMPLATE
}

compose() {
  local profile_args=()
  if sandbox_enabled; then
    profile_args+=(--profile sandbox)
  fi
  if reactplan_enabled; then
    profile_args+=(--profile reactplan)
  fi
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "${profile_args[@]}" "$@"
}

lock_deployment() {
  if [[ "${PAPERAGENT_DEPLOY_LOCK_HELD:-0}" == "1" ]]; then
    return
  fi
  if command -v flock >/dev/null 2>&1; then
    exec 9>"$APP_DIR/.paperagent-deploy.lock"
    flock -n 9 || fail "Another PaperAgent management task is already running"
  fi
}

app_http_port() {
  local port
  port="$(env_value APP_HTTP_PORT)"
  port="${port:-18080}"
  [[ "$port" =~ ^[0-9]+$ ]] || fail "APP_HTTP_PORT must be a numeric port"
  printf '%s' "$port"
}

wait_for_service() {
  local service="$1"
  local timeout="${2:-180}"
  local deadline=$((SECONDS + timeout))
  local container_id state

  while (( SECONDS < deadline )); do
    container_id="$(compose ps -q "$service" 2>/dev/null || true)"
    if [[ -n "$container_id" ]]; then
      state="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id" 2>/dev/null || true)"
      if [[ "$state" == "healthy" || "$state" == "running" ]]; then
        return
      fi
      [[ "$state" != "exited" && "$state" != "dead" ]] || fail "$service stopped before becoming ready"
    fi
    sleep 2
  done
  fail "$service did not become ready within ${timeout}s"
}

wait_for_api_health() {
  local timeout="${1:-180}"
  local deadline=$((SECONDS + timeout))
  local port
  port="$(app_http_port)"
  command -v curl >/dev/null 2>&1 || fail "curl is required for the API health check"

  while (( SECONDS < deadline )); do
    if curl --fail --silent "http://127.0.0.1:${port}/actuator/health" >/dev/null 2>&1; then
      return
    fi
    sleep 2
  done
  fail "API health check did not pass within ${timeout}s"
}

wait_for_stack() {
  local service
  for service in mysql redis elasticsearch kafka minio; do
    wait_for_service "$service" 240
  done
  if reactplan_enabled; then
    wait_for_service agent-engine-reactplan 240
  fi
  for service in api frontend; do
    wait_for_service "$service" 240
  done
  if sandbox_enabled; then
    wait_for_service sandbox-broker 240
  fi
  wait_for_api_health 240
}

start_stack() {
  validate_deployment_env
  compose up -d "$@"
  wait_for_stack
  compose ps
}
