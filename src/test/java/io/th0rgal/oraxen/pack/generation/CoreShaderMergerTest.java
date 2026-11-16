package io.th0rgal.oraxen.pack.generation;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class CoreShaderMergerTest {
    private static final String PATH = "assets/minecraft/shaders/core/rendertype_text.fsh";

    @Test void combinesSharedInitializationWithIndependentEffectsAndHelpers() {
        String low = """
                #version 150
                #moj_import <fog.glsl>
                uniform float Low;
                out vec4 fragColor;
                void lowEffect() { fragColor.r *= Low; }
                void main() {
                    vec4 color = vec4(1.0);
                    if (color.a < 0.1) { discard; }
                    fragColor = color;
                    lowEffect();
                }
                """;
        String high = """
                #version 150
                uniform float High;
                out vec4 fragColor;
                void highEffect() { fragColor.b *= High; }
                void main() {
                    vec4 color = vec4(1.0); // Shared initialization must run only once.
                    if (color.a < 0.1) { discard; }
                    fragColor = color;
                    highEffect();
                }
                """;
        String merged = merge(PATH, low, high);
        assertEquals(1, occurrences(merged, "#version"));
        assertEquals(1, occurrences(merged, "void main"));
        assertEquals(1, occurrences(merged, "vec4 color ="));
        assertTrue(merged.contains("uniform float Low;"));
        assertTrue(merged.contains("uniform float High;"));
        assertTrue(merged.indexOf("lowEffect();") < merged.indexOf("highEffect();"));
        assertEquals(merged, merge(PATH, merged, high));
    }

    @Test void preservesInsertionsAroundCommonStatementsAndConditionalElseBlocks() {
        String low = "#version 150\nvoid main() { vec4 c = vec4(1); if (c.a < 0.1) { discard; } else { c.a = 1; } fragColor = c; }";
        String high = "#version 150\nvoid main() { vec4 c = vec4(1); fragColor = c; if (c.r > 0.5) fragColor.r = 0; }";
        String merged = merge(PATH, low, high);
        assertTrue(merged.indexOf("else") < merged.indexOf("fragColor = c"));
        assertTrue(merged.indexOf("fragColor = c") < merged.indexOf("if (c.r"));
    }

    @Test void mergesProgramParametersWithoutDuplicatingUniforms() {
        String low = """
                {"vertex":"text","fragment":"text","samplers":[{"name":"Sampler0"}],
                 "uniforms":[{"name":"GameTime","type":"float","count":1,"values":[0]}]}
                """;
        String high = """
                {"vertex":"text","fragment":"text","samplers":[{"name":"Sampler0"},{"name":"Sampler2"}],
                 "uniforms":[{"name":"GameTime","type":"float","count":1,"values":[0]},{"name":"ScreenSize","type":"float","count":2,"values":[1,1]}]}
                """;
        var result = JsonParser.parseString(merge(PATH.replace(".fsh", ".json"), low, high)).getAsJsonObject();
        assertEquals(2, result.getAsJsonArray("samplers").size());
        assertEquals(2, result.getAsJsonArray("uniforms").size());
        assertThrows(IllegalArgumentException.class, () -> merge(PATH.replace(".fsh", ".json"), low, high.replace("[0]", "[1]")));
        assertThrows(IllegalArgumentException.class, () -> merge(PATH.replace(".fsh", ".json"), low, high.replace("\"fragment\":\"text\"", "\"fragment\":\"other\"")));
    }

    @Test void rejectsConflictingDeclarationsVersionsAndLocalInitializations() {
        String base = "#version 150\nuniform float Time;\nvoid main() { vec4 color = vec4(1); fragColor = color; }";
        assertThrows(IllegalArgumentException.class, () -> merge(PATH, base, base.replace("uniform float", "uniform vec2")));
        assertThrows(IllegalArgumentException.class, () -> merge(PATH, base, base.replace("150", "330")));
        assertThrows(IllegalArgumentException.class, () -> merge(PATH, base, base.replace("vec4(1)", "vec4(0)")));
    }

    @Test void rejectsUnrelatedOrReorderedMainBodiesAndUnsupportedPreprocessing() {
        assertThrows(IllegalArgumentException.class, () -> merge(PATH,
                "void main() { fragColor = vec4(1); }", "void main() { fragColor = vec4(0); }"));
        assertThrows(IllegalArgumentException.class, () -> merge(PATH,
                "void main() { first(); second(); }", "void main() { second(); first(); }"));
        assertThrows(IllegalArgumentException.class, () -> merge(PATH,
                "#ifdef CUSTOM\nuniform float Time;\n#endif\nvoid main() {}", "void main() {}"));
    }

    @Test void mergesIncludesAndHandlesBracesInsideComments() {
        String path = "assets/minecraft/shaders/include/custom.glsl";
        String merged = merge(path, "float low() { return 1.0; }", "float high() { return 2.0; }");
        assertTrue(merged.contains("float low()"));
        assertTrue(merged.contains("float high()"));
        assertDoesNotThrow(() -> merge(PATH, "void main() /* { */ { first(); low(); }", "void main() { first(); high(); }"));
    }

    private static String merge(String path, String low, String high) {
        return new String(CoreShaderMerger.merge(path, low.getBytes(StandardCharsets.UTF_8), high.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }

    private static int occurrences(String value, String part) {
        return (value.length() - value.replace(part, "").length()) / part.length();
    }
}
