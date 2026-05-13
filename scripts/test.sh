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

# Story 1.5 AC13 — brand-voice (NFR-9.6.1 hard CI gate) + WCAG 2.2 AA contrast.
# Silent-skip when the tools workspace is uninstalled (matches the FE guard
# pattern above): the fast-path doesn't fail on a missing dev dep, but a
# real violation when the tools DO run is a hard failure.
if [ -x "$ROOT_DIR/tools/node_modules/.bin/tsx" ]; then
  (cd "$ROOT_DIR" && tools/node_modules/.bin/tsx tools/brand-voice-lint.ts)
  (cd "$ROOT_DIR" && tools/node_modules/.bin/tsx tools/contrast-check.ts)
  (cd "$ROOT_DIR/tools" && ./node_modules/.bin/tsx --test __tests__/brand-voice-lint.test.ts __tests__/contrast-check.test.ts)
else
  echo "tools dependencies are not installed; skipping brand-voice-lint + contrast-check. Run npm install in yeosal/tools."
fi

if [ -x "$ROOT_DIR/BE/gradlew" ]; then
  (cd "$ROOT_DIR/BE" && ./gradlew test --no-daemon)
elif command -v gradle >/dev/null 2>&1; then
  (cd "$ROOT_DIR/BE" && gradle test --no-daemon)
else
  echo "Gradle is not installed and BE/gradlew is absent; skipping BE tests."
fi
