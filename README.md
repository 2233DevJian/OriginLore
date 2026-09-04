**English** | [简体中文](README.zh-CN.md)

# OriginLore

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-6f58a2)
![Fabric Loader](https://img.shields.io/badge/Fabric_Loader-%E2%89%A50.19.2-dbd0b4)
![Java](https://img.shields.io/badge/Java-21-ed8b00)
![Install](https://img.shields.io/badge/Install-server--side-3d8c40)
![License](https://img.shields.io/badge/License-MIT-blue)
![Build](https://github.com/2233DevJian/OriginLore/actions/workflows/build.yml/badge.svg)

OriginLore decides what an item *is* based on where it *came from*. Chest loot, block drops, entity drops, fishing, archaeology, piglin bartering, trial vaults, crafting, smelting, stonecutting, smithing, the anvil and `/give` are each recognised as a distinct source, and every source can carry its own name, lore, rarity, food effects, enchantments, attribute modifiers and tool rules — including weighted random variants rolled the first time an item is created.

All rule resolution, variant rolls and item refreshing happen on the logical server and are written into **vanilla data components**. Players install nothing: a vanilla client reads and uses the result exactly as if a datapack or a command had written it. The optional client module exists only to give operators an in-game admin GUI and registry-aware tab completion.

## Highlights

- **Three-tier rule model** — base rule → most specific matching source rule → the variant rolled on first creation. Unfilled fields inherit; they never blank out a value the item already had.
- **14 recognised sources** with optional loot table / recipe ID matching, plus a safe `UNKNOWN` bucket for legacy items and mod-added creation paths. OriginLore never guesses a source.
- **Stable random variants** — rolled once, then persisted. Restarts, chunk reloads, splitting, stacking and reconnection never re-roll, and different variants never stack together.
- **Reversible** — delete a field from the config and the item gets back the exact component patch it had before OriginLore first touched it, instead of being left with an empty value.
- **Live refresh** — saving the config updates online inventories, ender chests, equipment, open containers, loaded block inventories and item entities incrementally. Unloaded chunks and offline saves are never scanned.
- **Server-authoritative GUI** — the config is only ever sent to operators at permission level 2 or above, and every edit is re-validated server-side before an atomic write. Transactional copies, version-conflict detection and disconnect protection are built in.
- **Held-item quick edit** — press `O` with an item in your main hand to jump straight to that item's rule editor. An item with no rule yet gets a new one with its ID prefilled and locked.
- **Advanced component editor** — arbitrary `ComponentType` JSON, validated against the live registry and that component's own persistence codec plus the full `ItemStack` component validator. Invalid components are rejected, never written.
- **Non-invasive** — never modifies another mod's JAR, config or resources. Third-party data under `minecraft:custom_data` is preserved; OriginLore keeps its own bookkeeping separately under `minecraft:custom_data.originlore`.

## Installation

| Where | What to install |
| --- | --- |
| Server / modpack server | OriginLore + Fabric API + Fabric Loader 0.19.2 or newer |
| OP client that needs the admin GUI | the same OriginLore JAR + Fabric API |
| Regular player clients | nothing |

Target environment is fixed at Minecraft 1.21.1 and Java 21.

Download `originlore-2.1.0.jar` from the [Releases page](https://github.com/2233DevJian/OriginLore/releases) and drop it into the instance's `mods` folder; building from source produces the same artifact in `build/libs`. Deploy that JAR only — never the `-dev` or `-sources` variants. On first launch the mod creates:

```text
config/originlore/item_components.json
```

## Quick start

Join a world or server and press `O` to open the admin GUI (rebindable under Options → Controls). Holding an item in your main hand makes `O` jump straight to that item's rule editor instead — creating a new rule with the ID prefilled and locked if none exists yet — while an empty hand opens the item list. The server only sends the config to players at permission level 2+; without permission, without the mod on the server, with an incompatible protocol or while disconnected, editing is disabled rather than silently failing.

> **GUI language:** the admin interface is currently Chinese-only. Source type names are the exception — they come from the game's language file, so they appear in English under `en_us`. The [English user guide](docs/en/USER_GUIDE.md) quotes every on-screen label as the Chinese string with an English gloss, so you can match what you see.

The GUI covers:

- Base / source / variant rule editing in one tree.
- Tab completion for items, loot tables, recipes, components, enchantments, attributes, status effects and block IDs, driven by a server-supplied registry catalog.
- `Tab`, arrow keys, `Enter`, `Escape` and mouse control of the completion popup.
- Colour, bold and italic for names and lore, plus full vanilla Text JSON.
- Food, enchantment, attribute modifier, tool rule and advanced data component editors.
- Transactional copies, server-side validation errors and config version conflict protection.

Saving always goes to the logical server, including the integrated server of a single-player world. The server validates and atomically writes the file before broadcasting the new revision and refreshing items.

`/originlore reload` can be run by any command source at permission level 2+ to force a reload from disk. A corrupt or invalid file never replaces the last known-good config.

## Rule model

The config file uses schema v3:

```json
{
  "schemaVersion": 3,
  "revision": 0,
  "items": {
    "minecraft:sweet_berries": {
      "base": {
        "lore": ["Sweet and tangy berries. Not bad!"]
      },
      "sources": [
        {
          "type": "CHEST_LOOT",
          "lootTableId": "minecraft:chests/simple_dungeon",
          "rule": {},
          "variants": [
            {"id": "fresh", "weight": 6, "rule": {}},
            {"id": "stored", "weight": 3, "rule": {"lore": ["Berries that have been stored for a long time."]}}
          ]
        }
      ]
    }
  }
}
```

For each item, OriginLore picks the single most specific source rule, then merges in this order:

```text
base rule -> source rule -> variant rule rolled on first creation
```

Fields you leave out keep the item's own value. When a field is removed from the config, OriginLore restores the original component patch it recorded before first taking over that field. If a specific loot table or recipe ID cannot be obtained at runtime, only source rules without an ID constraint are eligible — the item is never reassigned to a different source.

Supported source types:

```text
BLOCK_DROP  CHEST_LOOT  ENTITY_DROP  FISHING  ARCHAEOLOGY
BARTER      GIFT        VAULT        COMMAND  CRAFTING
SMELTING    CUTTING     SMITHING     UNKNOWN
```

Legacy items and mod-added creation paths whose origin cannot be traced reliably land in `UNKNOWN`. You can configure `UNKNOWN` like any other source to adopt them in bulk.

## Field reference

| Field | Meaning |
| --- | --- |
| `customName` / `customNameJson` | plain-text name, or vanilla Text JSON |
| `lore` / `loreJson` | plain-text lore lines, or Text JSON lines |
| `rarityName` | `common`, `uncommon`, `rare`, `epic` |
| `maxStackSize` / `maxStackSizeRange` | fixed, or randomised once on first creation |
| `maxDamage` / `maxDamageRange` | fixed, or randomised once on first creation |
| `currentDamage` | current durability loss |
| `fireResistant` | whether the fire-resistant component is present |
| `enchantments` / `storedEnchantments` | enchantment ID → level map |
| `food` | nutrition, saturation, eat time, always-edible and chance-based effects |
| `attributes` | attribute, modifier ID, amount, operation and slot |
| `attackDamageRange` | extra main-hand attack damage, rolled once and persisted |
| `tool` | block set, mining speed, correct drops and per-block damage |
| `customModelData` | custom model data |
| `hideTooltip` / `hideAdditionalTooltip` | tooltip-hiding components |
| `setComponents` / `removeComponents` | advanced component JSON, set or remove |

Advanced component values are validated by the persistence codec of the corresponding Minecraft `ComponentType` and by the full `ItemStack` component validator. Components that do not exist, are not persistable, or cannot be resolved against the current registry are rejected and never reach the config or the item.

See [config_example.json](config_example.json) for a full example, and [modpack_config_example.json](modpack_config_example.json) for modded items in a dedicated modpack.

## Source identity and hot refresh

Adopted items store their source, concrete ID, variant ID, config revision, rolled random values and the original component patch of every managed field under `minecraft:custom_data.originlore`.

- Variants are rolled by weight only on first adoption and then survive restarts, duplication, splitting and chunk reloads unchanged.
- Different variants carry different identities, so they cannot stack by mistake; identical variants stack normally even when one came from a furnace and the other from a smoker, as long as the resulting components match.
- Furnaces, smokers and blast furnaces pause after finishing each OriginLore item and resume only once the output is taken, so the next item is rolled against the newest weights and a single output slot never mixes variants.
- An item that already has a definite source keeps it when it passes through the `UNKNOWN` fallback; it is never downgraded.
- After a config save, online player inventories, ender chests, equipment, open containers, loaded block inventories and item entities are refreshed incrementally.
- Player login, chunk load, entity load and inventory change trigger lazy refresh.
- Unloaded chunks are never scanned and offline saves are never rewritten directly.

## Compatibility

Standard `ItemStack` construction, loot tables, the vanilla recipe system and persistent data components work automatically. Behaviour that another mod hardcodes in Java without expressing it through data components needs dedicated integration. When a creation path is unrecognised, the item can still be adopted through an `UNKNOWN` rule.

OriginLore never modifies another mod's JAR, config or resources. For coexistence details, the automatically recognised creation paths and known limitations, see the [compatibility notes](docs/en/COMPATIBILITY.md).

## Documentation

| Topic | English | 中文 |
| --- | --- | --- |
| Overview | this file | [README.zh-CN.md](README.zh-CN.md) |
| User guide — full GUI walkthrough | [docs/en/USER_GUIDE.md](docs/en/USER_GUIDE.md) | [使用手册.md](使用手册.md) |
| Compatibility, tests and limitations | [docs/en/COMPATIBILITY.md](docs/en/COMPATIBILITY.md) | [COMPATIBILITY_TESTS.md](COMPATIBILITY_TESTS.md) |
| Changelog | [docs/en/CHANGELOG.md](docs/en/CHANGELOG.md) | [更新日志.md](更新日志.md) |

Read the user guide before using the GUI for the first time. It walks through basic lore, source rules, random variants, food effects, differentiating iron swords by origin, and the advanced component editor.

## Building from source

```powershell
.\gradlew.bat test runGametest build --console=plain
```

The default target is Fabric Loader 0.19.2. For a quick regression run against 0.19.3, override it explicitly:

```powershell
.\gradlew.bat test runGametest '-Ploader_version=0.19.3' --console=plain
```

Quote Gradle property arguments that contain dots when using PowerShell. The production JAR lands in `build/libs`.

## Implementation layout

- `ItemComponentConfig` — schema, migration, transactional snapshots, atomic saves and revisions.
- `ItemComponentManager` — original-value restoration, rule merging, stable random values, codec validation and component transaction commit.
- `SourceContext` and the source mixins — loot, commands, crafting, smelting, stonecutting, smithing, player inventory and the generic fallback.
- `RefreshService` — bounded incremental refresh of online and loaded objects.
- `OriginLoreNetworking` — OP permission checks, compressed fragmentation, size limits, conflict detection and revision broadcast.
- `ClientConfigSession` and the screens — read-only snapshot cache and the transactional admin GUI.

## License

MIT.
