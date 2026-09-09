# Vanilla Preset Sources

The preset universe is Minecraft Java 1.21.1 with the default survival feature set: 1,218 items in 12 categories. `categories.json` and `exclusions.json` partition the committed item ID reference. Player heads are unavailable in vanilla survival, and bundles require an experimental feature in this version.

Acquisition references, Java-only paths and the latest audit corrections are recorded in [SOURCE_AUDIT.md](SOURCE_AUDIT.md).

Every item has a base containing only `loreJson`. Quality names use `itemName`, so they do not behave as an anvil rename. Ordinary blocks and materials do not receive gameplay overrides. Forty edible items plus cake, 68 durable items, and four horse armor items receive source-specific quality pools.

## Content Generation

From the repository root, run:

```powershell
node tools/presets/_generate_all.mjs
node --test tools/presets/_generator.test.mjs
./gradlew writePresets
./gradlew build
./gradlew runGametest
```

The Node command completes the bilingual source shards. Gradle parses those shards through the real configuration model and writes the bundled resources. Run `writePresets` before `build` so resource processing packages the newly generated data. All listed checks run offline; no game client is started. `_apply_stat_nerf.mjs` and `_retune_variants.mjs` are compatibility aliases for the full generator; neither writes gameplay changes into base rules.

The family generators use vanilla language dumps at `build/preset-tools/lang_zh_cn.json` and `lang_en_us.json`. These are local Mojang assets and are not committed. Hand-authored lore remains in the bilingual shards, and `_lore_remaining.mjs` supplies the additional category content. `_quality_rules.mjs` contains the existing shared numeric draft and the quality descriptions. The approved iron-sword outcomes and stale-bread description are fixed. Other numeric values and names remain draft defaults for the administrator to decide; see `DRAFT_PARAMETERS.md`.

Existing source pools retain their order, selectors, variants and manual text and numeric edits during regeneration. Multiple rules of the same source type are preserved separately; only an acquisition type missing from the item receives a generated pool. The generator test exercises this in a temporary copy for both languages. `PresetQualityTest` locks the approved iron-sword names and iron-sword and stale-bread descriptions verbatim in both languages.

Existing migrated source pools are retained verbatim, including text, weights and values; the generator only adds absent real acquisition pools. Existing base lore is retained. Generated building and colour families store their last generated lore in `_generated_copy.json`: a subsequent generation updates a field only when its current value still equals that stored value. Manual changes to text or its formatting therefore survive repeated generation. `_generator.test.mjs` proves this in an isolated temporary copy with independent base, generated-family, quality-name, quality-lore, weight and durability edits.

`_legacy_authored.json` is the complete original source archive from before the migration. It preserves all old authored names, descriptions, variant IDs and values for review. Conflicting old draft rules, such as named base rules, quality-bearing ordinary materials, invented chest sources for special equipment and the superseded iron-sword outcomes, are preserved there and replaced in the active preset according to the approved requirements. The archive is outside both language directories and is never bundled. This source migration is separate from runtime configuration import: existing server configuration is preserved until the administrator explicitly imports a preset.

## Reference Data

`_vanilla_reference.json` records item stats and acquisition sources derived from the 1.21.1 runtime. To update it, supply the matching common Minecraft jar and the item-stat dump:

```powershell
./tools/presets/_extract_reference.ps1 -MinecraftJar <common-jar> -Stats build/gametest/item_stats_1_21_1.txt
```

The extractor reads recipe and loot JSON from the jar. Trial vault reward tables are classified as `VAULT` even though their stored loot-context type is `chest`. Java-only mechanics such as villager trades, mob equipment, harvested honey, and filled bowls are documented in `_quality_rules.mjs`. Direct berry picking, honey collection and mooshroom bowls use `HARVEST`; actual loot-table gifts continue to use `GIFT`.

Food saturation is the total saturation restored, not the food-builder multiplier. Cake values apply to one of its seven bites. Intrinsic food effects remain intact through `appendEffects`; quality adds only its own effects. Armor bonuses use `appendAttributes`, and wolf armor varies only in durability.

`PresetQualityTest` checks coverage, base-only lore, unique base descriptions in each language, bilingual numeric equality, real special-equipment and harvest sources, food rules, and the approved iron-sword outcomes. A smithing pool includes all four equipment quality IDs so an upgrade can retain its previous quality identity. Family descriptions combine material or colour with the actual shape; they do not reuse an identical base description across different IDs.
