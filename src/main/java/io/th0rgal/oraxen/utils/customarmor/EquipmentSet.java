package io.th0rgal.oraxen.utils.customarmor;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record EquipmentSet(String id, Map<EquipmentLayer, String> layers, List<String> itemIds,
        Map<EquipmentLayer, String> layerItems) {

    public EquipmentSet {
        layers = Collections.unmodifiableMap(new EnumMap<>(layers));
        itemIds = List.copyOf(itemIds);
        layerItems = Collections.unmodifiableMap(new EnumMap<>(layerItems));
    }
}
