#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -z "${JAVA_HOME:-}" ] && [ -d "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" ]; then
  export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
fi

if [ -d "$ROOT_DIR/FE/node_modules" ]; then
  (cd "$ROOT_DIR/FE" && npm run lint && npm run typecheck && npm test)
else
  echo "FE dependencies are not installed; skipping npm typecheck/test. Run npm install in yeosal/FE."
fi

if [ -x "$ROOT_DIR/BE/gradlew" ]; then
  (cd "$ROOT_DIR/BE" && ./gradlew test --no-daemon)
elif command -v gradle >/dev/null 2>&1; then
  (cd "$ROOT_DIR/BE" && gradle test --no-daemon)
else
  echo "Gradle is not installed and BE/gradlew is absent; skipping BE tests."
fi
