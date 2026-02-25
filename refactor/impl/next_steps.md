# 后续注意事项与建议

本文档旨在为后续在 Gluten 项目中成功编译、集成和验证 `backends-common` 模块提供指导。

## 1. 编译环境与构建修复建议

本地 `make jar` 命令的失败表明，尽管代码逻辑正确，但新模块 `backends-common` 未能在当前的 Maven 环境中被正确配置。

### 核心问题
Scala 编译器在编译 `backends-common` 时缺少核心依赖（如 `scala.native`），这通常与 `scala-maven-plugin` 的配置或类路径有关。

### 修复步骤建议
1.  **检查父 POM 与 Profile**:
    - **位置**: `gluten/pom.xml`
    - **操作**: 仔细核对 `<profile>` 部分中 `backends-velox` 和 `backends-bolt` 的配置。检查是否存在除了 `<modules>` 之外，还为子模块提供了特殊编译插件配置、依赖管理或属性的部分。新添加的 `backends-common` 可能需要被加入到这些配置块中。
    - **关注点**: 寻找 `scala-maven-plugin`, `build-helper-maven-plugin` 等与源码生成、编译路径相关的插件配置。

2.  **为 `backends-common` 补充编译插件配置**:
    - **位置**: `gluten/backends-common/pom.xml`
    - **操作**: 尝试从一个能够正常独立编译的模块（如 `gluten-core` 或 `gluten-substrait`）中，复制其 `<build>` 部分关于 `scala-maven-plugin` 和其他相关编译插件的完整配置到 `backends-common` 的 `pom.xml` 中。这可以确保它拥有一个明确且完整的编译环境声明，而不是完全依赖父 POM 的继承。

3.  **清理与重新构建**:
    - **操作**: 在修改 POM 文件后，执行 `mvn clean` 清理所有旧的构建产物，然后再次尝试 `make jar` 或直接针对 `backends-common` 执行 `mvn package`。
    - **命令**:
      ```bash
      cd gluten
      mvn clean
      # 尝试单独编译新模块以快速定位问题
      mvn package -pl backends-common -am
      # 如果单独编译成功，则尝试完整构建
      make jar
      ```

## 2. 外部环境与 SCM 编译触发建议

一旦本地编译问题解决，即可在持续集成（CI）环境中触发编译，以确保变更在标准构建环境下同样有效。

### SCM (Software Configuration Management) 编译触发
- **目标**: 在公司的 CI/CD 平台（如 Jenkins, GitLab CI）上触发一次完整的项目构建。
- **触发条件**: 将本地修复后的代码提交到一个新的 Git 分支，并发起合并请求（Merge Request）或直接推送到特定分支来触发自动构建。
- **构建命令/脚本**:
  - CI 环境通常会执行与 `Makefile` 中定义的类似命令，例如 `make` 或 `mvn clean package [options]`。
  - **必须确保传递了正确的 Profile**，以激活包含 `backends-common` 在内的所有相关模块。例如：
    ```bash
    # 示例：在 SCM 任务中确保激活 velox 后端，从而编译 backends-common
    mvn clean package -Pbackends-velox -Pspark-3.3 -DskipTests
    ```
    或者
    ```bash
    # 示例：同时激活 bolt 后端
    mvn clean package -Pbackends-bolt -Pspark-3.3 -DskipTests
    ```
- **验证**: 关注 SCM 构建日志，确保 `backends-common` 模块被识别、编译，并且依赖它的 `backends-velox` 和 `backends-bolt` 也成功编译。

## 4. 最新进展与后续计划

本轮已完成 `SharedLibraryLoader` 的公共化抽取，并修复了由此引入的构建与格式化问题。后续代码迁移建议保持不变：

1.  **逐个分析与迁移**：继续按照 `mapping.csv` 清单，以“小步快跑”的原则，逐个分析和迁移公共文件。
2.  **小步提交与验证**：每次迁移后都执行本地编译，确保改动没有破坏构建。

下一个明确的迁移目标是 `ArrowCSVFileFormat` 及相关的数据源工具类。

## 最新进展与后续计划 (Round 2)

**最新进展**:

第二轮重构已完成，主要集中在 **Metrics 组件** 的抽取：
1.  **DTOs**: `Metrics.java` 和 `OperatorMetrics.java` 已统一迁移到 `backends-common`，并遵循“保留 Velox 命名”的规则（`veloxToArrow`）。
2.  **工具类**: `MetricsUtil.scala` 已被通用化并迁移到 `backends-common`，通过 `BackendsApiManager.getBackendName` 动态区分后端。
3.  **Updaters**:
    - 15 个完全一致的 `*MetricsUpdater` 已迁移到 `backends-common`，由 Velox/Bolt 共同复用；
    - 对 `HashAggregateMetricsUpdater` 抽取了公共 `trait`，并保留两端各自的 `HashAggregateMetricsUpdaterImpl` 差异实现在后端模块中。
4.  **CSV 组件**: 纯工具类 `ArrowCSVOptionConverter.scala` 已迁移到 `backends-common`，为后续完整抽取 Arrow CSV datasource 做铺垫。

**后续计划**:

1.  **CSV Datasource 的完整抽取**
    - 目标: 迁移 `ArrowCSVFileFormat.scala` 及 `v2` 下的 `ArrowCSVScan.scala`、`ArrowCSVScanBuilder.scala`、`ArrowCSVTable.scala`、`ArrowCSVPartitionReaderFactory.scala`。
    - 挑战: 这些类目前直接依赖 `RowToVeloxColumnarExec` / `RowToBoltColumnarExec` 以及各自的 `VeloxConfig` / `BoltConfig`，属于后端特定逻辑。
    - 建议路线:
      1. 在 `backends-common` 中抽象一个面向 Arrow 的通用 Row->Columnar 转换帮助类（基于 `NativeRowToColumnarJniWrapper` 和 `BackendsApiManager`），从现有 `RowToVeloxColumnarExec` / `RowToBoltColumnarExec` 中提炼共有实现；
      2. 将 `veloxPreferredBatchBytes` / `boltPreferredBatchBytes` 抽象到 `BackendSettingsApi` / `CommonBackendSettingsApi` 中，由各后端实现；
      3. 在完成上述抽象后，再将 CSV datasource 的主要类迁移到 `backends-common`，并用新的通用转换入口替代当前的后端特定调用。

2.  **FS 与其他 metrics 衍生组件的抽取**
    - 对 `fs` 目录下的通用文件系统工具类（如 `JniFilesystem` / `OnHeapFileSystem`）进行一次 `identical` 验证后，整体迁移到 `backends-common`；
    - 对 Hudi/Paimon 等 connector 的 metrics/FS 相关辅助类，按照 `extract_candidates.csv` 中 `identical` / `renamed` 的优先级，分批迁移。

后续所有步骤仍将遵循“小步抽取 + 频繁验证”的原则：每一批抽取都需跑通 `mvn spotless:apply`，并在条件允许的环境中执行一次 `mvn -DskipTests package` 或完整 `make jar` 来验证编译。
