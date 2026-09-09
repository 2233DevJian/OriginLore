# Preset Parameters Awaiting Administrator Decisions

The active defaults complete the survival coverage and source integration. Except for the four iron-sword outcomes below, names, probabilities and gameplay values come from the existing draft in `_quality_rules.mjs`; they are not a newly approved balance. Both languages use exactly the same values and stable variant IDs. Administrators can revise them in the GUI or source shards.

| Approved iron sword | Source | Weight | Extra attack damage | Maximum durability |
| --- | --- | ---: | --- | --- |
| Hand-forged / 手工打制铁剑 | CRAFTING | 100 | -2 to -1 | 150 to 200 |
| Notched / 剑刃有缺口的铁剑 | CHEST_LOOT | 70 | 0 | 75 |
| Smith-forged / 铁匠打造的铁剑 | CHEST_LOOT | 25 | 0 | 250 |
| Fine Steel / 精钢铁剑 | CHEST_LOOT | 5 | +1 | 275 |

The vanilla unenchanted total is 6 attack damage, giving totals of 4 to 5, 6, 6 and 7. `attackDamageRange` continues to mean an addition, including negative additions. The approved hand-forged iron-sword lore and stale-bread lore are kept verbatim in Chinese and English.

| Draft group | Administrator decisions still open |
| --- | --- |
| Other equipment | Names and descriptions of `handmade`, `damaged`, `standard`, `refined`; source weights; attack, armour, durability, mining and projectile ranges |
| Other iron-sword sources | Drop, trade, vault and other real source pool weights; the four quality IDs retain the approved iron-sword stat meanings |
| Food | Names and descriptions of `stale`, `ordinary`, `choice`; source weights; nutrition, total saturation and eating-time ranges |
| Ingredient quality | Quality scores and the three ingredient-weight interpolation points per food variant |
| Spoilage | Generated spoilage levels, negative-effect probabilities, durations and amplifiers; there is no timed decay system |
| Processing | Cooking and crafting risk retention, risk floors and residual-effect settings |

Changes to existing source shards survive regeneration. Update the two language shards together when changing gameplay. `_quality_rules.mjs` supplies newly introduced source pools; it does not overwrite existing edited pools. The original source archive is retained in `_legacy_authored.json` so removed draft fields remain available for review.

The audited set is 1,218 survival items, with 115 explicit exclusions from the 1,333-item reference. Player heads have no vanilla survival acquisition path. Bundles in Java 1.21.1 require an experimental feature. Milk, potions and ordinary materials receive base lore only. Forty native edible items plus cake and 72 equipment items receive applicable quality pools; 68 equipment items have durability and four horse-armour items have no durability added.
