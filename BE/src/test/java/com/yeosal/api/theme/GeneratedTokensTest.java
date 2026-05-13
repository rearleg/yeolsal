package com.yeosal.api.theme;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Round-trip parity test for the design-token codegen pipeline (Story 1.5 AC3/AC14).
 *
 * <p>Reads the canonical {@code FE/src/theme/tokens.json}, then verifies that every leaf
 * token has a corresponding {@code GeneratedTokens.*} public-static field with the same
 * value. Guarantees that the Gradle {@code generateTokens} task did not silently drop or
 * skew any token between the JSON source and the Java consumer surface.
 *
 * <p>No Spring context, no Docker, no DB — runs in the default {@code ./gradlew test} cycle.
 */
class GeneratedTokensTest {

  private static final Path TOKENS_JSON =
      Paths.get("..", "FE", "src", "theme", "tokens.json");

  @Test
  @DisplayName("GeneratedTokens.VERSION + SYSTEM match tokens.json metadata")
  void version_and_system_match() throws Exception {
    JsonNode root = readTokens();
    assertThat(GeneratedTokens.VERSION).isEqualTo(root.get("version").asText());
    assertThat(GeneratedTokens.SYSTEM).isEqualTo(root.get("system").asText());
  }

  @Test
  @DisplayName("Survival packed-type constants match tokens.json (all 4 states x 4 fields)")
  void survival_packed_type_round_trip() throws Exception {
    JsonNode survival = readTokens().get("semantic").get("survival");
    for (String state : new String[] {"ACTIVE", "YELLOW", "RED", "SPECTATOR"}) {
      JsonNode node = survival.get(state);

      String expectedColor = node.get("color").get("hex").asText();
      String expectedLabel = node.get("label").asText();
      String expectedIcon = node.get("icon").asText();
      String expectedGrass = node.get("grass-treatment").asText();

      assertThat(reflectString("SURVIVAL_" + state + "_COLOR")).isEqualTo(expectedColor);
      assertThat(reflectString("SURVIVAL_" + state + "_LABEL")).isEqualTo(expectedLabel);
      assertThat(reflectString("SURVIVAL_" + state + "_ICON")).isEqualTo(expectedIcon);
      assertThat(reflectString("SURVIVAL_" + state + "_GRASS_TREATMENT")).isEqualTo(expectedGrass);
    }
  }

  @Test
  @DisplayName("Base color constants match tokens.json leaf hex values")
  void base_colors_round_trip() throws Exception {
    JsonNode color = readTokens().get("color");

    assertThat(reflectString("COLOR_BG_CANVAS"))
        .isEqualTo(color.get("bg").get("canvas").get("hex").asText());
    assertThat(reflectString("COLOR_BG_SURFACE"))
        .isEqualTo(color.get("bg").get("surface").get("hex").asText());
    assertThat(reflectString("COLOR_TEXT_PRIMARY"))
        .isEqualTo(color.get("text").get("primary").get("hex").asText());
    assertThat(reflectString("COLOR_KEY_DEFAULT"))
        .isEqualTo(color.get("key").get("default").get("hex").asText());
    assertThat(reflectString("COLOR_EMBER_DEFAULT"))
        .isEqualTo(color.get("ember").get("default").get("hex").asText());
    assertThat(reflectString("COLOR_STROKE_SUBTLE"))
        .isEqualTo(color.get("stroke").get("subtle").get("hex").asText());
  }

  @Test
  @DisplayName("Typography size + line-height + weight constants match tokens.json")
  void typography_round_trip() throws Exception {
    JsonNode body = readTokens().get("typography").get("body");
    assertThat(reflectInt("TYPOGRAPHY_BODY_SIZE")).isEqualTo(body.get("size").asInt());
    assertThat(reflectInt("TYPOGRAPHY_BODY_LINE_HEIGHT")).isEqualTo(body.get("lineHeight").asInt());
    assertThat(reflectInt("TYPOGRAPHY_BODY_WEIGHT")).isEqualTo(body.get("weight").asInt());

    JsonNode display = readTokens().get("typography").get("display");
    assertThat(reflectInt("TYPOGRAPHY_DISPLAY_WEIGHT")).isEqualTo(display.get("weight").asInt());
  }

  @Test
  @DisplayName("Space + radius + blur scalar constants match tokens.json")
  void scalars_round_trip() throws Exception {
    JsonNode root = readTokens();
    assertThat(reflectInt("SPACE_4")).isEqualTo(root.get("space").get("4").asInt());
    assertThat(reflectInt("SPACE_24")).isEqualTo(root.get("space").get("24").asInt());
    assertThat(reflectInt("SPACE_LAYOUT_PADDING"))
        .isEqualTo(root.get("space").get("layout").get("padding").asInt());

    assertThat(reflectInt("RADIUS_DEFAULT")).isEqualTo(root.get("radius").get("default").asInt());
    assertThat(reflectInt("RADIUS_PILL")).isEqualTo(root.get("radius").get("pill").asInt());

    assertThat(reflectInt("BLUR_SUBTLE")).isEqualTo(root.get("blur").get("subtle").asInt());
    assertThat(reflectInt("BLUR_MODAL")).isEqualTo(root.get("blur").get("modal").asInt());
  }

  @Test
  @DisplayName("Motion duration + easing constants match tokens.json")
  void motion_round_trip() throws Exception {
    JsonNode motion = readTokens().get("motion");
    assertThat(reflectInt("MOTION_DURATION_NORMAL")).isEqualTo(motion.get("duration").get("normal").asInt());
    assertThat(reflectInt("MOTION_DURATION_CINEMATIC"))
        .isEqualTo(motion.get("duration").get("cinematic").asInt());
    assertThat(reflectString("MOTION_EASING_STANDARD"))
        .isEqualTo(motion.get("easing").get("standard").asText());
    assertThat(reflectString("MOTION_EASING_RITUAL"))
        .isEqualTo(motion.get("easing").get("ritual").asText());
    assertThat(reflectInt("MOTION_ENTRY_DURATION")).isEqualTo(motion.get("entry").get("duration").asInt());
    assertThat(reflectString("MOTION_ENTRY_EASING")).isEqualTo(motion.get("entry").get("easing").asText());
  }

  @Test
  @DisplayName("SubMode inner classes carry override constants matching tokens.json")
  void submode_overrides_round_trip() throws Exception {
    JsonNode subMode = readTokens().get("subMode");

    int editorialWeight =
        (int) GeneratedTokens.SubMode.Editorial.class
            .getField("TYPOGRAPHY_HEADING_WEIGHT")
            .get(null);
    assertThat(editorialWeight)
        .isEqualTo(subMode.get("editorial").get("typography.heading.weight").asInt());

    int bentoRadius =
        (int) GeneratedTokens.SubMode.Bento.class.getField("RADIUS_DEFAULT").get(null);
    assertThat(bentoRadius).isEqualTo(subMode.get("bento").get("radius.default").asInt());

    int quietDuration =
        (int) GeneratedTokens.SubMode.Quiet.class.getField("MOTION_ENTRY_DURATION").get(null);
    assertThat(quietDuration)
        .isEqualTo(subMode.get("quiet").get("motion.entry.duration").asInt());

    int postcardDuration =
        (int) GeneratedTokens.SubMode.Postcard.class.getField("MOTION_ENTRY_DURATION").get(null);
    assertThat(postcardDuration)
        .isEqualTo(subMode.get("postcard").get("motion.entry.duration").asInt());

    int plateRadius =
        (int) GeneratedTokens.SubMode.Plate.class.getField("RADIUS_DEFAULT").get(null);
    assertThat(plateRadius).isEqualTo(subMode.get("plate").get("radius.default").asInt());
  }

  // --- helpers -------------------------------------------------------------

  private static JsonNode readTokens() throws Exception {
    return new ObjectMapper().readTree(Files.readString(TOKENS_JSON));
  }

  private static String reflectString(String fieldName) throws Exception {
    Field field = GeneratedTokens.class.getField(fieldName);
    return (String) field.get(null);
  }

  private static int reflectInt(String fieldName) throws Exception {
    Field field = GeneratedTokens.class.getField(fieldName);
    return (int) field.get(null);
  }
}
