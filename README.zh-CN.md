[English](README.md) | **简体中文**

# OriginLore

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-6f58a2)
![Fabric Loader](https://img.shields.io/badge/Fabric_Loader-%E2%89%A50.19.2-dbd0b4)
![Java](https://img.shields.io/badge/Java-21-ed8b00)
![Install](https://img.shields.io/badge/Install-server--side-3d8c40)
![License](https://img.shields.io/badge/License-MIT-blue)
![Build](https://github.com/2233DevJian/OriginLore/actions/workflows/build.yml/badge.svg)

OriginLore 依据物品**从哪里来**决定它**是什么**。箱子战利品、方块掉落、实体掉落、钓鱼、考古、猪灵以物易物、试炼密室宝库、工作台合成、熔炼、切石、锻造、铁砧和 `/give` 各自被识别为一种独立来源；每种来源都可以拥有自己的名称、Lore、稀有度、食物效果、附魔、属性修饰符和工具规则，也支持按权重抽取、且只在首次生成时抽取一次的随机变体。

配置、来源判定、随机变体和物品刷新全部由逻辑服务端决定，结果以**原版数据组件**的形式写入物品本身。普通玩家不需要安装任何东西：原版客户端看到并使用这些内容的过程，与数据包或命令写入的结果完全一致。可选的客户端模组只为管理员提供游戏内管理界面和基于注册表的 Tab 补全。

## 特性一览

- **三层规则模型** —— 基础规则 → 最具体的匹配来源规则 → 首次生成时抽到的变体规则。未填写的字段表示继承，绝不会把物品原有的值清空。
- **14 种来源识别**，可选按战利品表 / 配方 ID 精确匹配；无法追溯的旧物品和模组自定义入口安全归入 `UNKNOWN`。OriginLore 不猜测来源。
- **稳定的随机变体** —— 只抽取一次并持久保存。重启、区块重载、拆分、堆叠和重连都不会重抽，不同变体也不会错误堆叠。
- **可回退** —— 从配置中删除某个字段后，物品会恢复 OriginLore 首次接管它之前记录的原始组件补丁，而不是留下一个空值。
- **热刷新** —— 保存配置后，在线玩家背包、末影箱、装备、打开的容器、已加载方块库存和物品实体会增量刷新。未加载区块和离线存档从不被扫描。
- **服务端权威的管理界面** —— 配置只下发给权限等级不低于 2 的 OP，每次编辑都会在服务端重新校验后才原子写盘。内置事务副本、版本冲突检测和断线保护。
- **高级组件编辑器** —— 任意 `ComponentType` JSON，由当前注册表、该组件自身的持久化 Codec 以及完整的 `ItemStack` 组件校验器共同验证。无效组件被拒绝，不会被写入。
- **不侵入其他模组** —— 不修改任何第三方模组的 JAR、配置或资源。`minecraft:custom_data` 下的第三方数据完整保留，OriginLore 自己的记录单独存放在 `minecraft:custom_data.originlore`。

## 安装

| 位置 | 需要安装 |
| --- | --- |
| 服务端 / 整合包服务端 | OriginLore + Fabric API + Fabric Loader 0.19.2 或更高 |
| 需要使用管理界面的 OP 客户端 | 同一个 OriginLore JAR + Fabric API |
| 普通玩家客户端 | 不需要安装 |

运行环境固定为 Minecraft 1.21.1 和 Java 21。

从 [Releases 页面](https://github.com/2233DevJian/OriginLore/releases) 下载 `originlore-2.0.1.jar`，放入实例的 `mods` 目录；自行构建时产物同样位于 `build/libs`。部署时只用这个 JAR，不要使用 `-dev` 或 `-sources` 版本。首次启动会创建：

```text
config/originlore/item_components.json
```

## 快速上手

进入世界或服务器后按 `O` 打开管理界面（可在“选项 → 控制”中改键）。服务端只向权限等级至少为 2 的玩家发送配置；无权限、服务端未安装模组、协议不兼容或已经断线时，编辑功能会被明确禁用，而不是静默失败。

管理界面支持：

- 基础、来源、变体三层规则在同一棵规则树中编辑。
- 物品、战利品表、配方、组件、附魔、属性、状态效果和方块 ID 补全，数据来自服务端下发的注册表目录。
- `Tab`、方向键、`Enter`、`Escape` 和鼠标操作补全列表。
- 名称和 Lore 的颜色、粗体、斜体，以及完整的原版 Text JSON。
- 食物、附魔、属性修饰符、工具规则和高级数据组件编辑器。
- 事务副本、服务端校验错误提示和配置版本冲突保护。

保存操作始终发往逻辑服务端，包括单人世界中的集成服务端。服务端校验并原子写盘成功后，才会广播新版本并刷新物品。

`/originlore reload` 可由权限等级至少为 2 的命令源执行，用于强制从磁盘重新加载。损坏或无效的文件不会替换上一份有效配置。

## 规则模型

配置文件使用 schema v3：

```json
{
  "schemaVersion": 3,
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
            {"id": "fresh", "weight": 6, "rule": {}},
            {"id": "stored", "weight": 3, "rule": {"lore": ["存放很久的浆果。"]}}
          ]
        }
      ]
    }
  }
}
```

对每个物品，OriginLore 先选择最具体的一个来源规则，再按以下顺序合并：

```text
基础规则 -> 来源规则 -> 首次抽取的变体规则
```

未填写的字段保持物品原值。字段从配置中删除后，OriginLore 会恢复首次接管前记录的原始组件补丁。特定战利品表或配方 ID 在运行时无法取得时，只有没有限定具体 ID 的来源规则可以匹配，物品不会被错误归到另一个来源。

支持的来源类型：

```text
BLOCK_DROP  CHEST_LOOT  ENTITY_DROP  FISHING  ARCHAEOLOGY
BARTER      GIFT        VAULT        COMMAND  CRAFTING
SMELTING    CUTTING     SMITHING     UNKNOWN
```

旧物品和无法可靠追溯来源的模组自定义入口归入 `UNKNOWN`。你可以像配置其他来源一样为 `UNKNOWN` 单独配置规则，统一接管这些物品。

## 字段说明

| 字段 | 含义 |
| --- | --- |
| `customName` / `customNameJson` | 纯文本名称，或原版 Text JSON |
| `lore` / `loreJson` | 纯文本 Lore 行，或 Text JSON 行 |
| `rarityName` | `common`、`uncommon`、`rare`、`epic` |
| `maxStackSize` / `maxStackSizeRange` | 固定的最大堆叠数，或首次生成时随机 |
| `maxDamage` / `maxDamageRange` | 固定的最大耐久，或首次生成时随机 |
| `currentDamage` | 当前耐久损耗 |
| `fireResistant` | 是否拥有防火组件 |
| `enchantments` / `storedEnchantments` | 附魔 ID 到等级的映射 |
| `food` | 营养、饱和度、食用时间、随时食用和概率效果 |
| `attributes` | 属性、修饰符 ID、数值、运算和槽位 |
| `attackDamageRange` | 首次生成时抽取并持久保存的额外主手攻击伤害 |
| `tool` | 方块集合、挖掘速度、正确掉落和每方块耐久损耗 |
| `customModelData` | 自定义模型数据 |
| `hideTooltip` / `hideAdditionalTooltip` | Tooltip 隐藏组件 |
| `setComponents` / `removeComponents` | 高级组件 JSON 设置或移除 |

高级组件值由对应 Minecraft `ComponentType` 的持久化 Codec 和完整 `ItemStack` 组件校验器验证。不存在、不可持久化或无法按当前注册表解析的组件会被拒绝，不会写入配置或物品。

完整示例见 [config_example.json](config_example.json)；专用整合包中的模组物品示例见 [modpack_config_example.json](modpack_config_example.json)。

## 来源身份与热刷新

被接管的物品会在 `minecraft:custom_data.originlore` 中保存来源、具体 ID、变体 ID、配置 revision、随机值和受管理字段的原始组件补丁。

- 变体只在首次接管时按权重抽取一次，此后在重启、复制、拆分和区块重载中保持不变。
- 不同变体带有不同身份，因此不会错误堆叠；相同变体即使分别由熔炉和烟熏炉产出，只要实际组件一致也可正常堆叠。
- 熔炉、烟熏炉和高炉每完成一件 OriginLore 物品后暂停，取走产物后才按最新权重开始下一件，避免单个产物槽混入不同变体。
- 已有明确来源的物品经过 `UNKNOWN` 回退入口时仍保留原来源，不会被降级覆盖。
- 配置保存后，在线玩家背包、末影箱、装备、打开的容器、已加载方块库存和物品实体会增量刷新。
- 玩家登录、区块加载、实体加载和库存变更时会惰性刷新。
- 模组不会扫描未加载区块，也不会直接改写离线存档。

## 兼容性

标准 `ItemStack` 构造、LootTable、原版配方体系和持久数据组件可自动工作。其他模组在 Java 中写死、且没有用数据组件表达的行为需要单独适配。来源入口不可识别时，物品仍可通过 `UNKNOWN` 规则接管。

OriginLore 不修改第三方模组的 JAR、配置或资源。与其他模组的共存细节、自动识别的来源入口和已知限制见[兼容性说明](COMPATIBILITY_TESTS.md)。

## 文档索引

| 主题 | 中文 | English |
| --- | --- | --- |
| 项目概览 | 本文件 | [README.md](README.md) |
| 使用手册 —— 管理界面完整操作 | [使用手册.md](使用手册.md) | [docs/en/USER_GUIDE.md](docs/en/USER_GUIDE.md) |
| 兼容性、测试与已知限制 | [COMPATIBILITY_TESTS.md](COMPATIBILITY_TESTS.md) | [docs/en/COMPATIBILITY.md](docs/en/COMPATIBILITY.md) |
| 更新日志 | [更新日志.md](更新日志.md) | [docs/en/CHANGELOG.md](docs/en/CHANGELOG.md) |

第一次使用管理界面前请先阅读[使用手册](使用手册.md)，其中包含基础 Lore、来源规则、随机变体、食物效果、铁剑差异化和高级组件编辑器的完整操作示例。

## 从源码构建

```powershell
.\gradlew.bat test runGametest build --console=plain
```

默认构建目标是 Fabric Loader 0.19.2。若要针对 0.19.3 做快速回归，可显式覆盖：

```powershell
.\gradlew.bat test runGametest '-Ploader_version=0.19.3' --console=plain
```

PowerShell 中带点号的 Gradle 属性参数应使用引号。生产 JAR 位于 `build/libs`。

## 实现结构

- `ItemComponentConfig`：schema、迁移、事务快照、原子保存和 revision。
- `ItemComponentManager`：原始值恢复、规则合并、稳定随机值、Codec 校验和组件事务提交。
- `SourceContext` 与来源 Mixin：Loot、命令、合成、熔炼、切石、锻造、玩家库存和通用回退。
- `RefreshService`：在线及已加载对象的有界增量刷新。
- `OriginLoreNetworking`：OP 权限、压缩分片、大小限制、冲突检查和版本广播。
- `ClientConfigSession` 与各 Screen：只读快照缓存和事务式管理员 GUI。

## 许可证

MIT。
