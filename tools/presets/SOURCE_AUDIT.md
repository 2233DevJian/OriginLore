# Vanilla Acquisition Audit

Target: Minecraft Java 1.21.1, Yarn 1.21.1+build.3, default survival features.

The item reference contains 1,333 IDs. The category table contains 1,218 distinct IDs in 12 categories; 115 IDs have explicit exclusion reasons. Player heads have no vanilla survival acquisition path. Bundles require an experimental feature in this version.

The active presets contain 41 edible items (40 native food components plus cake), 72 equipment items, 309 acquisition pools and 934 variants. Milk, potions, ordinary materials and other ordinary items have base Lore only. The four horse-armour items receive armour qualities without adding durability.

## Source Evidence

| Acquisition | Reference and special handling |
| --- | --- |
| Recipes | Vanilla `data/minecraft/recipe` results cover crafting, smelting, smoking, blasting, campfire cooking, smithing transforms and stonecutting. Smoking, blasting and campfires share `SMELTING`. |
| Loot | Vanilla `data/minecraft/loot_table` entries cover block, chest, entity, fishing, archaeology, barter and gift contexts. Trial chamber reward tables use `VAULT`. |
| Trading | Default `TradeOffers.PROFESSION_TO_LEVELED_TRADE`, including processed fish and suspicious stew factories. Experimental trade-rebalance tables are excluded. |
| Mob equipment | Equipment assigned in Java supplies the corresponding entity-drop pools. End-ship item frames supply elytra through `ENTITY_DROP`. |
| Harvesting | Sweet berries, glow berries, bottled honey and the two mooshroom stews use `HARVEST`; stew collection is not a gift. |
| Special crafting | Suspicious stew, turtle helmets and wolf armour include their actual crafting paths even where static recipe output extraction is insufficient. |
| Maintenance | Repairs, anvil names, enchantment, grindstones and trims inherit the main item. Netherite transforms use `SMITHING` with corresponding stable quality IDs. |
| Commands and unknown origins | The preset supplies only the base rule, so these paths do not acquire a fabricated survival-quality source. |

The 1,189 static recipe and loot source records in `_vanilla_reference.json` were compared with the local 1.21.1 common JAR on 2026-09-08 without differences. This check establishes reference freshness; it does not prove runtime interception of every production operation.

The default trade factories were also compared with every quality-bearing preset item. The audit corrected two entries: the armorer sells shields, while the toolsmith sells stone and diamond hoes but no iron hoes. Both languages now include shield trading and exclude iron-hoe trading. The removed iron-hoe pool matched the existing generated draft exactly; no manual text or values were replaced.

## Regression Boundaries

`PresetQualityTest` checks the survival set, base Lore, bilingual gameplay identity, applicable food and equipment pools, important source distinctions and approved copy. `_generator.test.mjs` verifies bilingual source data, independent manual text and numeric edits, multiple rules of one source type, and repeatable regeneration in a temporary directory. `PresetPipelineTest` compares the source shards with the packaged resources through the actual configuration model.

All source pools still use the existing draft weights and values except for the four explicitly approved iron-sword outcomes. Source coverage is not approval of those other balance choices; see `DRAFT_PARAMETERS.md`. Runtime transaction, transfer, hit and consumption behavior requires the separate GameTest and vanilla-client acceptance runs.
