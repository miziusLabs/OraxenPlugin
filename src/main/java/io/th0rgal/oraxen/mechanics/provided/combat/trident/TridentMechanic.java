package io.th0rgal.oraxen.mechanics.provided.combat.trident;

import io.th0rgal.oraxen.api.OraxenItems;
import io.th0rgal.oraxen.configs.AppearanceMode;
import io.th0rgal.oraxen.items.ItemBuilder;
import io.th0rgal.oraxen.items.ModelData;
import io.th0rgal.oraxen.items.OraxenMeta;
import io.th0rgal.oraxen.mechanics.Mechanic;
import io.th0rgal.oraxen.mechanics.MechanicFactory;
import io.th0rgal.oraxen.utils.VersionUtil;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

public class TridentMechanic extends Mechanic {
    private final String throwSound;
    private final String hitSound;
    private final String hitGroundSound;
    private final String returnSound;
    private final String model;
    private final String thrownModel;
    private final ItemDisplayTransform transform;
    private ItemBuilder displayItem;
    private ItemStack displayStack;

    public TridentMechanic(MechanicFactory factory, ConfigurationSection section) {
        super(factory, section, item -> item.setType(Material.TRIDENT));
        throwSound = section.getString("sounds.throw", "minecraft:item.trident.throw");
        hitSound = section.getString("sounds.hit", "minecraft:item.trident.hit");
        hitGroundSound = section.getString("sounds.hit-ground", hitSound);
        returnSound = section.getString("sounds.return", throwSound);
        model = section.getString("appearance.model");
        thrownModel = section.getString("appearance.thrown-model", model);
        try {
            transform = ItemDisplayTransform.valueOf(section.getString("appearance.transform", "NONE")
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid trident appearance.transform for " + getItemID(), exception);
        }
    }

    public String getThrowSound() { return throwSound; }
    public String getHitSound() { return hitSound; }
    public String getHitGroundSound() { return hitGroundSound; }
    public String getReturnSound() { return returnSound; }
    public String getModel() { return model; }
    public String getThrownModel() { return thrownModel; }
    public ItemDisplayTransform getTransform() { return transform; }
    public String getDisplayItemId() { return "trident/" + getItemID() + "/thrown"; }
    public ItemBuilder getDisplayItem() { return displayItem; }
    public ItemStack createDisplayStack() { return displayStack.clone(); }

    void initializeDisplayItem() {
        if (thrownModel == null || thrownModel.isBlank()) return;
        ConfigurationSection pack = new YamlConfiguration();
        pack.set("model", thrownModel);
        pack.set("generate_model", false);
        OraxenMeta meta = new OraxenMeta();
        meta.setPackInfos(pack);
        int modelData = ModelData.generateId(thrownModel, Material.PAPER);
        meta.setCustomModelData(modelData);
        displayItem = new ItemBuilder(Material.PAPER).setOraxenMeta(meta)
                .setCustomModelData(modelData)
                .setCustomTag(OraxenItems.ITEM_ID, PersistentDataType.STRING, getDisplayItemId());
        if (VersionUtil.atOrAbove("1.21.4")) {
            if (AppearanceMode.isItemPropertiesEnabled())
                displayItem.setItemModel(new NamespacedKey("oraxen", getDisplayItemId()));
            if (AppearanceMode.isModelDataIdsEnabled())
                displayItem.setCustomModelDataStrings(List.of("oraxen:" + getDisplayItemId()));
        }
        displayStack = displayItem.build();
    }
}
