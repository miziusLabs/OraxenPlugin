# Convert a Nexo setup

Place the contents of the Nexo data folder in `plugins/Oraxen/converter/Nexo`, then run `/oraxen convert NEXO` as an operator or a sender with `oraxen.command.convert`.

The converter writes an offline Oraxen setup to `plugins/Oraxen/converter/output`. It does not change the source, reload Oraxen, or replace the live setup. Conversion runs asynchronously and supports Folia.

Read `conversion-report.txt` in the output before importing. Back up Oraxen, merge the generated folders and settings into its data folder, then reload Oraxen and rebuild the pack. Resolve conflicts with existing item IDs and files before importing. YAML comments and formatting are not retained.

The output folder must be absent or empty. Move the previous output elsewhere before running another conversion. Invalid YAML, duplicate item IDs, conflicting asset files and symbolic links stop conversion without publishing a partial setup.

## Supported content

- Item names use the current `name` and `custom-name` keys. Explicit current names take priority over legacy names. Materials, templates, common components and custom model data stay intact. `Pack`, `Components` and `Mechanics` become their lowercase Oraxen sections. A single pack texture becomes an Oraxen texture layer. Generated Nexo item-model IDs become Oraxen IDs unless the corresponding item-model JSON is supplied in the pack.
- Recipe ingredients, results, use remainders and loot tables use `oraxen_item` instead of `nexo_item`.
- Glyph chat settings move into `chat`. Font and shadow settings move into `appearance`. Configured default fonts, permissions and shadow colors are applied to glyphs without overrides. Characters, texture paths and grid settings stay intact.
- Furniture keeps common display properties, storage, drops, placement restrictions, seats, lights and evolution settings. Barrier coordinates and ranges become Oraxen coordinate strings. Furniture appearance copies the effective pack settings, including cross-file templates, unless an explicit appearance or another furniture item is configured. Interaction hitboxes become `hitboxes`. Ghast hitboxes become `ghasts` and require Minecraft 1.21.6 or newer. Missing Ghast scales use the Nexo default of 0.25. Invalid offsets and scales outside 0.0625 through 1 are skipped and reported. Rotation and visibility options are omitted and reported. Single seats and lights become lists.
- Tridents use Oraxen's model, charging-model, thrown-model and transform appearance settings. A separate thrown item can be defined in another item file.
- Component armor uses equipment models with the correct humanoid and leggings textures. Armor slots are inferred for helmets, chestplates, leggings and boots. Existing equippable model overrides take priority.
- Sounds retain their sound references and playback settings. Jukebox registration settings and item song references use the Oraxen format. Painting variants, titles, authors and dimensions stay intact.
- Basic note blocks and string blocks become the corresponding Oraxen block types. Legacy `bigmining` settings become `mining`. Other common shared mechanics are retained. Shared global settings use the current Oraxen paths, such as `general`, `glyphs`, `custom-armor` and `pack`. Explicit current paths take priority. Only settings with matching Oraxen defaults are imported. A disabled Nexo pack server disables Oraxen pack uploading and dispatch.

For example, this item configuration

```yaml
forest_sword:
  material: DIAMOND_SWORD
  itemname: Forest sword
  Pack:
    texture: forest:item/sword
    custom_model_data: 1056
```

becomes

```yaml
forest_sword:
  material: DIAMOND_SWORD
  name: Forest sword
  pack:
    textures:
      - forest:item/sword
    custom_model_data: 1056
    generate_model: true
```

## Resource packs

Namespaced assets stay under `pack/assets`. Models, textures, sounds, fonts and other files are copied without moving them to Oraxen's legacy asset folders. Namespaces are not renamed.

`pack/external_packs` becomes `pack/uploads`. ZIPs stay zipped and directory imports keep their structure. Armor conversion also copies the configured layer textures into the vanilla equipment texture locations and writes the equipment JSON when needed.

## Limits

This is a configuration converter, not a world or inventory migration. It does not replace placed Nexo furniture or items already held by players.

Unsupported mechanics and known unsupported settings are omitted and recorded in the report. Furniture beds, state machines, placement previews and Nexo click actions need manual setup. Shulker hitboxes need manual replacements. Barrier expressions that expand beyond 4096 blocks are reported instead of expanded. Tridents without resolvable models need manual appearance configuration.

Dyeable and composite model generation, animated and reference glyph settings, and armor types without humanoid equivalents are not converted. Other unrecognized settings inside shared components or mechanics may still need review. Pack hosting and credentials are not migrated. Keep the original setup for anything the report marks for manual conversion. Nexo commands and PlaceholderAPI placeholders in text also need review. Explicit permissions retain their names.

The output uses Oraxen's existing version support. Converting a configuration does not make newer Minecraft components or equipment models available on older servers.
