# OriginLore 兼容性说明

适用版本：OriginLore 2.0.1  
运行环境：Minecraft 1.21.1、Fabric Loader 0.19.2 或更高、Java 21

## 安装要求

| 位置 | 需要安装 |
| --- | --- |
| 服务端 / 整合包服务端 | OriginLore + Fabric API + Fabric Loader 0.19.2 或更高 |
| 需要使用管理界面的 OP 客户端 | 同一个 OriginLore JAR + Fabric API |
| 普通玩家客户端 | 不需要安装 OriginLore |

普通玩家即使不安装本模组，也能正常看到并使用服务端生成的名称、Lore、稀有度、食物效果、附魔和属性修饰符——这些内容全部以原版数据组件的形式写入物品本身，由原版客户端解析。

管理界面只对权限等级不低于 2 的 OP 开放。服务端不会向权限不足的客户端下发配置。

## 自动化测试

仓库包含两套测试：

- **JUnit 单元测试 18 项**，覆盖配置 schema 与旧版迁移、原子保存与损坏回退、配置 revision、网络分片传输与大小限制、三层规则合并与来源解析。`./gradlew build` 会自动执行，GitHub Actions 在每次 push 和 pull request 上运行。
- **GameTest 12 项**，在真实 Minecraft 服务端中运行，覆盖组件应用与恢复、第三方 `custom_data` 保留、变体在刷新与复制后的稳定性、熔炉/烟熏炉/高炉的暂停与重抽、相同变体可堆叠而不同变体隔离，以及物品直接进入玩家库存时的 `UNKNOWN` 回退。需要单独执行：

```powershell
.\gradlew.bat runGametest --console=plain
```

Fabric Loader 0.19.2 与 0.19.3 下，两套测试均全部通过。

## 自动识别的来源入口

以下路径无需额外配置即可识别来源：

- 战利品表：箱子与结构战利品、地牢、钓鱼、考古、猪灵以物易物、试炼密室宝库
- 掉落：方块掉落、实体掉落
- 命令：`/give` 等命令生成的物品
- 配方：工作台合成、熔炉/高炉/烟熏炉熔炼、切石、锻造台转换与纹饰
- 铁砧
- 玩家库存：其他模组直接调用 `insertStack` 或 `setStack` 发放的物品

无法可靠追溯来源的物品（例如本模组安装前就已存在的旧物品，或没有走标准生成路径的模组自定义入口）会归入 `UNKNOWN`。你可以为 `UNKNOWN` 单独配置规则统一接管，OriginLore 不会把它猜测成箱子或合成来源。

## 与其他模组的共存

- OriginLore 不修改任何第三方模组的 JAR、配置文件或资源。
- 只接管你在配置中显式列出的物品字段。未配置的字段完整保留原版、玩家和其他模组写入的数据，包括其他模组存放在 `minecraft:custom_data` 下的内容。
- 被接管的物品在 `minecraft:custom_data.originlore` 下保存自己的来源身份、变体 ID、配置版本和受管理字段的原始值，与第三方数据分开存放，互不覆盖。
- 从配置中删除某个字段后，OriginLore 会恢复该物品首次被接管前记录的原始组件补丁，而不是留下一个空值。
- 其他模组直接往玩家库存里发放物品时会走 `UNKNOWN` 回退；如果该模组自己已经写入了组件，OriginLore 只补充配置中要求的字段。

## 已知限制

- 其他模组在 Java 代码中写死、且没有用标准数据组件表达的行为，无法只靠高级组件伪造出来，需要单独适配。
- OriginLore 不扫描未加载的区块，也不直接改写离线世界存档。这些物品会在下次被加载时惰性处理。
- 高级数据组件编辑器只接受当前注册表中存在、且具备持久化 Codec 的组件。无效或不存在的组件会被服务端拒绝保存，不会写入配置或物品。
- 随机变体只在物品首次被接管时抽取一次。之后的配置热修改、重启、复制、拆分、丢弃、区块重载和玩家重连都不会重新抽取。

## 从源码构建

```powershell
.\gradlew.bat test runGametest build --console=plain
```

默认构建目标是 Fabric Loader 0.19.2。若要针对 0.19.3 做快速回归：

```powershell
.\gradlew.bat test runGametest '-Ploader_version=0.19.3' --console=plain
```

PowerShell 中带点号的 Gradle 属性参数需要加引号。

生产 JAR 输出到 `build/libs/originlore-2.0.1.jar`。部署时请使用这个 JAR，不要使用 `-dev` 或 `-sources` 版本。
