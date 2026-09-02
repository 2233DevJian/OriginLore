# OriginLore 兼容性测试报告

测试日期：2026-09-02  
目标版本：Minecraft 1.21.1、Fabric Loader 0.19.2、Java 21

本报告以 2.0.0 的整合包实机测试为基线，2.0.1 的增量验证见下一节。除下一节明确列出的项目外，其余整合包实机结果均取得于 2.0.0，未在 2.0.1 上重跑。

## v2.0.1 增量验证

2.0.1 修复了两个缺陷：`PlayerInventoryMixin` 从未注册到 `originlore.mixins.json`；以及该 Mixin 用单个回调同时声明两个 `insertStack` 重载，导致两参数重载的注入静默失败。

### 已重跑

- `.\gradlew.bat build runGametest --console=plain`（Loader 0.19.2）：JUnit 18 项、GameTest 12 项全部通过，日志中无任何 Mixin 注入错误或警告。
- `.\gradlew.bat test runGametest '-Ploader_version=0.19.3' --console=plain`：日志确认实际加载 `Fabric Loader 0.19.3`，同样 18 + 12 全部通过。
- 新增 GameTest `playerInventoryInsertionAppliesUnknownFallback`，分别覆盖 `insertStack(ItemStack)`、`insertStack(int, ItemStack)` 与 `setStack(int, ItemStack)`，并同时断言基础规则（Lore）与 `UNKNOWN` 来源规则（`customName`）生效。拆分回调**之前**，该测试精确报出 `insertStack(int, ItemStack) did not apply the base Lore`，而服务端日志中没有任何 Mixin 错误——即静默半覆盖；拆分之后通过。
- 生产 JAR 核验：`originlore.mixins.json` 注册 16 个真实 Mixin（含 `PlayerInventoryMixin`），`originlore.refmap.json` 存在且该 Mixin 的三个目标均已映射。

### 生产形态静态核验

- 用 `javap` 对生产形态的 intermediary jar（`minecraft-common-intermediary-1.21.1`）核验 `net.minecraft.class_1661` 上 `method_7394(Lnet/minecraft/class_1799;)Z`、`method_7367(ILnet/minecraft/class_1799;)Z`、`method_5447(ILnet/minecraft/class_1799;)V` 真实存在且描述符与 refmap 完全一致。这补上了 GameTest 开发环境（named 映射）覆盖不到的生产解析路径。
- 静态扫描隔离整合包 138 个模组 JAR 的 refmap，只有两个模组触及 `class_1661` 的这两个方法，且均为打在**调用点**上的 `@Redirect`：
  - `fabric-carpet-1.21-1.4.147+v240613`：`Inventory_scarpetEventMixin` 在 `insertStack(ItemStack)` 内部重定向对 `insertStack(int, ItemStack)` 的调用。
  - `collective-1.21.1-8.25`：`ItemEntityMixin` 在 `ItemEntity.playerTouch` 内部重定向对 `insertStack(ItemStack)` 的调用。

  两者的调用点与宿主方法各不相同，不构成 redirector 冲突；OriginLore 的 `@Inject` 位于方法 HEAD，与 `@Redirect` 可共存。
- carpet 的这条重定向同时证明 `insertStack(ItemStack)` 委托给 `insertStack(int, ItemStack)`，因此单次调用会触发两次组件应用。`ItemComponentManager.applyComponents` 在元数据版本、配置 revision 与来源均未变化时提前返回 `unchanged`，重复应用幂等且不会重掷随机变体。

### 未重跑

- 下文"Fabric Loader 0.19.2 整合包服务端"的 239 模组实机启动仍为 2.0.0 结果。
- 下文"Fabric Loader 0.19.2 整合包客户端"的 271 模组实机启动仍为 2.0.0 结果。
- 上述静态扫描属于 JAR 层面分析，不等价于实机 Mixin 应用结果；`PlayerInventory` 是热门注入目标，2.0.1 尚未在真实整合包服务端实机运行过。

## 隔离与数据保护

- 所有兼容性测试均位于 `OriginLore/build` 下的隔离目录。
- 服务端隔离目录：`build/compat-server-0192`。
- 客户端隔离目录：`build/compat-client-0192`。
- 第三方模组取自专用 0.19.2 测试实例及其 AutoModpack 模组集合。
- 未修改原始 AutoModpack 内容、第三方模组 JAR 或原始存档。
- 测试世界为隔离生成的 `build/compat-server-0192/compat-world`，不属于原始实例。

> 上述两个隔离目录已于 2026-09-02 被 `gradlew clean build` 删除（位于 `build/` 内，属于清理范围）。下文所有整合包实机结果均取自删除前的 2.0.0 运行；若需重跑，须先从原始 AutoModpack 实例重建隔离目录。

## 自动化测试

最终回归命令：

```powershell
.\gradlew.bat test runGametest build --console=plain
```

JUnit 共 18 个测试，0 失败、0 错误、0 跳过：

| 测试套件 | 数量 | 结果 |
| --- | ---: | --- |
| `ItemComponentConfigTest` | 7 | 通过 |
| `PayloadTransportTest` | 6 | 通过 |
| `RuleResolutionTest` | 5 | 通过 |

GameTest 共 11 个测试，全部通过：

- 高级组件 Codec 拒绝无效值。
- 跨物品转换使用组件补丁语义。
- 身份组件应用失败时不污染目标物品。
- 受管理组件可恢复且保留第三方数据。
- 变体经过刷新和复制后保持稳定。
- 结构化组件通过原版 Codec 应用。
- 随机区间的所有端点均参与校验。
- 熔炉、烟熏炉和高炉在每件受管理产物完成后暂停，取出产物后才继续。
- 取出产物后按当前权重重新抽取下一件变体。
- 热修改权重只影响下一件产物，已生成变体保持不变。
- 相同变体跨熔炉/烟熏炉/高炉可以堆叠。
- 不同变体即使 Lore 相同也不会堆叠，外部 `custom_data` 仍参与比较。

## Fabric Loader 0.19.2 整合包服务端

服务端在完整 AutoModpack 模组集合下加载 239 个模组，日志记录：

```text
Loading Minecraft 1.21.1 with Fabric Loader 0.19.2
Loading 239 mods
Done (4.416s)!
```

结果：

- OriginLore 2.0.0 初始化成功，配置 revision 1 加载成功。
- 服务端到达可接收玩家连接的完成状态，并可正常停止。
- 没有 OriginLore Mixin 应用失败、refmap 缺失、注册表错误或客户端类误加载。
- 生产 JAR 包含 `originlore.refmap.json`。

  **计数更正**：本条原先写作"配置中注册的 17 个生产 Mixin 均有对应类"，该数字有误。2.0.0 的 `originlore.mixins.json` 实际注册 15 项；`RecipeMixin.class` 虽编译进 JAR，但它本身没有 `@Mixin`，只是四个嵌套 Mixin 的容器，本就不应注册。同时 `PlayerInventoryMixin.class` 也已编译进 JAR 却未出现在注册列表中——这正是 2.0.1 修复的缺陷。2.0.1 起注册数为 16。

### 来源与随机变体

通过原版村庄针叶林房屋战利品表生成甜浆果，确认持久化数据包括：

```text
source_type = CHEST_LOOT
loot_table_id = minecraft:chests/village/village_taiga_house
source_id = minecraft:chests/village/village_taiga_house
config_revision = 1
```

重复生成时观察到 `fresh`、`stored`、`rotten` 三个带权重变体。`stored` 和 `rotten` 保存了原始 `minecraft:food` 组件，其营养值为 2、饱和度为 0.4；变体身份及原始组件均写入 `minecraft:custom_data.originlore`。

### 热刷新与损坏回退

锁定同一件 `stored` 甜浆果后修改隔离配置并执行 `/originlore reload`：

- revision 从 1 增至 2。
- Lore 立即更新。
- `variant_id = stored`、来源 ID 和原始组件保持不变。

随后故意写入损坏 JSON：

- 重载被拒绝，并报告准确的行、列和 JSON 路径。
- 服务端继续使用上一份有效配置，没有崩溃。
- 恢复文件后重载成功，revision 增至 3，物品恢复预期 Lore。

日志中的这一次 OriginLore `ERROR` 是故意执行的损坏配置回退测试，不是启动故障。

## Fabric Loader 0.19.2 整合包客户端

隔离客户端加载 271 个客户端模组及 OriginLore 2.0.0，能够进入主界面且进程正常退出，没有观察到 OriginLore Mixin、refmap 或客户端初始化崩溃。

连接隔离服务端时，服务端收到登录请求，但 AutoModpack 按预期拒绝未加载 AutoModpack 的隔离客户端：

```text
OriginLoreTest has not installed AutoModpack.
AutoModpack mod for fabric modloader is required to play on this server!
```

这次拒绝发生在 OriginLore GUI 网络会话建立之前，因此不能作为 OP GUI 保存流程的验收结果。

## 整合包自身警告

兼容日志中存在第三方模组的 refmap 缺失、服务端加载客户端 Mixin 目标、Patchouli 旧书籍格式、缺失数据修复器/资源以及 Immersive Portals 兼容性警告。这些日志均指向对应第三方模组，不包含 OriginLore 类或 OriginLore 注入点，并且没有阻止服务器完成启动。

这些警告只记录为整合包基线现状；OriginLore 不修改相应 JAR 或配置。

## 尚需人工交互验收

下列项目需要在用户允许的客户端交互会话中实际操作，当前不标记为通过：

- 使用安装了服务器所要求 AutoModpack 的 OP 客户端连接，然后按 `O` 打开管理 GUI。
- 验证物品 ID、战利品表 ID、配方 ID 和组件 ID 的 Tab/方向键/鼠标补全。
- 分别新增、修改和删除基础规则、来源规则及变体，确认保存后 revision 自动更新。
- 用第二个 OP 客户端确认新 revision 广播和版本冲突拒绝。
- 断线后确认当前编辑器立即转为不可提交状态。
- 用未安装 OriginLore 客户端的普通玩家确认服务端物品的名称、Lore、食物效果和属性仍可正常使用。

## 产物

- 生产 JAR：`build/libs/originlore-2.0.1.jar`
- 源码 JAR：`build/libs/originlore-2.0.1-sources.jar`
- JUnit 报告：`build/reports/tests/test/index.html`
- GameTest 结果：`build/gametest-results.xml`
- 0.19.2 服务端日志：`build/compat-server-0192/logs/latest.log` —— **已不存在**。2026-09-02 执行 `gradlew clean build` 时连带删除了 `build/compat-server-0192` 与 `build/compat-client-0192`，因此上文 2.0.0 的整合包实机日志已无法复查，只能依据本报告的记录。原始整合包（`.minecraft` 下的 AutoModpack 实例）未受影响，隔离环境可据此重建。
