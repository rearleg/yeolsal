#!/usr/bin/env bash
set -euo pipefail

COMMAND="${1:-}"
DOMAIN="${POSTHOG_DOMAIN:-}"
UPSTREAM_REF="${POSTHOG_UPSTREAM_REF:-}"
INSTALL_DIR="${POSTHOG_INSTALL_DIR:-$HOME/posthog}"

usage() {
  cat <<'EOF'
Usage:
  POSTHOG_DOMAIN=analytics.example.com \
  POSTHOG_UPSTREAM_REF=<40-char-posthog-commit> \
  infra/posthog.sh install

  POSTHOG_INSTALL_DIR=$HOME/posthog infra/posthog.sh start|stop|status|logs

The install command delegates to PostHog's official hobby installer pinned to
POSTHOG_UPSTREAM_REF. Runtime commands operate on the generated upstream stack.
EOF
}

require_install_config() {
  if [[ ! "$UPSTREAM_REF" =~ ^[0-9a-f]{40}$ ]]; then
    echo "POSTHOG_UPSTREAM_REF must be a pinned 40-character PostHog commit." >&2
    exit 2
  fi
  if [[ -z "$DOMAIN" ]]; then
    echo "POSTHOG_DOMAIN is required." >&2
    exit 2
  fi
}

require_stack() {
  if [[ ! -f "$INSTALL_DIR/docker-compose.yml" ]]; then
    echo "PostHog stack not found at $INSTALL_DIR; run install first." >&2
    exit 2
  fi
}

case "$COMMAND" in
  install)
    require_install_config
    mkdir -p "$INSTALL_DIR"
    cd "$INSTALL_DIR"
    installer_url="https://raw.githubusercontent.com/PostHog/posthog/$UPSTREAM_REF/bin/deploy-hobby"
    curl --fail --show-error --silent --location "$installer_url" |
      /bin/bash -s -- "$UPSTREAM_REF" "$DOMAIN"
    ;;
  start)
    require_stack
    docker compose -f "$INSTALL_DIR/docker-compose.yml" start
    ;;
  stop)
    require_stack
    docker compose -f "$INSTALL_DIR/docker-compose.yml" stop
    ;;
  status)
    require_stack
    docker compose -f "$INSTALL_DIR/docker-compose.yml" ps
    ;;
  logs)
    require_stack
    docker compose -f "$INSTALL_DIR/docker-compose.yml" logs --tail=200
    ;;
  *)
    usage
    exit 2
    ;;
esac
