package com.yeosal.api.theme;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Schema-validation contract test for {@code tokens.schema.json} (Story 1.5 AC2/AC14).
 *
 * <p>One positive case verifies the canonical {@code FE/src/theme/tokens.json} passes.
 * Four negative cases prove the schema actually rejects the failure modes Story 1.5
 * NFR-9.6.1 cares about:
 *
 * <ol>
 *   <li>Survival packed-type missing a required field (label removed).
 *   <li>Typography weight set to 500 (forbidden by the UX weight policy).
 *   <li>Sub-mode override block contains a key outside the 16-key whitelist.
 *   <li>Blur value exceeds the 8px ceiling (glassmorphism guard).
 * </ol>
 *
 * <p>Pure JUnit 5 + Jackson + json-schema-validator — no Spring context, no DB, no Docker.
 */
class TokenSchemaValidationTest {

  private static JsonSchema schema;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @BeforeAll
  static void loadSchema() throws Exception {
    try (InputStream stream =
        TokenSchemaValidationTest.class.getResourceAsStream("/tokens.schema.json")) {
      assertThat(stream)
          .as("tokens.schema.json must be on the classpath")
          .isNotNull();
      schema =
          JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(stream);
    }
  }

  @Test
  @DisplayName("Canonical FE/src/theme/tokens.json passes schema validation")
  void canonical_tokens_pass() throws Exception {
    String json = Files.readString(Paths.get("..", "FE", "src", "theme", "tokens.json"));
    Set<ValidationMessage> errors = schema.validate(MAPPER.readTree(json));
    assertThat(errors)
        .as("canonical tokens.json must validate cleanly")
        .isEmpty();
  }

  @Test
  @DisplayName("Survival packed-type missing 'label' field fails validation")
  void missing_survival_label_fails() throws Exception {
    JsonNode root = readCanonical();
    ObjectNode active = (ObjectNode) root.get("semantic").get("survival").get("ACTIVE");
    active.remove("label");

    Set<ValidationMessage> errors = schema.validate(root);

    assertThat(errors).isNotEmpty();
    assertThat(errors.toString())
        .as("error message should reference the missing 'label' field on ACTIVE")
        .containsAnyOf("label", "required");
  }

  @Test
  @DisplayName("Typography weight=500 fails validation (UX weight policy)")
  void weight_500_fails() throws Exception {
    JsonNode root = readCanonical();
    ObjectNode body = (ObjectNode) root.get("typography").get("body");
    body.put("weight", 500);

    Set<ValidationMessage> errors = schema.validate(root);

    assertThat(errors).isNotEmpty();
    assertThat(errors.toString())
        .as("error message should reference the weight enum")
        .containsAnyOf("weight", "enum", "500");
  }

  @Test
  @DisplayName("Sub-mode override key outside whitelist fails validation")
  void out_of_whitelist_subMode_key_fails() throws Exception {
    JsonNode root = readCanonical();
    ObjectNode editorial = (ObjectNode) root.get("subMode").get("editorial");
    // `color.chart.primary` is NOT in the 16-key whitelist.
    editorial.put("color.chart.primary", "oklch(50% 0.1 25)");

    Set<ValidationMessage> errors = schema.validate(root);

    assertThat(errors).isNotEmpty();
    assertThat(errors.toString())
        .as("error message should mention the rejected key or additionalProperties")
        .containsAnyOf("color.chart.primary", "additionalProperties", "additional");
  }

  @Test
  @DisplayName("Blur value above 8px fails validation (A13 glassmorphism guard)")
  void blur_over_eight_fails() throws Exception {
    JsonNode root = readCanonical();
    ObjectNode blur = (ObjectNode) root.get("blur");
    blur.put("modal", 12);

    Set<ValidationMessage> errors = schema.validate(root);

    assertThat(errors).isNotEmpty();
    assertThat(errors.toString())
        .as("error message should mention the maximum or the rejected value")
        .containsAnyOf("maximum", "12", "modal");
  }

  // --- helpers -------------------------------------------------------------

  private static JsonNode readCanonical() throws Exception {
    String json = Files.readString(Paths.get("..", "FE", "src", "theme", "tokens.json"));
    return MAPPER.readTree(json);
  }
}
