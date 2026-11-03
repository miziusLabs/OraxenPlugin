# Experimental native full blocks

The `FULL` block mechanic requires Paper or Folia 1.21.4 or newer and a working native handler. Other block types keep their existing implementations. This experimental implementation does not establish compatibility with every server fork, plugin, or vanilla block interaction.

Full blocks have native server identities. Chunk files store their native keys. Outgoing packets map their appearance to note block states that the resource pack renders. Players need the pack but no client mod. Ordinary note blocks keep their server instruments, notes, and redstone behavior.

Native full blocks are custom server blocks. They do not have note block instrument, pitch, or redstone behavior. Ordinary note blocks keep those behaviors. On Bukkit, a native full block still reports `Material.NOTE_BLOCK`. `Block#getType()` cannot distinguish it from an ordinary note block. Use Oraxen's block APIs or events to identify custom blocks. Their block data is generic `BlockData`, not `NoteBlock`.

Breaking a block above or below a custom full block can make the vanilla client predict a different note block instrument. Oraxen sends the removed block and the unchanged custom neighbors together before acknowledging the break, including creative and instant breaks that bypass block damage events. Survival stack mining also keeps its existing server-controlled progress. Creative prediction happens before the server receives the click, so this correction cannot guarantee that every client frame keeps the custom appearance under latency.

An item can define its full block under `mechanics.block`.

```yaml
mechanics:
  block:
    type: FULL
    appearance:
      model: minecraft:block/stone
      rotation: none
    light: 0
```

`appearance.model` selects the placed model. Without it, Oraxen uses `pack.model`, then the generated item model named after the item ID. Stone in this example is an explicit model choice.

`appearance.rotation` accepts `none`, `horizontal`, or `axis`. Horizontal rotation faces the player. Axis rotation follows the clicked block face. Each orientation consumes a separate appearance allocation. Rotation cannot be combined with the legacy `directional` configuration.

New definitions do not need `custom-variation`. Oraxen assigns appearance states and persists them independently of configuration order. The current note block appearance mapping has 774 allocations shared by full blocks and their rotation variants. Existing allocations remain reserved when an item definition is removed.

`breaking` retains the block mechanic's ordered tool rules, hardness, drops, and durability configuration. Without a matching rule, hardness defaults to `1.0` and no drops are configured. `events` retains its click filters and actions. Full blocks use a full-cube collision shape. The model changes their appearance, but not their collision shape.

With custom block sounds enabled, missing modern `sounds` actions default individually to `minecraft:block.stone.break`, `minecraft:block.stone.place`, `minecraft:block.stone.hit`, `minecraft:block.stone.step`, and `minecraft:block.stone.fall`. Modern actions use volume and pitch `1.0` unless overridden. Oraxen preserves custom sound keys exactly. For example, `sounds.place` can be a sound key or a section with `sound`, `volume`, and `pitch`. Legacy `block_sounds` keeps its existing parsing behavior.

`light` sets native emitted light between `0` and `15`. Full blocks no longer need adjacent invisible `LIGHT` blocks for this setting.

## Example definition

The [item definition](examples/native-pillar.yml) and [model](examples/native-pillar.json) define an emerald pedestal with gold trim. The model uses Minecraft's emerald and gold textures. Horizontal rotation turns the gold front detail toward the placing player. The block emits light level `12`, uses stone placement and glass breaking sounds, and has a fallback breaking rule that drops the `native_pillar` item in survival mode. Its collision shape remains a full cube.

## Hot reload and persistence

Oraxen registers a fixed pool of 774 native block identities before worlds load. This matches the 774 appearance allocations shared by full blocks and rotation variants. `/oraxen reload all` can add definitions and rotation variants while unused allocations remain, and can update existing models, mechanics, and emitted light. Reload also refreshes placed blocks in loaded chunks for connected players. Newly loaded chunks use the current appearance and light settings. On Folia, Oraxen migrates already loaded chunks on their owning regions and applies current appearance and light settings.

Allocations stay reserved after an item definition is removed because saved chunks may still contain that identity. The 774-slot limit applies across the lifetime of the cache, including removed definitions and old rotation variants. The identity pool and appearance allocator are both limited to 774, so a normal restart cannot expand this capacity or free old allocations. A larger capacity requires a coordinated migration of both identity and appearance allocations.

Removing a definition does not remove its placed blocks. After `/oraxen reload all`, those blocks keep their physical identity and last light setting, but the active mechanic and generated model mapping are gone. Their configured drops and events no longer apply, and clients may show the default note block appearance. A removed definition therefore needs to be restored for its behavior and generated appearance to return. Regenerating the pack without definitions for placed native blocks leaves their model mappings absent.

`plugins/Oraxen/native-blocks.json` stores the identity-to-item and appearance allocations needed to interpret saved native blocks. A world opened with a different or missing mapping file can lose saved blocks when the server encounters unknown identities. Changing item IDs or regenerating allocation data can disconnect saved identities from their definitions. The mapping file and item definitions therefore need to match the world data they accompany.

Oraxen restores the recorded native registrations during plugin loading, before worlds load their chunks. A missing registration can cause the server to replace an unknown saved block with air. Per-block metadata cannot reliably recover a block after the server has discarded its saved native state. Keep Oraxen enabled while worlds can load or generate chunks. If Oraxen is disabled while the server keeps running, chunks generated during that interval lack the marker that excludes them from legacy conversion. After re-enabling Oraxen, vanilla note blocks matching configured legacy variations in those chunks may be converted as custom blocks. Chunk data has no reliable provenance to distinguish these chunks from older chunks. Stopping and restarting prevents this for future chunks, but it cannot identify or repair chunks generated during a disabled interval.

## Migration of existing worlds

The original explicit `custom_variation` or `custom-variation` values identify old note states during conversion. Automatic new allocations do not identify vanilla note blocks as custom blocks.

Oraxen scans old chunks when their migration schema or legacy-mapping fingerprint changes and converts matching legacy note states into native blocks. Chunk metadata records completion. Newly generated chunks store a marker that keeps them out of legacy classification, including when legacy mappings change during a later reload. After conversion, a vanilla note matching an old custom appearance remains a vanilla note.

Legacy ordinary blocks did not store an item ID. A vanilla note with the same state as a configured legacy custom block is indistinguishable during a scan and can also convert. Because restoring a missing legacy item definition only helps before a chunk's migration is recorded, plugin data and world data need to stay paired during an upgrade.

Each chunk also records a fingerprint of the configured legacy variation mappings. If that mapping set changes, Oraxen scans the chunk again. This allows a legacy item definition restored later to convert matching old note states. A vanilla note that uses one of those newly mapped states is also indistinguishable and can convert.

After all currently loaded chunks migrate successfully, Oraxen writes `disable-noteblock-updates: false` to Paper's configuration and restores live vanilla note updates. Later old chunks migrate when they load. A migration failure prevents the startup completion callback. Tripwire and chorus update settings remain separate and keep their existing behavior.
