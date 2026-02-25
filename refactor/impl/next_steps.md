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

## 最新进展与后续计划 (Round 3)

**最新进展**:

第三轮重构已完成，主要针对 `VeloxColumnarBatches` / `BoltColumnarBatches` 以及 `ColumnarBatchSerializerInstance` 进行了抽取：

1.  **`*ColumnarBatches` 抽取**:
    - 创建了 `backends-common/src/main/java/org/apache/gluten/columnarbatch/BackendColumnarBatchesBase.java`，承载了 `VeloxColumnarBatches` 和 `BoltColumnarBatches` 之间几乎全部的 Java 逻辑。
    - Velox/Bolt 两侧的实现被重构为该基类的子类，仅保留后端特定的 JNI 调用、类型字符串和 action name，极大地减少了代码重复。
    - 所有对外静态 API 保持不变，对调用方透明。

2.  **`ColumnarBatchSerializerInstance` 抽取**:
    - 将在两个后端中完全相同的 `ColumnarBatchSerializerInstance.scala` 抽象类迁移到了 `backends-common`。
    - 原后端模块中的文件仅保留 package 声明和注释，避免了 FQN 冲突，同时确保了 `spotless` 格式化可通过。

**后续计划**:

基于 `extract_candidates.csv` 和已有的分析，后续可抽取的“仅注释/命名差异”类还有很多。以下是建议的下一步清单：

1.  **`ColumnarToRowExec` 系列 (Velox/Bolt)**
    - **文件**: `VeloxColumnarToRowExec.scala` / `BoltColumnarToRowExec.scala`
    - **分析**: 两者实现几乎完全一致，仅在 `checkVeloxBatch`/`checkBoltBatch` 调用、`BroadcastUtils.veloxToSparkUnsafe`/`boltToSparkUnsafe` 调用以及日志/类名中存在 "Velox" vs "Bolt" 的差异。
    - **策略**:
      - 创建 `backends-common/src/main/scala/org/apache/gluten/execution/CommonColumnarToRowExec.scala`，定义一个包含绝大部分通用逻辑的 `trait` 或基类。
      - 后端差异（如 `checkBackendBatch` 和 `broadcastUtilsToSparkUnsafe`）可通过 `BackendsApiManager` 注入，或者由子类覆盖。
      - Velox/Bolt 各自的 `*ColumnarToRowExec` 继承该公共基类，仅保留少量差异代码。

2.  **`ArrowColumnarTo*ColumnarExec` 系列**
    - **文件**: `ArrowColumnarToVeloxColumnarExec.scala` / `ArrowColumnarToBoltColumnarExec.scala`
    - **分析**: 实现极度相似，仅在 `VeloxBatchType`/`BoltBatchType` 和 `VeloxColumnarBatches.toVeloxBatch`/`BoltColumnarBatches.toBoltBatch` 上有区别。
    - **策略**:
      - 创建一个 `backends-common` 的 `ArrowColumnarToBackendColumnarExec` 基类，将 `toBackendBatch` 方法抽象化，由子类提供。
      - Velox/Bolt 子类继承并传入各自的 `BatchType` 和 `toBackendBatch` 实现。

3.  **`ColumnarCollectLimitExec` / `ColumnarCollectTailExec`**
    - **文件**: Velox/Bolt 版本的 `ColumnarCollectLimitExec.scala` 和 `ColumnarCollectTailExec.scala`。
    - **分析**: 几乎完全相同，仅在 `VeloxColumnarBatches.slice`/`BoltColumnarBatches.slice` 调用上有差异。
    - **策略**:
      - `VeloxColumnarBatches.slice` 和 `BoltColumnarBatches.slice` 的 API 和行为已经统一，可以直接将其中一个（如 Velox 版本）的实现迁移到 `backends-common` 中，作为 `CommonCollectLimitExec`。
      - 然后让 Velox/Bolt 的实现直接继承或复用 `CommonCollectLimitExec`。

4.  **`*ColumnarBatchJniWrapper`**
    - **文件**: `VeloxColumnarBatchJniWrapper.java` / `BoltColumnarBatchJniWrapper.java`
    - **分析**: 两个文件的方法签名完全一致，仅类名不同。
    - **策略**:
      - 在 `backends-common` 中创建 `IColumnarBatchJniWrapper` 接口，定义 `from`, `compose`, `slice`, `repeatedThenCompose` 等 `native` 方法。
      - `VeloxColumnarBatchJniWrapper` 和 `BoltColumnarBatchJniWrapper` 实现该接口。
      - `BackendColumnarBatchesBase` 中的 `fromNative` 等抽象方法可以改为接受 `IColumnarBatchJniWrapper` 实例，由后端在 `getInstance` 时注入。但由于 JNI 方法的 `native` 关键字不能在接口中定义，此方案需要调整。一个更简单的方式是，暂时保持现状，认识到这两个文件在结构上是统一的，只是绑定了不同的 C++ 实现。

后续的抽取工作应继续遵循“识别公共模式 -> 抽取基类/Trait -> 后端继承/实现差异”的模式，稳步推进。
