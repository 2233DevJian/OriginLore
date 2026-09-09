[English version](docs/en/COMPATIBILITY.md) | [项目概览](README.zh-CN.md)

# OriginLore 兼容性与自动化测试规范 (v3.0.1)

**适用版本**：OriginLore 3.0.1

**运行基准**：Minecraft 1.21.1 / Fabric Loader ≥ 0.19.2 / Java 21

---

## 1. 自动化测试套件

OriginLore 拥有极高的工程质量标准，仓库内置两套自动化测试：

### ✦ JUnit 单元测试（91 项全部通过）
- **覆盖领域**：配置 Schema v5 解析、旧版 Schema 自动迁移、原子写盘与坏损回滚、Revision 递增版本控制、网络分片解压限流、三层规则合并算法、FIFO 食品队列计算。
- **执行方式**：包含于 `./gradlew build` 中，在 GitHub Actions 持续集成流程中自动运行。

### ✦ GameTest 实机服务端测试（63 项全部通过）

- **覆盖领域**：组件应用与回滚机制、第三方 `custom_data` 隔离保护、变体持久化稳定性、熔炉/烟熏炉/高炉产物槽暂停重抽逻辑、同品质堆叠/异品质隔离逻辑、`insertStack` / `setStack` 玩家库存发放的 `UNKNOWN` 智能回退。
- **执行命令**：
```powershell
  .\gradlew.bat runGametest --console=plain
```

## 2. 第三方模组共存准则

### 绝对非侵入式

OriginLore 绝不修改任何第三方模组的 JAR、配置或内置资源。

### 数据独立隔离

第三方模组存放在 minecraft:custom_data 下的字段原样完整保留。
OriginLore 专属的元数据与来源标记严格隔离于 minecraft:custom_data.originlore 命名空间下。

### 无损数据回退

若从 OriginLore 中删除某物品的规则配置，模组会自动恢复该物品在接管前保存的组件补丁，不留冗余垃圾数据。

### 模组自定义发放

支持其他模组直接调用原版接口向玩家塞入物品时，模组会智能走 UNKNOWN 回退入口补齐规则，确保不遗漏任何新物品。

## 3. 已知行为边界

### 惰性加载机制

模组不主动扫描未加载的冷区块，亦不直接修改离线世界存档。待区块或实体加载时会自动进行无感惰性刷新。

### 注册表强校验

高级组件编辑器仅接受当前服务端注册表中有效且具备持久化 Codec 的合法组件，非法 JSON 会被服务端直接拒绝。
