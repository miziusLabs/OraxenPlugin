package io.th0rgal.oraxen.utils.customarmor;

import io.th0rgal.oraxen.api.OraxenItems;
import io.th0rgal.oraxen.items.ItemBuilder;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

public final class EquipmentSets {

    private EquipmentSets() {
    }

    public static Map<String, ItemEquipment> configuredEquipment() {
        Map<String, ItemEquipment> equipmentByItem = new LinkedHashMap<>();
        for (Map.Entry<String, ItemBuilder> entry : OraxenItems.getEntries())
            if (entry.getValue().hasEquipment())
                equipmentByItem.put(entry.getKey(), entry.getValue().getEquipment());
        return equipmentByItem;
    }

    public static Map<String, EquipmentSet> collect(Map<String, ItemEquipment> equipmentByItem,
            Consumer<String> warningConsumer) {
        Map<String, ItemEquipment> sortedItems = new TreeMap<>(equipmentByItem);
        Map<String, SetBuilder> builders = new LinkedHashMap<>();

        for (Map.Entry<String, ItemEquipment> itemEntry : sortedItems.entrySet()) {
            String itemId = itemEntry.getKey();
            ItemEquipment equipment = itemEntry.getValue();
            SetBuilder setBuilder = builders.computeIfAbsent(equipment.id(), SetBuilder::new);
            setBuilder.itemIds.add(itemId);

            for (Map.Entry<EquipmentLayer, String> layerEntry : equipment.layers().entrySet()) {
                EquipmentLayer layer = layerEntry.getKey();
                String texture = layerEntry.getValue();
                String existingTexture = setBuilder.layers.get(layer);
                if (existingTexture == null) {
                    setBuilder.layers.put(layer, texture);
                    setBuilder.layerItems.put(layer, itemId);
                } else if (!existingTexture.equals(texture)) {
                    warningConsumer.accept("Conflicting equipment layer \"" + layer.configKey() + "\" for equipment id \""
                            + equipment.id() + "\" on items \"" + setBuilder.layerItems.get(layer) + "\" and \"" + itemId
                            + "\"; keeping the texture from \"" + setBuilder.layerItems.get(layer) + "\".");
                }
            }
        }

        Map<String, EquipmentSet> sets = new LinkedHashMap<>();
        for (Map.Entry<String, SetBuilder> entry : builders.entrySet()) {
            SetBuilder setBuilder = entry.getValue();
            if (setBuilder.layers.isEmpty())
                warningConsumer.accept("Equipment id \"" + entry.getKey() + "\" has no layers defined on any item.");
            sets.put(entry.getKey(), new EquipmentSet(entry.getKey(), setBuilder.layers, setBuilder.itemIds,
                    setBuilder.layerItems));
        }
        return sets;
    }

    private static final class SetBuilder {
        private final String id;
        private final Map<EquipmentLayer, String> layers = new EnumMap<>(EquipmentLayer.class);
        private final Map<EquipmentLayer, String> layerItems = new EnumMap<>(EquipmentLayer.class);
        private final List<String> itemIds = new ArrayList<>();

        private SetBuilder(String id) {
            this.id = id;
        }
    }
}
