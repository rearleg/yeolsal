// Story 8.5 AC15 row 8 — analytics-taxonomy-lint helper tests.
// Uses node:test like the brand-voice + contrast tools tests.

import { test } from "node:test";
import { strict as assert } from "node:assert";
import { mkdtempSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";

import {
  collectFiles,
  lintContent,
  lintFiles,
  loadCatalogue,
  loadTypedCatalogue,
  __testing,
} from "../analytics-taxonomy-lint.ts";

function tempDoc(body: string): string {
  const dir = mkdtempSync(join(tmpdir(), "yeosal-lint-"));
  const path = join(dir, "analytics.md");
  writeFileSync(path, body, "utf8");
  return path;
}

test("loadCatalogue parses the locked ```analytics-events fenced block", () => {
  const path = tempDoc([
    "# heading",
    "",
    "```analytics-events",
    "signup.completed",
    "onboarding.completed",
    "revival.attempted",
    "```",
    "",
    "tail",
  ].join("\n"));
  const cat = loadCatalogue(path);
  assert.deepEqual([...cat], [
    "signup.completed",
    "onboarding.completed",
    "revival.attempted",
  ]);
});

test("loadCatalogue throws when docs/analytics.md has no fenced block (only failure mode)", () => {
  const path = tempDoc("# Just text, no fence here.\n");
  assert.throws(() => loadCatalogue(path), /analytics-events fenced block/);
});

test("loadCatalogue throws when the file is missing entirely", () => {
  assert.throws(
    () => loadCatalogue("/nonexistent/path/analytics.md"),
    /Cannot read/,
  );
});

test("loadTypedCatalogue parses ANALYTICS_EVENTS", () => {
  const dir = mkdtempSync(join(tmpdir(), "yeosal-typed-catalogue-"));
  const path = join(dir, "analytics.ts");
  writeFileSync(
    path,
    `export const ANALYTICS_EVENTS = ["signup.completed", "onboarding.completed"] as const;\n`,
    "utf8",
  );
  assert.deepEqual([...loadTypedCatalogue(path)], [
    "signup.completed",
    "onboarding.completed",
  ]);
});

test("doc and typed catalogues must match in order", () => {
  assert.equal(
    __testing.sameCatalogue(
      ["signup.completed", "onboarding.completed"],
      ["signup.completed", "onboarding.completed"],
    ),
    true,
  );
  assert.equal(
    __testing.sameCatalogue(
      ["signup.completed", "onboarding.completed"],
      ["onboarding.completed", "signup.completed"],
    ),
    false,
  );
});

test("FE captureEvent('...') with known event → 0 warnings", () => {
  const catalogue = new Set(["signup.completed", "onboarding.completed"]);
  const file = {
    absPath: "/fake/FE/src/auth/AuthContext.tsx",
    relPath: "FE/src/auth/AuthContext.tsx",
    ext: ".tsx",
  };
  const source =
    `import { captureEvent } from "@/lib/analytics";\n` +
    `captureEvent("signup.completed", { authMethod: "EMAIL" });\n`;
  const warnings = lintContent(file, source, catalogue);
  assert.equal(warnings.length, 0);
});

test("FE captureEvent('...') with rogue event → 1 warning, exit 0 (warn-only)", () => {
  const catalogue = new Set(["signup.completed"]);
  const file = {
    absPath: "/fake/FE/src/auth/AuthContext.tsx",
    relPath: "FE/src/auth/AuthContext.tsx",
    ext: ".tsx",
  };
  const source = `captureEvent("totally.rogue.event", {});\n`;
  const warnings = lintContent(file, source, catalogue);
  assert.equal(warnings.length, 1);
  assert.equal(warnings[0]!.eventName, "totally.rogue.event");
  assert.equal(warnings[0]!.source, "FE");
});

test("BE capture extraction is independent of the receiver variable name", () => {
  const catalogue = new Set(["signup.completed"]);
  const file = {
    absPath: "/fake/BE/src/main/java/com/yeosal/api/x/X.java",
    relPath: "BE/src/main/java/com/yeosal/api/x/X.java",
    ext: ".java",
  };
  const source =
    `productAnalytics.capture(distinctId, "rogue.be.event", props);\n`;
  const warnings = lintContent(file, source, catalogue);
  assert.equal(warnings.length, 1);
  assert.equal(warnings[0]!.source, "BE");
  assert.equal(warnings[0]!.eventName, "rogue.be.event");
});

test("collectFiles + lintFiles walks a tiny directory and reports warnings", () => {
  const dir = mkdtempSync(join(tmpdir(), "yeosal-lint-files-"));
  const tsPath = join(dir, "sample.ts");
  writeFileSync(tsPath, `captureEvent("rogue.fe");\n`, "utf8");
  const files = collectFiles([
    { absPath: dir, label: "tmp", extensions: new Set([".ts", ".tsx"]) },
  ]);
  assert.ok(files.length >= 1);
  const warnings = lintFiles(files, ["signup.completed"]);
  assert.ok(warnings.some((w) => w.eventName === "rogue.fe"));
});
