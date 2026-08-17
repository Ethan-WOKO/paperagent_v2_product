#!/usr/bin/env bash

set -Eeuo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/common.sh"

BRANCH="${BRANCH:-main}"

require_app_files
lock_deployment

cd "$APP_DIR"
current_branch="$(git branch --show-current)"
[[ "$current_branch" == "$BRANCH" ]] ||
  fail "Deployment checkout is on '$current_branch'; switch it to '$BRANCH' before updating"
if [[ "${PAPERAGENT_UPDATE_REEXEC:-0}" != "1" ]]; then
  if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
    echo "WARNING: tracked local changes exist; Git will preserve them or stop if they conflict."
  fi

  before="$(git rev-parse HEAD)"
  git -c http.version=HTTP/1.1 pull --ff-only origin "$BRANCH"
  after="$(git rev-parse HEAD)"
  if [[ "$before" != "$after" ]]; then
    export PAPERAGENT_UPDATE_REEXEC=1
    export PAPERAGENT_DEPLOY_LOCK_HELD=1
    exec bash "$SCRIPT_DIR/update.sh"
  fi
fi

start_stack --build
