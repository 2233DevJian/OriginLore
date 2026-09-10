**English** | [简体中文](README.zh-CN.md)

# OriginLore

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-6f58a2?style=flat-square)
![Fabric Loader](https://img.shields.io/badge/Fabric_Loader-%E2%89%A50.19.2-dbd0b4?style=flat-square)
![Java](https://img.shields.io/badge/Java-21-ed8b00?style=flat-square)
![Install](https://img.shields.io/badge/Install-Server--side-3d8c40?style=flat-square)
[![License: CC BY-NC-SA 4.0](https://img.shields.io/badge/License-CC_BY--NC--SA_4.0-blue?style=flat-square)](LICENSE)
![Build](https://github.com/2233DevJian/OriginLore/actions/workflows/build.yml/badge.svg)

> **Where items come from defines what they are.**
> OriginLore is a server-authoritative item storytelling and component-crafting mod designed for RPG modpacks and immersive servers.

OriginLore determines an item's **narrative lore, name, attributes, and quality** based on its **origin**. Whether salvaged from ancient crypts, fished from icy depths, forged at an anvil, or unlocked in trial chambers, 15 distinct sources can each carry tailored data components and weighted random variants upon generation.

All rule resolutions and live refreshes execute strictly on the **logical server** and are written directly into **vanilla Data Components**. **Regular players need no client mods installed**—vanilla clients read, render, and use the custom properties seamlessly.

---

## ✦ Highlights

- **Three-Tier Cascading Rule Model**
  `Base Rule` → `Specific Source Rule` → `First-generation Variant Rule`. Unconfigured fields inherit gracefully without wiping out native or lower-tier attributes.
- **15 Distinct Origin Sources**
  Supports loot tables and recipe IDs for pinpoint matching; legacy or untracked modded items fall safely back into `UNKNOWN` without inaccurate guesses.
- **Persistent & Stable Random Variants**
  Variants roll once upon first generation and persist indefinitely across chunk unloads, restarts, rucksack transfers, and reconnects. No irregular cross-quality stacking.
- **Food FIFO Queue Persistence**
  Built with first-in-first-out data tracking for food items, supporting inventory splits, hoppers, processing risks, honey bottles, and cake layers.
- **Lossless Quality & Durability Inheritance**
  Repairs, anvil renaming, grindstone cleansing, armor trims, and Netherite upgrades preserve the original item's quality identity and durability ratio.
- **True Incremental Live Refresh**
  Saving configurations immediately refreshes online player inventories, ender chests, open containers, and dropped item entities without server restarts.
- **Server-Authoritative Admin GUI**
  Exclusive to operators (OP level ≥ 2). Features registry tab-completion, Text JSON styling, transactional safety, conflict prevention, and a one-key held-item quick editor (`O` key).
- **1,218 High-Quality Survival Presets**
  Ready out of the box! Ships with complete, rich bilingual presets covering all 1,218 default survival items for Minecraft 1.21.1.

---

## 🛠 Installation

| Target Environment | Required Components |
| :--- | :--- |
| **Server / Modpack Server** | `OriginLore-3.0.1.jar` + Fabric API + Fabric Loader ≥ 0.19.2 |
| **Operator (OP) Client** | Same `OriginLore-3.0.1.jar` + Fabric API *(to access the GUI)* |
| **Regular Player Client** | **None** *(parsed natively by vanilla clients)* |

*Requirements: Minecraft 1.21.1 / Java 21.*

Download the latest release from the [Releases page](https://github.com/2233DevJian/OriginLore/releases) and place it in your `mods` folder. The configuration file generates automatically on first launch:
```text
config/originlore/item_components.json
```

## ⚡ Quick Start
Join your world or server. Hold an item in your main hand and press O (customizable under Options → Controls) to jump straight into its dedicated rule editor. Pressing O with an empty hand opens the full item catalog.
Navigate the left rule tree to configure Base Rules, Source Rules (e.g., Dungeon Chests, Crafting), and Weighted Quality Variants.
Click "Save" at the bottom. The configuration will validate server-side, write atomically to disk, and immediately update items across the server.
💡 Need to force a reload from disk? Run:

```bash
/originlore reload
```

## 📐 Rule Model Example (Schema v5)

```json
{
  "schemaVersion": 5,
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
            { "id": "fresh", "weight": 6, "rule": {} },
            { "id": "stored", "weight": 3, "rule": { "lore": ["Berries stored long in damp dungeon chests."] } },
            { "id": "rotten", "weight": 1, "rule": { "itemName": "Rotten Sweet Berries", "food": { "nutrition": 1, "saturation": 0.1 } } }
          ]
        }
      ]
    }
  }
}
```

## 📚 Documentation

| Document | English | 中文 |
| --- | --- | --- |
| User Guide | [docs/en/USER_GUIDE.md](docs/en/USER_GUIDE.md) | [使用手册.md](使用手册.md) |
| Compatibility & Tests | [docs/en/COMPATIBILITY.md](docs/en/COMPATIBILITY.md) | [COMPATIBILITY_TESTS.md](COMPATIBILITY_TESTS.md) |
| Changelog | [docs/en/CHANGELOG.md](docs/en/CHANGELOG.md) | [更新日志.md](更新日志.md) |

## 📄 License

OriginLore by [2233DevJian](https://github.com/2233DevJian) is licensed under [Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International (CC BY-NC-SA 4.0)](https://creativecommons.org/licenses/by-nc-sa/4.0/). See [LICENSE](LICENSE) for the full terms.

Sharing and adaptation require attribution, a license reference and an indication of changes. Use must be noncommercial, and shared adaptations must use the same or a compatible license as specified in the full terms. This license restricts commercial use and is not an open-source software license.

Third-party materials retain their own licenses. This change does not revoke permissions previously granted for earlier versions.
