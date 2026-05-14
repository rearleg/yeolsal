#!/usr/bin/env bash
# verify.sh — repo-wide pre-push verification.
#
# Story 1.4 AC11 opt-in: set BOOT_SMOKE=true to also run the Testcontainers-
# gated integration tests (`V11MigrationIT`, `SurvivalStateRosterIT`,
# `SurvivalStateEvaluatorIT`, `RoomControllerIT`). Default off — those
# require Docker.
#
# Usage:
#   bash scripts/verify.sh                 # default cycle (no Docker required)
#   BOOT_SMOKE=true bash scripts/verify.sh # also run opt-in BE IT layer
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# BOOT_SMOKE is propagated to scripts/test.sh, which forwards it to
# Gradle as `-Dyeosal.boot-smoke=true` when set.
export BOOT_SMOKE="${BOOT_SMOKE:-false}"

"$ROOT_DIR/scripts/test.sh"
"$ROOT_DIR/scripts/build.sh"
