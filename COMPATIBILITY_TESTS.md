# OriginLore 2.0.0 兼容性测试报告

测试日期：2026-09-02  
目标版本：Minecraft 1.21.1、Fabric Loader 0.19.2、Java 21

## 隔离与数据保护

- 所有兼容性测试均位于 `OriginLore/build` 下的隔离目录。
- 服务端隔离目录：`build/compat-server-0192`。
- 客户端隔离目录：`build/compat-client-0192`。
- 第三方模组取自专用 0.19.2 测试实例及其 AutoModpack 模组集合。
- 未修改原始 AutoModpack 内容、第三方模组 JAR 或原始存档。
- 测试世界为隔离生成的 `build/compat-server-0192/compat-world`，不属于原始实例。

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
- 生产 JAR 包含 `originlore.refmap.json`，配置中注册的 17 个生产 Mixin 均有对应类。

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

- 生产 JAR：`build/libs/originlore-2.0.0.jar`
- 源码 JAR：`build/libs/originlore-2.0.0-sources.jar`
- JUnit 报告：`build/reports/tests/test/index.html`
- GameTest 结果：`build/gametest-results.xml`
- 0.19.2 服务端日志：`build/compat-server-0192/logs/latest.log`
