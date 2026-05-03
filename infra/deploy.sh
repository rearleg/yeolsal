#!/usr/bin/env bash
# Convenience wrapper around `docker compose up -d --build api` that
# stamps the current main commit into /app/COMMIT inside the image.
#
# Run from the repo root or anywhere — it cd's to its own directory
# (the infra/ folder) so the docker-compose.yml resolves consistently.
#
# Usage:
#   ./infra/deploy.sh            # api only (default)
#   ./infra/deploy.sh full       # all services
#
# After:
#   docker compose exec api cat /app/COMMIT
# should print the revision the running container was built from.
set -euo pipefail

cd "$(dirname "$0")"

# Resolve the host repo's HEAD without depending on the cwd at call
# time. Falls back to "unknown" if we can't reach git for some reason
# (e.g. the operator extracted the infra dir alone) so the build still
# succeeds.
GIT_SHA="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
export GIT_SHA

mode="${1:-api}"
case "$mode" in
  api)
    docker compose up -d --build api
    ;;
  full)
    docker compose up -d --build
    ;;
  *)
    echo "usage: $0 [api|full]" >&2
    exit 64
    ;;
esac

echo "[deploy] embedded GIT_SHA=${GIT_SHA}"
echo "[deploy] verify with: docker compose exec api cat /app/COMMIT"
