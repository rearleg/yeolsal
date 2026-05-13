// Story 1.5 AC2.3 / FE-2.3: FE-side schema smoke test.
// Validates FE/src/theme/tokens.json against the canonical
// BE/src/main/resources/tokens.schema.json at FE test time. Catches drift
// the moment tokens.json is touched, without waiting for the BE build.

import Ajv2020 from "ajv/dist/2020";
import tokensJson from "../tokens.json";
// eslint-disable-next-line @typescript-eslint/no-require-imports
const tokensSchema = require("../../../../BE/src/main/resources/tokens.schema.json");

const ajv = new Ajv2020({ strict: false, allErrors: true });
const validate = ajv.compile(tokensSchema);

describe("tokens.json conforms to tokens.schema.json (draft 2020-12)", () => {
  it("validates canonical tokens.json against the schema", () => {
    const ok = validate(tokensJson);
    if (!ok) {
      throw new Error(
        `tokens.json failed schema validation:\n` +
          (validate.errors ?? [])
            .map((e) => `  - ${e.instancePath} ${e.message}`)
            .join("\n"),
      );
    }
    expect(ok).toBe(true);
  });

  it("rejects a synthetic violation: weight = 500 (not in [300,400,600,700,800,900])", () => {
    const mutated = JSON.parse(JSON.stringify(tokensJson)) as Record<string, unknown>;
    const typography = mutated.typography as Record<string, { weight: number }>;
    typography.heading!.weight = 500;
    const ok = validate(mutated);
    expect(ok).toBe(false);
    const msg = (validate.errors ?? []).map((e) => e.message).join(" | ");
    expect(msg).toMatch(/allowed values|enum/i);
  });

  it("rejects a synthetic violation: missing 'label' field on a survival state", () => {
    const mutated = JSON.parse(JSON.stringify(tokensJson)) as Record<string, unknown>;
    const semantic = mutated.semantic as {
      survival: Record<string, Record<string, unknown>>;
    };
    delete semantic.survival.ACTIVE!.label;
    const ok = validate(mutated);
    expect(ok).toBe(false);
    const errors = (validate.errors ?? []).map((e) => `${e.instancePath} ${e.message}`);
    expect(errors.some((s) => /label/.test(s) || /required/.test(s))).toBe(true);
  });

  it("rejects a synthetic violation: blur value > 8px", () => {
    const mutated = JSON.parse(JSON.stringify(tokensJson)) as Record<string, unknown>;
    (mutated.blur as Record<string, number>).subtle = 12;
    const ok = validate(mutated);
    expect(ok).toBe(false);
  });

  it("rejects a synthetic violation: sub-mode override key outside the 16-key whitelist", () => {
    const mutated = JSON.parse(JSON.stringify(tokensJson)) as Record<string, unknown>;
    const subMode = mutated.subMode as Record<string, Record<string, unknown>>;
    subMode.bento!["color.text.disabled"] = { oklch: "oklch(50% 0 0)", hex: "#808080" };
    const ok = validate(mutated);
    expect(ok).toBe(false);
  });
});
