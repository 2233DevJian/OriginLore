[English](README.md) | **简体中文**

# OriginLore

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-6f58a2?style=flat-square)
![Fabric Loader](https://img.shields.io/badge/Fabric_Loader-%E2%89%A50.19.2-dbd0b4?style=flat-square)
![Java](https://img.shields.io/badge/Java-21-ed8b00?style=flat-square)
![Install](https://img.shields.io/badge/Install-Server--side-3d8c40?style=flat-square)
[![License: CC BY-NC-SA 4.0](https://img.shields.io/badge/License-CC_BY--NC--SA_4.0-blue?style=flat-square)](LICENSE)
![Build](https://github.com/2233DevJian/OriginLore/actions/workflows/build.yml/badge.svg)

> **物随其源，质赋其形。**
> OriginLore 是一款专为 RPG 整合包与沉浸式服务器打造的服务端权威物品叙事与属性重构模组。

OriginLore 依据物品的**获取来源（Source）**决定它的**属性、品质与故事（Lore）**。从地牢宝箱、钓鱼、实体掉落到工匠锻造与试炼宝库，15 种独立来源均可拥有专属名称、叙事文本、稀有度、食物效果、附魔、属性修饰符与工具规则，并在产出时依权重抽取恒定变体。

全部运算与数据下发均由**逻辑服务端**接管，并以**原版数据组件（Data Components）**格式直接写入物品本身。**普通玩家客户端无需安装任何模组**，即可享受与原版无缝兼容的深度 RPG 体验。

---

## ✦ 核心特性一览

- **三层递进规则模型**
  `基础规则 (Base)` → `最匹配来源规则 (Source)` → `首次抽取变体规则 (Variant)`。未配置字段完全继承原版或上一层设定，绝不破坏物品原始属性。
- **15 种精准来源识别**
  支持战利品表 / 配方 ID 精确匹配；对于旧物品或无法追踪来源的模组物品，自动回退至 `UNKNOWN` 统一规则，不作错误猜测。
- **持久化稳定随机变体**
  变体仅在物品首次生成时抽取并固化。跨维度、区块卸载、重启、拆分堆叠或重连均不会导致重新抽取，不同品质间绝不异常混堆。
- **食品 FIFO 队列持久化**
  兼容先进先出（FIFO）数据队列，支持拆分、合并、容器流转与自动合成；无缝记录食品品质、加工风险、蜂蜜与蛋糕状态。
- **无损属性继承与迁移**
  装备修理、铁砧改名、附魔、砂轮除魔、锻造纹饰乃至下界合金升级，均完整继承原物品的品质身份与耐久比例。
- **真正的热刷新（Live Refresh）**
  保存配置后，在线玩家背包、末影箱、装备槽、已加载容器与掉落物实体即刻增量更新，无需重启服务器，亦不扫描未加载区块。
- **服务端权威的管理 GUI**
  仅向 2 级及以上 OP 开放。内置注册表 Tab 自动补全、原版 Text JSON 样式、事务保护与防并发冲突检测；支持主手持物按 `O` 一键快捷编辑。
- **全量 1.21.1 原版生存预设**
  开箱即用！内置覆盖 1,218 个默认生存可获得物品的高质量中英文预设，并支持游戏内一键双语无缝切换。

---

## 🛠 安装指南

| 部署环境 | 必需组件 |
| :--- | :--- |
| **服务端 / 整合包服务端** | `OriginLore-3.0.1.jar` + Fabric API + Fabric Loader ≥ 0.19.2 |
| **管理员（OP）客户端** | 相同的 `OriginLore-3.0.1.jar` + Fabric API *（用于打开管理 GUI）* |
| **普通玩家客户端** | **无需安装任何模组** *（完全由原版客户端解析）* |

*环境要求：Minecraft 1.21.1 / Java 21。*

从 [Releases 页面](https://github.com/2233DevJian/OriginLore/releases) 下载最新 JAR 并放入 `mods` 目录。首次启动后将自动生成配置文件：
```text
config/originlore/item_components.json
```

## ⚡ 快速上手
进入游戏后，主手持握需要编辑的物品按 O 键（可于“选项 → 控制”中自定义），即可直接进入该物品的专属编辑器；空手按 O 则打开全局物品列表。
展开左侧规则树，可在基础规则、来源规则（如箱子战利品、工作台合成）与加权变体间自由配置。
编辑完成后点击底部“保存”，配置将实时校验、原子写盘并广播刷新全服在线物品。
💡 想要重载磁盘配置？OP 玩家可在控制台或聊天框执行：

```bash
/originlore reload
```

## 📐 规则架构示意

配置文件遵循 Schema v5 规范：

```json
{
  "schemaVersion": 5,
  "revision": 0,
  "items": {
    "minecraft:sweet_berries": {
      "base": {
        "lore": ["酸甜可口的浆果，吃起来不错！"]
      },
      "sources": [
        {
          "type": "CHEST_LOOT",
          "lootTableId": "minecraft:chests/simple_dungeon",
          "rule": {},
          "variants": [
            { "id": "fresh", "weight": 6, "rule": {} },
            { "id": "stored", "weight": 3, "rule": { "lore": ["在地牢木箱中存放已久的浆果。"] } },
            { "id": "rotten", "weight": 1, "rule": { "itemName": "腐烂的甜浆果", "food": { "nutrition": 1, "saturation": 0.1 } } }
          ]
        }
      ]
    }
  }
}
```

## 🔧 15 种受支持的来源枚举

```text
BLOCK_DROP   CHEST_LOOT   ENTITY_DROP   FISHING     ARCHAEOLOGY
BARTER       GIFT         VAULT         COMMAND     CRAFTING
SMELTING     CUTTING      SMITHING      TRADING     HARVEST
UNKNOWN
```

## 📚 文档索引

| 文档 | 中文 | English |
| --- | --- | --- |
| 完整使用指南 | [使用手册.md](使用手册.md) | [docs/en/USER_GUIDE.md](docs/en/USER_GUIDE.md) |
| 兼容性与测试说明 | [COMPATIBILITY_TESTS.md](COMPATIBILITY_TESTS.md) | [docs/en/COMPATIBILITY.md](docs/en/COMPATIBILITY.md) |
| 版本更新日志 | [更新日志.md](更新日志.md) | [docs/en/CHANGELOG.md](docs/en/CHANGELOG.md) |

## 📄 许可协议

OriginLore 由 [2233DevJian](https://github.com/2233DevJian) 创作，采用 [知识共享署名-非商业性使用-相同方式共享 4.0 国际许可协议（CC BY-NC-SA 4.0）](https://creativecommons.org/licenses/by-nc-sa/4.0/deed.zh-hans)。完整条款见 [LICENSE](LICENSE)。

分享与改编时需署名、提供许可信息并注明修改；仅限非商业性使用，分享改编作品时须按完整条款采用相同或兼容许可。该协议限制商业使用，不属于严格意义上的开源软件许可证。

第三方材料保留各自的许可证。本次更换不撤销此前版本已授予的许可。
