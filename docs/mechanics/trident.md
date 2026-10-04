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
