package io.th0rgal.oraxen.pack.generation;

import com.google.gson.JsonObject;
import io.th0rgal.oraxen.items.OraxenMeta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class TridentModelGeneratorTest {
    @Test
    void keepsVanillaChargingAndAddsCustomHandheldOverride() {
        OraxenMeta meta = mock(OraxenMeta.class);
        when(meta.isCustomTrident()).thenReturn(true);
        when(meta.getCustomModelData()).thenReturn(1042);
        when(meta.getGeneratedModelPath()).thenReturn("");
        when(meta.getModelName()).thenReturn("tridents/oraxen_trident");
        JsonObject model = TridentModelGenerator.inHandModel(List.of(meta));
        assertEquals("builtin/entity", model.get("parent").getAsString());
        assertEquals(2, model.getAsJsonArray("overrides").size());
        JsonObject vanilla = model.getAsJsonArray("overrides").get(0).getAsJsonObject();
        assertEquals(1, vanilla.getAsJsonObject("predicate").get("throwing").getAsInt());
        JsonObject custom = model.getAsJsonArray("overrides").get(1).getAsJsonObject();
        assertEquals(1042, custom.getAsJsonObject("predicate").get("custom_model_data").getAsInt());
        assertEquals("tridents/oraxen_trident", custom.get("model").getAsString());
    }
}
