package io.th0rgal.oraxen.pack.generation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.th0rgal.oraxen.items.OraxenMeta;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class TridentModelGenerator {
    private TridentModelGenerator() {}

    public static JsonObject inHandModel(List<OraxenMeta> items) {
        try (var input = TridentModelGenerator.class.getResourceAsStream("/models/trident_in_hand.json")) {
            if (input == null) throw new IllegalStateException("Missing trident model template");
            JsonObject model = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray overrides = model.getAsJsonArray("overrides");
            for (OraxenMeta meta : items) {
                if (!meta.isCustomTrident() || meta.getCustomModelData() == null) continue;
                JsonObject predicate = new JsonObject();
                predicate.addProperty("custom_model_data", meta.getCustomModelData());
                JsonObject override = new JsonObject();
                override.add("predicate", predicate);
                override.addProperty("model", meta.getGeneratedModelPath() + meta.getModelName());
                overrides.add(override);
            }
            return model;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read trident model template", exception);
        }
    }
}
