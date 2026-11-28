package io.th0rgal.oraxen.utils.customarmor;

import io.th0rgal.oraxen.utils.SemanticServerVersion;
import org.apache.commons.lang3.EnumUtils;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public enum EquipmentLayer {
    HUMANOID("humanoid", "1.21.2", null, List.of()),
    HUMANOID_LEGGINGS("humanoid_leggings", "1.21.2", null, List.of()),
    HUMANOID_BABY("humanoid_baby", "26.1", null, List.of()),
    WINGS("wings", "1.21.2", "CHEST", List.of()),
    WOLF_BODY("wolf_body", "1.21.2", "BODY", List.of("WOLF")),
    HORSE_BODY("horse_body", "1.21.2", "BODY", List.of("HORSE", "ZOMBIE_HORSE")),
    LLAMA_BODY("llama_body", "1.21.2", "BODY", List.of("LLAMA", "TRADER_LLAMA")),
    PIG_SADDLE("pig_saddle", "1.21.5", "SADDLE", List.of("PIG")),
    STRIDER_SADDLE("strider_saddle", "1.21.5", "SADDLE", List.of("STRIDER")),
    CAMEL_SADDLE("camel_saddle", "1.21.5", "SADDLE", List.of("CAMEL")),
    HORSE_SADDLE("horse_saddle", "1.21.5", "SADDLE", List.of("HORSE")),
    DONKEY_SADDLE("donkey_saddle", "1.21.5", "SADDLE", List.of("DONKEY")),
    MULE_SADDLE("mule_saddle", "1.21.5", "SADDLE", List.of("MULE")),
    SKELETON_HORSE_SADDLE("skeleton_horse_saddle", "1.21.5", "SADDLE", List.of("SKELETON_HORSE")),
    ZOMBIE_HORSE_SADDLE("zombie_horse_saddle", "1.21.5", "SADDLE", List.of("ZOMBIE_HORSE")),
    HAPPY_GHAST_BODY("happy_ghast_body", "1.21.6", "BODY", List.of("HAPPY_GHAST")),
    NAUTILUS_BODY("nautilus_body", "1.21.11", "BODY", List.of("NAUTILUS", "ZOMBIE_NAUTILUS")),
    NAUTILUS_SADDLE("nautilus_saddle", "1.21.11", "SADDLE", List.of("NAUTILUS", "ZOMBIE_NAUTILUS")),
    CAMEL_HUSK_SADDLE("camel_husk_saddle", "1.21.11", "SADDLE", List.of("CAMEL_HUSK"));

    private final String configKey;
    private final SemanticServerVersion minimumVersion;
    private final String impliedSlotName;
    private final List<String> allowedEntityTypeNames;

    EquipmentLayer(String configKey, String minimumVersion, @Nullable String impliedSlotName,
            List<String> allowedEntityTypeNames) {
        this.configKey = configKey;
        this.minimumVersion = SemanticServerVersion.parse(minimumVersion);
        this.impliedSlotName = impliedSlotName;
        this.allowedEntityTypeNames = allowedEntityTypeNames;
    }

    public String configKey() {
        return configKey;
    }

    public String minimumVersion() {
        return minimumVersion.toString();
    }

    @Nullable
    public String impliedSlotName() {
        return impliedSlotName;
    }

    public List<String> allowedEntityTypeNames() {
        return allowedEntityTypeNames;
    }

    public boolean isSupported(SemanticServerVersion serverVersion) {
        return serverVersion.toCanonicalNamespace().isAtLeast(minimumVersion);
    }

    @Nullable
    public EquipmentSlot impliedSlot() {
        return impliedSlotName == null ? null : EnumUtils.getEnum(EquipmentSlot.class, impliedSlotName);
    }

    public List<EntityType> allowedEntityTypes() {
        return allowedEntityTypeNames.stream()
                .map(name -> EnumUtils.getEnum(EntityType.class, name))
                .filter(Objects::nonNull)
                .toList();
    }

    @Nullable
    public static EquipmentLayer fromConfigKey(String key) {
        if (key == null)
            return null;
        String normalized = key.toLowerCase(Locale.ROOT);
        for (EquipmentLayer layer : values())
            if (layer.configKey.equals(normalized))
                return layer;
        return null;
    }
}
