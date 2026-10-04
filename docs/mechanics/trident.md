# Trident

Requires Paper 1.21.4 or newer, including Folia. Enable `trident` in `mechanics.yml`.

```yaml
trident:
  itemname: '<green>Custom trident'
  material: TRIDENT
  mechanics:
    trident:
      sounds:
        throw: trident.throw.sound
        hit: trident.hit.sound
        hit-ground: trident.hit.sound
        return: trident.return.sound
      appearance:
        model: tridents/oraxen_trident
        thrown-model: tridents/oraxen_trident_thrown
        transform: NONE
```

`appearance.model` and `appearance.thrown-model` are required resource pack model paths without the `.json` extension. Paths without a namespace use `minecraft`. For example, `tridents/oraxen_trident` uses `pack/assets/minecraft/models/tridents/oraxen_trident.json`. A path such as `oraxen:tridents/oraxen_trident` uses the `oraxen` namespace instead. Models can reference textures in any namespace.

The mechanic generates the item model definitions for holding, charging and throwing. The thrown model uses the configured item display transform, which defaults to `NONE`. Orient its shaft along the model's Y axis with its tip toward positive Y.

Sounds accept vanilla or custom resource pack sound identifiers. Omit `hit-ground` to use `hit`, and omit `return` to use `throw`. Omitting `throw` or `hit` uses the corresponding vanilla sound. Custom sounds must also be defined in the resource pack, for example through `sounds.yml`.

The server keeps a normal trident projectile. Damage, durability, pickup, Loyalty, Channeling and Riptide use vanilla behavior. Its appearance changes through packets without spawning another world entity or running a per-projectile rendering task. Custom tridents send movement updates every tick, with one tick of display interpolation, so the model follows flight, impacts and Loyalty returns.

## Aligning the thrown model

Minecraft checks the native projectile's path against blocks. The custom model does not change that collision path. If the model's tip extends far ahead of the projectile position, the tip can enter a wall before the projectile stops.

To reduce this, move the thrown model backward along its shaft so its tip sits near the projectile position. This keeps the model's size and lets the shaft extend behind the collision point. Keep the tip pointing toward positive Y.

Use a separate model for flight so the adjustment does not affect the handheld appearance. Edit the file referenced by `appearance.thrown-model`, rather than the file referenced by `appearance.model`.

### Applying a model offset

Set `appearance.transform` to `FIXED` in the item's trident mechanic. For example, an item with separate Ender trident models would use these settings.

```yaml
appearance:
  model: oraxen:tridents/ender_trident
  thrown-model: oraxen:tridents/ender_trident_throwing
  transform: FIXED
```

In the thrown model JSON, replace its `display.fixed` entry with the following transform. Keep the model's elements, textures and other display entries.

```json
{
  "rotation": [0, 0, 0],
  "translation": [0, -21, 0],
  "scale": [1, 1, 1]
}
```

This object is the value of `display.fixed`. `FIXED` selects that entry for the flight model. With the default `NONE` setting, changing `display.fixed` has no effect on the thrown trident.

The example offset is specific to a model whose tip is at Y=29. Item model coordinates are centered at `[8, 8, 8]`, and 16 model units equal one block at scale 1. Moving the model by `8 - 29 = -21` units brings that tip to the projectile position, shifting the model backward by about 1.31 blocks.

For another model at scale 1, start with a Y translation of `8 - tipY`. Use the frontmost visible point after any element rotations when determining `tipY`. A negative Y translation moves the model backward along its shaft. Leave the X and Z translations at zero if the shaft is already centered on X=8 and Z=8. Resetting the fixed rotation to zero and its scale to one avoids inheriting an item-frame pose or changing the model's size.

After editing, reload the item configuration and regenerate the resource pack through `/oraxen reload all`. Load the updated pack on the client and throw a new trident. Test straight throws into walls, shallow impacts, floors and ceilings. Existing thrown tridents can retain their cached appearance settings until they are removed and recreated.

Adjust the offset in small steps to get the desired amount of tip embedding. This is a visual alignment fix. Wide prongs or the shaft can still intersect nearby blocks at shallow angles because Minecraft does not check the entire custom model for collisions.
