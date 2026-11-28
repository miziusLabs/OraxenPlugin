package io.th0rgal.oraxen.nms.handler.java21;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.component.CustomModelData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentCodecTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void customModelDataCodecReadsYamlShapes() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("floats", List.of(1, 2.5));
        input.put("flags", List.of(true, false));
        input.put("strings", List.of("alpha", "beta"));
        input.put("colors", List.of(List.of(1.0, 0.0, 0.0)));

        var result = CustomModelData.CODEC.parse(NbtOps.INSTANCE, NMSHandler.componentTag(input));
        assertTrue(result.error().isEmpty(), () -> result.error().get().message());
        CustomModelData data = result.result().orElseThrow();
        assertEquals(List.of(1.0f, 2.5f), data.floats());
        assertEquals(List.of(true, false), data.flags());
        assertEquals(List.of("alpha", "beta"), data.strings());
        assertEquals(List.of(0xFFFF0000), data.colors());
    }

    @Test
    void yamlListsPreserveCompoundsEmptyListsAndLiteralKeys() {
        Map<String, Object> first = Map.of("alpha", 1);
        Map<String, Object> second = Map.of("enabled", true);
        Tag listTag = NMSHandler.componentTag(List.of(first, second));
        ListTag list = assertInstanceOf(ListTag.class, listTag);
        assertEquals(2, list.size());
        assertInstanceOf(CompoundTag.class, list.get(0));

        Tag emptyTag = NMSHandler.componentTag(List.of());
        assertEquals(0, assertInstanceOf(ListTag.class, emptyTag).size());

        Tag compoundTag = NMSHandler.componentTag(Map.of(
                "literal.dotted", 1,
                "minecraft:namespaced", "value"));
        CompoundTag compound = assertInstanceOf(CompoundTag.class, compoundTag);
        assertTrue(NmsAccessors.tagKeys(compound).contains("literal.dotted"));
        assertTrue(NmsAccessors.tagKeys(compound).contains("minecraft:namespaced"));
    }

    @Test
    void validatedScalarComponentCodecReadsNumbers() {
        var result = DataComponents.MAX_STACK_SIZE.codecOrThrow()
                .parse(NbtOps.INSTANCE, NMSHandler.componentTag(17));
        assertTrue(result.error().isEmpty(), () -> result.error().get().message());
        assertEquals(17, result.result().orElseThrow());
    }
}
