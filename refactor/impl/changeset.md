# 变更汇总：抽取 backends-common

本文档记录了为抽取 `backends-common` 模块所做的代码变更，旨在提升 Gluten 项目在多后端架构下的代码复用率和可维护性。

## 1. 新增模块与文件

### 1.1. 新增 `backends-common` 模块

- **路径**: `gluten/backends-common/`
- **说明**: 创建了新的 Maven 模块 `backends-common`，用于存放 `backends-velox` 和 `backends-bolt` 两个后端之间的公共代码。

### 1.2. 新增公共代码文件

- **`gluten/backends-common/pom.xml`**:
  - **说明**: `backends-common` 模块的构建配置文件，定义了其依赖（如 `gluten-core`、`gluten-substrait`）和编译设置。
- **`gluten/backends-common/src/main/scala/org/apache/gluten/backendsapi/CommonBackendSettingsApi.scala`**:
  - **说明**: 抽取了 `VeloxBackendSettings` 和 `BoltBackendSettings` 中绝大部分相同的实现，形成一个可复用的 `trait`。此文件包含了与原生执行、表达式转换、Join/Aggregate/Window/Shuffle 支持、序列化、内存管理等相关的通用后端配置与能力声明。

## 2. 修改的文件

### 2.1. 构建配置调整

- **`gluten/pom.xml`**:
  - **修改点**: 在 `backends-velox` 和 `backends-bolt` 的 profile 中，添加了对新模块 `backends-common` 的引用，确保其在激活相应后端时被正确加载和编译。
- **`gluten/backends-velox/pom.xml`**:
  - **修改点**: 添加了对 `backends-common` 模块的编译时依赖，使其能够引用公共代码。
- **`gluten/backends-bolt/pom.xml`**:
  - **修改点**: 同上，为 `backends-bolt` 添加了对 `backends-common` 模块的编译时依赖。

### 2.2. 后端 API 实现调整

- **`gluten/backends-velox/src/main/scala/org/apache/gluten/backendsapi/velox/VeloxBackend.scala`**:
  - **修改点**:
    - `VeloxBackendSettings` 对象继承自新增的 `CommonBackendSettingsApi` 而非原来的 `BackendSettingsApi`。
    - 删除了所有已在 `CommonBackendSettingsApi` 中实现的通用方法，仅保留了 Velox 特有的差异化实现，例如：
      - `validateScanExec`: 针对 Velox 的文件系统（`VeloxFileSystemValidationJniWrapper`）和 ORC 扫描配置（`VeloxConfig.get.veloxOrcScanEnabled`）进行验证。
      - `supportHashBuildJoinTypeOnLeft`/`supportHashBuildJoinTypeOnRight`: 保留了 Velox 特有的 Join 类型支持逻辑和相关注释。
      - `enableEnhancedFeatures`: 使用 `VeloxConfig.get.enableEnhancedFeatures()` 进行判断。
- **`gluten/backends-bolt/src/main/scala/org/apache/gluten/backendsapi/bolt/BoltBackend.scala`**:
  - **修改点**:
    - `BoltBackendSettings` 对象同样继承自 `CommonBackendSettingsApi`。
    - 删除了与 `VeloxBackendSettings` 相同的冗余代码，仅保留 Bolt 特有的实现：
      - `validateScanExec`: 使用 `BoltFileSystemValidationJniWrapper` 和 `BoltConfig.get.boltOrcScanEnabled`。
      - `enableEnhancedFeatures`: 使用 `BoltConfig.get.enableEnhancedFeatures()`。

### 2.3. 其他代码格式化

- **`gluten/gluten-core/pom.xml`**:
  - **修改点**: 运行 `mvn spotless:apply` 后，对 `pom.xml` 文件进行了格式化。
- **`gluten/package/pom.xml`**:
  - **修改点**: 同上，`pom.xml` 文件被格式化。

## 3. 删除的文件

本次重构及后续补充抽取过程删除了少量“定义重复”的文件，避免与 `backends-common` 中的公共实现产生冲突：

- **`gluten/backends-velox/src/main/scala/org/apache/gluten/spi/SharedLibraryLoader.scala`**
  - 原为 Velox 后端私有的 `SharedLibraryLoader` 接口定义，方法签名依赖 `JniLibLoader`。
  - 现已统一由 `gluten/backends-common/src/main/scala/org/apache/gluten/spi/SharedLibraryLoader.scala` 提供公共接口（参数类型为 `AnyRef`），因此移除重复定义。
- **`gluten/backends-bolt/src/main/scala/org/apache/gluten/spi/SharedLibraryLoader.scala`**
  - 原为 Bolt 后端私有的 `SharedLibraryLoader` 接口定义，方法签名依赖 `BoltJniLibLoader`。
  - 同样统一迁移至 `backends-common` 后删除，避免多份同名 trait 并存造成类型冲突。
- **`gluten/backends-velox/src/main/resources/META-INF/services/org.apache.gluten.spi.SharedLibraryLoader`**
- **`gluten/backends-bolt/src/main/resources/META-INF/services/org.apache.gluten.spi.SharedLibraryLoader`**
  - 两个后端原本各自维护一份 ServiceLoader 声明，现在统一由
    `gluten/backends-common/src/main/resources/META-INF/services/org.apache.gluten.spi.SharedLibraryLoader`
    承担，避免重复声明导致 `ServiceLoader` 发现多于一个候选实现。

## 4. 总结

通过本次重构，成功将 `backends-velox` 和 `backends-bolt` 中大量的重复逻辑抽取到 `backends-common` 模块。这显著减少了代码冗余，使得后端特定实现更加清晰，未来新增后端或维护现有后端将变得更加高效。所有改动均在 Maven 构建体系内完成，未引入新的构建工具或破坏现有流程。

## 5. 补充抽取：SharedLibraryLoader 公共化与后端适配

在原有 `CommonBackendSettingsApi` 抽取的基础上，本轮进一步围绕 `SharedLibraryLoader` 能力完成了以下公共化与适配调整：

1. **统一 SharedLibraryLoader 接口到 backends-common**
   - **文件**: `gluten/backends-common/src/main/scala/org/apache/gluten/spi/SharedLibraryLoader.scala`
   - **说明**:
     - 将原先在 Velox/Bolt 各自模块中定义的 `SharedLibraryLoader` 接口上移到 `backends-common`，形成统一的 SPI：
       - 方法签名调整为 `def loadLib(loader: AnyRef): Unit`，屏蔽后端对 `JniLibLoader` / `BoltJniLibLoader` 的差异。
       - 文档化了接口的使用方式：通过 `ServiceLoader` 发现实现，并按 `accepts(osName, osVersion)` 进行选择。

2. **OS 级 SharedLibraryLoader 实现改造（Velox / Bolt 保留薄适配层）**
   - **涉及文件（示例）**：
     - `gluten/backends-velox/src/main/scala/org/apache/gluten/spi/SharedLibraryLoaderCentos7.scala`
     - `gluten/backends-velox/src/main/scala/org/apache/gluten/spi/SharedLibraryLoaderUbuntu2004.scala`
     - `gluten/backends-velox/src/main/scala/org/apache/gluten/spi/SharedLibraryLoaderDebian11.scala`
     - `gluten/backends-velox/src/main/scala/org/apache/gluten/spi/SharedLibraryLoaderDebian12.scala`
     - `gluten/backends-velox/src/main/scala/org/apache/gluten/spi/SharedLibraryLoaderOpenEuler2403.scala`
     - 以及对应的 Bolt 版本：`SharedLibraryLoader*`（Centos7/8/9、Debian11/12、Ubuntu2004/2204、OpenEuler2403、MacOS）。
   - **修改点**：
     - 为每个实现类引入私有的 `doLoad(loader: JniLibLoader | BoltJniLibLoader)` 方法，保留原有按 OS 加载具体 `.so` 的逻辑不变。
     - 新增统一签名的 `override def loadLib(loader: AnyRef): Unit`，通过模式匹配将 `AnyRef` 安全下转型到具体的 `JniLibLoader` / `BoltJniLibLoader`：
       - 成功匹配时调用 `doLoad(...)` 执行原有加载序列；
       - 未匹配时抛出 `GlutenException`，给出清晰的 loader 类型错误提示，便于排错。
     - 这样既满足了 `backends-common` 中统一接口的要求，又保证原有 per-OS 依赖库列表逻辑完全保留在各自后端模块中，侵入性极小。

3. **统一 META-INF/services 声明到 backends-common**
   - **新增文件**:
     - `gluten/backends-common/src/main/resources/META-INF/services/org.apache.gluten.spi.SharedLibraryLoader`
   - **说明**:
     - 将原本分散在 `backends-velox` 和 `backends-bolt` 中的 ServiceLoader 声明整合到 `backends-common`：
       - 统一维护一份候选实现列表：
         - `SharedLibraryLoaderCentos7/8/9`
         - `SharedLibraryLoaderDebian11/12`
         - `SharedLibraryLoaderUbuntu2004/2204`
         - `SharedLibraryLoaderOpenEuler2403`
         - `SharedLibraryLoaderMacOS`
       - 避免多个模块同时声明导致 `ServiceLoader` 得到“重复候选”，从而触发 `SharedLibraryLoaderUtils` 中的“多于一个实现”保护性异常。

通过这一轮补充变更，`SharedLibraryLoader` 相关能力已经真正收敛到 `backends-common` 的公共 SPI 层，而 Velox / Bolt 保留了 OS 特定的动态库列表和加载细节，实现了接口统一与实现差异并存，为后续将更多 JNI/FS/UDF 相关封装迁移到 common 打下基础。

### Round 2: 抽取 CSV/Metrics/UDF 相关公共类

#### 新增文件

- `gluten/backends-common/src/main/java/org/apache/gluten/metrics/Metrics.java`: (迁移自 `backends-velox`)
  - **理由**: `Metrics` DTO 在两个后端实现几乎一致（仅 `veloxToArrow` vs `boltToArrow` 命名差异），遵循“保留 velox 命名”规则，迁移至 common。
- `gluten/backends-common/src/main/java/org/apache/gluten/metrics/OperatorMetrics.java`: (迁移自 `backends-velox`)
  - **理由**: `OperatorMetrics` DTO 在两个后端实现完全一致 (identical)，迁移至 common。
- `gluten/backends-common/src/main/scala/org/apache/gluten/datasource/ArrowCSVOptionConverter.scala`: (迁移自 `backends-velox`)
  - **理由**: CSV 功能的选项转换工具类，两后端实现一致 (identical)，属于纯函数工具，安全迁移。
- `gluten/backends-common/src/main/scala/org/apache/gluten/metrics/MetricsUtil.scala`: (迁移自 `backends-velox`，并改为使用 `BackendsApiManager.getBackendName` 动态拼接任务名)
  - **理由**: 指标工具类实现几乎一致 (renamed)，将硬编码后端名抽象为动态获取后迁移。
- `gluten/backends-common/src/main/scala/org/apache/gluten/metrics/HashAggregateMetricsUpdater.scala`: (新增)
  - **理由**: 为 `HashAggregateMetricsUpdaterImpl` 抽取公共 `trait`，该文件在两后端有轻微实现差异，因此在 common 中只定义接口。
- `gluten/backends-common/src/main/scala/org/apache/gluten/metrics/BatchScanMetricsUpdater.scala` 等 15 个 identical 的 metrics updater 实现: (迁移自 `backends-velox`)
  - **理由**: 这些 updater 实现完全一致，直接迁移到 common，由所有后端复用。

#### 修改文件

- `gluten/backends-velox/src/main/scala/org/apache/gluten/backendsapi/velox/VeloxMetricsApi.scala`:
  - **变更**: import 改为 `import org.apache.gluten.metrics.{MetricsUtil, _}`，并在 `genHashAggregateTransformerMetricsUpdater` 中实例化 `new HashAggregateMetricsUpdater(metrics)`。
  - **理由**: 使其依赖 `backends-common` 中的公共 metrics 工具和 trait。
- `gluten/backends-bolt/src/main/scala/org/apache/gluten/backendsapi/bolt/BoltMetricsApi.scala`:
  - **变更**: 与 Velox 版本一致，改为使用 `MetricsUtil` 和 `HashAggregateMetricsUpdater`。
- `gluten/backends-velox/src/main/scala/org/apache/gluten/metrics/HashAggregateMetricsUpdater.scala`:
  - **变更**: 保留 `HashAggregateMetricsUpdaterImpl` 的实现，但移除本地 trait 定义，改为继承 common 中的 `HashAggregateMetricsUpdater`。
- `gluten/backends-bolt/src/main/scala/org/apache/gluten/metrics/HashAggregateMetricsUpdater.scala`:
  - **变更**: 同上，继承 common 中的 trait，保留 Bolt 侧实现差异。

#### 删除文件

- `gluten/backends-velox/src/main/java/org/apache/gluten/metrics/Metrics.java`
- `gluten/backends-velox/src/main/java/org/apache/gluten/metrics/OperatorMetrics.java`
- `gluten/backends-bolt/src/main/java/org/apache/gluten/metrics/Metrics.java`
- `gluten/backends-bolt/src/main/java/org/apache/gluten/metrics/OperatorMetrics.java`
- `gluten/backends-velox/src/main/scala/org/apache/gluten/datasource/ArrowCSVOptionConverter.scala`
- `gluten/backends-bolt/src/main/scala/org/apache/gluten/datasource/ArrowCSVOptionConverter.scala`
- `gluten/backends-velox/src/main/scala/org/apache/gluten/metrics/MetricsUtil.scala`
- `gluten/backends-bolt/src/main/scala/org/apache/gluten/metrics/MetricsUtil.scala`
- `gluten/backends-velox/src/main/scala/org/apache/gluten/metrics/BatchScanMetricsUpdater.scala` 及其他 14 个 updater 的 velox 版本
- `gluten/backends-bolt/src/main/scala/org/apache/gluten/metrics/BatchScanMetricsUpdater.scala` 及其他 14 个 updater 的 bolt 版本
  - **理由**: 以上文件均已迁移至 `backends-common` 模块，删除原位置的重复实现，避免类冲突。
