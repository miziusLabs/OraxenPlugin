package io.th0rgal.oraxen.mechanics.provided.gameplay.block;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class NativeBlockRotationTest {
    @Test
    void publishedSchemaExposesAllSupportedRotationsAndTheDefault() throws Exception {
        JsonObject schema = JsonParser.parseString(Files.readString(Path.of("schemas/oraxen-schema.json"))).getAsJsonObject();
        JsonObject rotation = schema.getAsJsonObject("mechanics").getAsJsonObject("block")
                .getAsJsonObject("properties").getAsJsonObject("appearance")
                .getAsJsonObject("properties").getAsJsonObject("rotation");
        assertEquals(Arrays.stream(NativeBlockRotation.values()).map(value -> value.name().toLowerCase(Locale.ROOT)).toList(),
                rotation.getAsJsonArray("values").asList().stream().map(value -> value.getAsString()).toList());
        assertEquals(NativeBlockRotation.NONE, NativeBlockRotation.parse(rotation.get("default").getAsString()));
    }
    @Test
    void horizontalPlacementFacesThePlayerAndWrapsYaw() {
        var rotation = NativeBlockRotation.HORIZONTAL;
        assertEquals(0, rotation.select(BlockFace.UP, 0).y());
        assertEquals(90, rotation.select(BlockFace.UP, 90).y());
        assertEquals(180, rotation.select(BlockFace.UP, 180).y());
        assertEquals(270, rotation.select(BlockFace.UP, -90).y());
        assertEquals(rotation.select(BlockFace.UP, 90), rotation.select(BlockFace.UP, 450));
        assertEquals(0, rotation.select(BlockFace.UP, 44.9f).y());
        assertEquals(90, rotation.select(BlockFace.UP, 45).y());
        assertEquals(0, rotation.select(BlockFace.UP, 315).y());
    }

    @Test
    void axisPlacementUsesBothOpposingFacesOfEachAxis() {
        var rotation = NativeBlockRotation.AXIS;
        assertEquals(rotation.variants().getFirst(), rotation.select(BlockFace.UP, 90));
        assertEquals(rotation.select(BlockFace.UP, 90), rotation.select(BlockFace.DOWN, 0));
        var x = rotation.select(BlockFace.EAST, 0);
        assertEquals(x, rotation.select(BlockFace.WEST, 90));
        assertEquals(90, x.x());
        assertEquals(90, x.y());
        var z = rotation.select(BlockFace.NORTH, 0);
        assertEquals(z, rotation.select(BlockFace.SOUTH, 90));
        assertEquals(90, z.x());
        assertEquals(0, z.y());
    }

    @Test
    void variantsPreserveTheRootAllocationAndCopyTheModel() {
        JsonObject model = new JsonObject();
        model.addProperty("model", "example:block/log");
        model.addProperty("x", 270);
        model.addProperty("y", 180);
        var rotation = NativeBlockRotation.AXIS;
        assertEquals("log", rotation.variants().getFirst().identity("log"));
        assertEquals("log#rotation=axis_x", rotation.variants().get(1).identity("log"));
        JsonObject rotated = rotation.variants().get(1).apply(model);
        assertEquals("example:block/log", rotated.get("model").getAsString());
        assertEquals(90, rotated.get("x").getAsInt());
        assertEquals(90, rotated.get("y").getAsInt());
        assertEquals(270, model.get("x").getAsInt());
        assertFalse(rotation.variants().getFirst().apply(model).has("x"));
        assertFalse(rotation.variants().getFirst().apply(model).has("y"));
    }

    @Test
    void rejectsInvalidConfigurationAndInvalidPlacementInputs() {
        assertEquals(NativeBlockRotation.NONE, NativeBlockRotation.parse(null));
        assertEquals(NativeBlockRotation.HORIZONTAL, NativeBlockRotation.parse("Horizontal"));
        assertThrows(IllegalArgumentException.class, () -> NativeBlockRotation.parse("north"));
        assertThrows(IllegalArgumentException.class, () -> NativeBlockRotation.parse(""));
        assertThrows(IllegalArgumentException.class, () -> NativeBlockRotation.AXIS.select(BlockFace.SELF, 0));
        assertThrows(IllegalArgumentException.class, () -> NativeBlockRotation.HORIZONTAL.select(BlockFace.UP, Float.NaN));
        assertEquals("block", NativeBlockRotation.NONE.select(null, 0).identity("block"));
    }
}
