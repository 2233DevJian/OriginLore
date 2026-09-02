# OriginLore

OriginLore 是面向 Minecraft 1.21.1 的 Fabric 物品来源与数据组件管理模组。配置、来源判定、随机变体和物品刷新均由服务端决定；客户端模组只为管理员提供 GUI 和注册表 Tab 补全。

第一次使用 GUI 请先阅读 [中文使用手册](使用手册.md)，其中包含基础 Lore、来源规则、随机变体、食物效果和铁剑差异化的完整操作示例。

## 安装

- 服务端必须安装 OriginLore、Fabric API 和 Fabric Loader 0.19.2 或更高版本。
- 需要使用管理 GUI 的 OP 客户端安装同一个 OriginLore JAR 和 Fabric API。
- 普通玩家不需要安装 OriginLore 客户端，也能看到并使用服务端生成的数据组件。
- 运行环境固定为 Minecraft 1.21.1 和 Java 21。

将 `build/libs/originlore-2.0.1.jar` 放入实例的 `mods` 目录。首次启动会创建：

```text
config/originlore/item_components.json
```

## 管理 GUI

进入世界或服务器后按 `O` 打开管理界面。服务端只向权限等级至少为 2 的玩家发送配置；无权限、服务端未安装模组、协议不兼容或已经断线时，编辑功能会被禁用。

GUI 支持：

- 基础、来源、变体三层规则编辑。
- 物品、战利品表、配方、组件、附魔、属性、状态效果和方块 ID 补全。
- Tab、方向键、Enter、Escape 和鼠标操作补全列表。
- 名称和 Lore 的颜色、粗体、斜体，以及完整 Text JSON。
- 食物、附魔、属性修饰符、工具规则和高级数据组件编辑。
- 事务副本、服务端校验错误和配置版本冲突保护。

保存操作始终发往逻辑服务端，包括单人世界中的集成服务端。服务端校验并原子写盘成功后才会广播新版本并刷新物品。

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

未填写的字段保持物品原值。字段从配置中删除后，OriginLore 会恢复首次接管前记录的原始组件补丁。特定战利品表或配方 ID 无法取得时，只匹配没有具体 ID 限制的来源大类规则。

支持的来源类型：

```text
BLOCK_DROP  CHEST_LOOT  ENTITY_DROP  FISHING  ARCHAEOLOGY
BARTER      GIFT        VAULT        COMMAND  CRAFTING
SMELTING    CUTTING     SMITHING     UNKNOWN
```

无法可靠追溯来源的旧物品或模组自定义入口归入 `UNKNOWN`，不会猜测成箱子或合成来源。

## 常用字段

| 字段 | 含义 |
| --- | --- |
| `customName` / `customNameJson` | 纯文本名称或原版 Text JSON |
| `lore` / `loreJson` | 纯文本 Lore 行或 Text JSON 行 |
| `rarityName` | `common`、`uncommon`、`rare`、`epic` |
| `maxStackSize` / `maxStackSizeRange` | 固定或首次生成时随机的最大堆叠数 |
| `maxDamage` / `maxDamageRange` | 固定或首次生成时随机的最大耐久 |
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

- 变体只在首次接管时按权重抽取，重启、复制、拆分和区块重载后保持不变。
- 不同变体带有不同身份，因此不会错误堆叠；相同变体即使分别由熔炉和烟熏炉产出，只要实际组件一致也可堆叠。
- 熔炉、烟熏炉和高炉每完成一件 OriginLore 物品后暂停，取走产物后才按最新权重开始下一件，避免单个产物槽混入不同变体。
- 已有明确来源的物品经过 `UNKNOWN` 回退入口时仍保留原来源。
- 配置保存后，在线玩家背包、末影箱、装备、打开的容器、已加载方块库存和物品实体会增量刷新。
- 玩家登录、区块加载、实体加载和库存变更时会惰性刷新。
- 模组不会扫描未加载区块，也不会直接改写离线存档。

## 兼容性边界

标准 `ItemStack`、LootTable、原版配方体系和持久数据组件可自动工作。模组在 Java 中写死、且没有用数据组件表达的行为需要单独适配。来源入口不可识别时物品仍可通过 `UNKNOWN` 规则接管。

OriginLore 不修改第三方 JAR。完整整合包兼容测试应在隔离实例中引用原始模组目录，只把 OriginLore JAR 和测试世界写入隔离目录。

## 构建与测试

```powershell
.\gradlew.bat clean test runGametest build --console=plain
```

默认构建目标是 Loader 0.19.2。快速回归 0.19.3 可显式覆盖：

```powershell
.\gradlew.bat test runGametest '-Ploader_version=0.19.3' --console=plain
```

PowerShell 中带点号的 Gradle 属性参数应使用引号。生产 JAR 位于 `build/libs`，不应使用 `-dev` 或 `-sources` JAR 部署。

## 实现结构

- `ItemComponentConfig`: schema、迁移、事务快照、原子保存和 revision。
- `ItemComponentManager`: 原始值恢复、规则合并、稳定随机值、Codec 校验和组件事务提交。
- `SourceContext` 与来源 Mixin: Loot、命令、合成、熔炼、切石、锻造和通用回退。
- `RefreshService`: 在线及已加载对象的有界增量刷新。
- `OriginLoreNetworking`: OP 权限、压缩分片、大小限制、冲突检查和版本广播。
- `ClientConfigSession` 与各 Screen: 只读快照缓存和事务式管理员 GUI。

许可证：MIT。
