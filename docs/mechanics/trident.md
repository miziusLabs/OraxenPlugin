# Trident

Enable `trident.enabled` in `mechanics.yml` (enabled by default). Add the mechanic to an item:

```yaml
trident:
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

The mechanic sets the item's material to `TRIDENT`, so `material` may be omitted.
`hit-ground` defaults to `hit`; `return` defaults to `throw`. Return audio plays when
the trident is picked up, including a Loyalty return. These sounds are played in
addition to Minecraft's native audio. Omitted sounds add no audio.

Custom geometry requires Minecraft 1.21.4 or newer. Older servers retain vanilla
models and support the sound settings. The held model is also used while charging.
The thrown model is optional; omitting it keeps the vanilla projectile appearance.

Model paths refer to resource-pack model JSON files, not item IDs. Unnamespaced
paths use `minecraft`; for example, `tridents/oraxen_trident` refers to
`assets/minecraft/models/tridents/oraxen_trident.json`. Namespaced paths such as
`example:tridents/held` are supported. Supply the models, textures, and sound
definitions in your pack, then regenerate it. Item model definitions are generated
automatically, including when the item has no `pack` section.

`appearance.transform` selects the thrown model's display transform. It defaults
to `NONE`. Supported values are `NONE`, `THIRDPERSON_LEFTHAND`,
`THIRDPERSON_RIGHTHAND`, `FIRSTPERSON_LEFTHAND`, `FIRSTPERSON_RIGHTHAND`, `HEAD`,
`GUI`, `GROUND`, and `FIXED`. Values are case-insensitive; unrecognized values
fall back to `NONE`.

The thrown display uses the selected transform and follows the projectile's
yaw and pitch. Orient the model along positive Z. The original trident retains its
item data, damage, durability, pickup rules, and vanilla enchantment behavior.
