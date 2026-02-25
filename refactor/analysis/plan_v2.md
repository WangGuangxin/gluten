# backends-velox / backends-bolt 公共候选抽取建议（v2）

本轮基于工作区当前分支的实际代码，对 `backends-velox` 与 `backends-bolt` 做了逐文件对比：

- 按路径前缀替换规则，将每个 velox 文件 `backends-velox/...` 映射到 bolt 文件 `backends-bolt/...`；
- 对存在对应文件的 pair，按以下规则分类：
  - 内容完全一致 → `identical`；
  - 将 `Velox`/`Bolt`（含大小写变体）以及 `backends-velox`/`backends-bolt` 归一化后内容一致 → `renamed`；
  - 归一化后使用 `SequenceMatcher.ratio()` 相似度 ≥ 0.97 → `minor-modified`；
  - 其余视为 `substantial-modified`，不计入本次抽取清单；
- 仅将 `identical` / `renamed` / `minor-modified` 三类写入 `extract_candidates.csv`；
- `keep_velox_name` 列：
  - `renamed` 视为仅命名/包路径差异，统一标记为 `yes`（建议保留 velox 命名抽取到 common）；
  - `identical` / `minor-modified` 标记为 `no`（需要按后续人工 review 结合语义再决定是否保留 velox 叫法）。

组件初步聚类统计（只统计已进入 `extract_candidates.csv` 的文件）：

- `fs`：5 个 identical；
- `metrics`：16 个 identical，4 个 renamed，1 个 minor-modified；
- `udf`：3 个 identical，2 个 renamed，1 个 minor-modified；
- `csv_datasource`：13 个 identical，2 个 renamed；
- `extension_rules`：12 个 identical，8 个 renamed，2 个 minor-modified；
- `connector_iceberg`：48 个 identical，3 个 renamed；
- `connector_delta`：11 个 identical；
- `test_or_benchmark`：63 个 identical，680 个 renamed，5 个 minor-modified；
- 其他（尚未细分到具体组件类目）：`other` 共 46 个（19 identical / 11 renamed / 16 minor-modified）。

下文按组件分组给出抽取建议与风险点。

---

## 1. 文件系统（FS）

**代表路径模式（velox 端）：**
- `backends-velox/.../filesystem/...`
- 以及部分 `file/` 命名的工具类

**统计概览：**
- 5 个 `identical`，无 `renamed` / `minor-modified`。

**抽取建议：**
- 这部分文件在 velox/bolt 完全一致，且 FS 抽象本身偏通用（上层只关心 Hadoop/S3 等逻辑），优先抽取到 `backends-common`：
  - 建议以 `org.apache.gluten.fs` 或紧邻现有包名的形式集中；
  - velox/bolt 后端保留的差异点集中在 JNI 校验与 native 侧实现上，而非 Scala FS 封装层。

**风险点：**
- 需要确认是否有通过类全名（FS 实现类名）反射创建的调用路径；若有，需要在抽取时保留原 FQN 不变，或通过后端薄适配层转发；
- 注意与 C++/JNI 层对接的类（若包含 `JniFilesystem` 之类）不要在第一次抽取时整体迁移，可先抽取明显无 native 依赖的辅助类。

---

## 2. 指标（metrics）

**代表路径模式（velox 端）：**
- `.../metrics/...`
- 各类 `*MetricsUpdater` 工具类

**统计概览：**
- 16 个 `identical`，4 个 `renamed`，1 个 `minor-modified`。

**抽取建议：**
- `identical` + `renamed` 文件可以整体视为公共指标封装层，抽取到 `backends-common`：
  - 建议包名与现有 `metrics` 包保持一致（保留 velox 叫法时，可以在 common 中仍使用 velox 样式的类名）；
  - 抽取时优先处理无明显后端判断分支的工具类、累加器类。
- 单个 `minor-modified` 文件需要重点 review 差异：
  - 若差异与 log 文案或注释相关，可视为非语义差异，按公共实现抽取；
  - 若包含针对不同后端的条件分支，建议将分支拆分到后端适配层，只在 common 中保留与 Spark/Gluten 指标模型相关的通用逻辑。

**风险点：**
- 指标上报链路中如果存在 hard-coded 的 backend 名称（如用于 Prometheus 标签），抽取后应确保标签值仍能区分 velox/bolt；
- 需要排查是否有通过 `ServiceLoader` 或反射注册的指标收集器类。

---

## 3. UDF 封装

**代表路径模式：**
- 包含 `udf`/`Udf` 关键字的类

**统计概览：**
- 3 个 `identical`，2 个 `renamed`，1 个 `minor-modified`。

**抽取建议：**
- 作为通用 UDF 封装的候选：
  - `identical` + `renamed` 文件建议整体抽取到 `backends-common`，保留 velox 的命名（类名与包路径中涉及 velox 的部分）；
  - 针对 `minor-modified` 文件，建议检查差异是否仅在 UDF 库路径或 loader 名称：
    - 如果是路径/常量差异，可通过参数或后端适配层解耦，公共层保留逻辑；
    - 如涉及不同 JNI entry 或 native 签名，则此类文件暂不统一抽取，只提炼公共部分。

**风险点：**
- UDF 注册往往依赖 `Metas` 或 `FunctionRegistry`，抽取过程中要避免破坏注册顺序或命名；
- UDF native 库名称可能在 Scala 中写死（如 `libvelox_udf.so` vs `libbolt_udf.so`），需要在抽取时通过配置/后端适配隔离，不要放在 common 的硬编码中。

---

## 4. CSV datasource（Arrow CSV 等）

**代表路径模式：**
- 包含 `csv`/`CSV` 的 datasource 或 scan 类（如 `ArrowCSVFileFormat`、`ArrowCSVScan*` 等）

**统计概览：**
- 13 个 `identical`，2 个 `renamed`，无 `minor-modified`。

**抽取建议：**
- 这部分是非常典型的“逻辑完全一致，只是后端命名不同”的区域：
  - 建议整体抽取到 `backends-common`，保留 velox 的命名与包结构；
  - velox/bolt 端只保留最薄的一层对 common 的调用（例如在 backend 注册处引用 common 的 CSV datasource 实现）。

**风险点：**
- 需要确认 CSV datasource 是否在某些地方显式引用特定 backend 的 config 或 metrics；
- 在 Spark 的 datasource 注册机制中，如果存在通过 FQN 反射创建 CSV datasource 的逻辑，抽取后要保证该 FQN 不变。

---

## 5. Extension rules（计划/规则重写）

**代表路径模式：**
- 包路径/文件名中包含 `extension` / `rule` / `rules` 的类

**统计概览：**
- 12 个 `identical`，8 个 `renamed`，2 个 `minor-modified`。

**抽取建议：**
- 这部分通常是 Spark 计划重写规则，与具体后端引擎关系较弱，是优先抽取的公共层：
  - `identical` + `renamed` 文件建议整体迁移到 `backends-common` 的某个统一包（例如 `org.apache.gluten.extension`）；
  - `minor-modified` 的 2 个文件，需要检查差异：
    - 若差异集中在 log 描述、注释或 backend 名称，可以统一为 velox 命名并抽取到 common；
    - 若涉及是否开启某些规则的开关逻辑，可以将开关控制交给后端配置，规则实现本身放在 common。

**风险点：**
- 规则类往往使用 `SparkSessionExtensions` 或 `Rule[SparkPlan]` 注册，抽取后注册位置与顺序应保持稳定；
- 若某些规则实际上依赖特定后端的表达式实现（例如仅 velox 支持某些算子），需要在抽取前明确这些规则是否真的可复用。

---

## 6. Connectors

### 6.1 Iceberg

**代表路径模式：**
- 包含 `iceberg` 的 connector 相关类

**统计概览：**
- 48 个 `identical`，3 个 `renamed`，无 `minor-modified`。

**抽取建议：**
- Iceberg connector 在 velox/bolt 之间几乎完全一致，是构建 `backends-common` connector 层的核心候选：
  - 将写通道（`AbstractIcebergWriteExec`、`IcebergDataWriteFactory`、`IcebergColumnarBatchDataWriter` 等）和读通道的共同实现迁移到 `backends-common`；
  - velox/bolt 只保留与底层原生执行引擎相关的 bridge 层（如 JNI 调用、Scan/Exec 的后端特定实现）。

**风险点：**
- 需要确认 Iceberg 对象模型中是否有类型直接依赖 velox/bolt 的 native 扩展类型；
- Iceberg connector 的测试通常较重，建议将其测试基类与 util 一并抽取到 common 的 test 源集中管理。

### 6.2 Delta

**代表路径模式：**
- 包含 `delta` 的 connector 类

**统计概览：**
- 11 个 `identical`，无 `renamed` / `minor-modified`。

**抽取建议：**
- 抽取策略与 Iceberg 相似：
  - 以公共 Delta 逻辑为核心，迁移到 `backends-common`；
  - 后端侧仅保留差异较大的 commit/log/scan bridge 类。

**风险点：**
- Delta 在事务日志处理上依赖精确语义，统一过程中要特别注意是否引入 backend 特定行为差异。

### 6.3 Hudi / Paimon

**说明：**
- 目前统计中 `connector_hudi` / `connector_paimon` 计数不高（甚至为 0），意味着这两类 connector 要么尚未按路径关键字聚类出来，要么文件数量较少。

**建议：**
- 对 hudi/paimon 相关文件做一次人工过一遍：
  - 若模式与 Iceberg/Delta 类似（高度一致），可按相同方式抽取到 `backends-common`；
  - 若差异较多，可先只抽取测试基类和 util，按“小步抽取”的原则推进。

---

## 7. 测试与基准（test_or_benchmark）

**统计概览：**
- 63 个 `identical`，680 个 `renamed`，5 个 `minor-modified`。

**抽取建议：**
- 这一块是数量最大、同时也是最适合集中管理的区域：
  - 将公共的 UT/IT 基类、fuzzer、TPCH/TPCDS 计划与数据驱动测试迁移到 `backends-common` 的 test scope；
  - velox/bolt 仅保留小部分 backend 专属的稳定性/回归用例。
- 鉴于数量较大，建议分阶段实施：
  1. 先抽取明确 identical + renamed 的基础设施类和 helper；
  2. 再逐步针对 minor-modified 文件做语义 review，并按需要拆分差异到后端适配层。

**风险点：**
- 测试常依赖具体模块的 pom/test-jar 依赖，抽取后要确保依赖图仍然闭合；
- 路径变更可能影响测试数据加载（相对路径、resource 路径），抽取时注意保持数据文件结构不变或提供兼容路径。

---

## 8. 其他（other）

**统计概览：**
- 19 个 `identical`，11 个 `renamed`，16 个 `minor-modified`。

**说明与建议：**
- 这类文件尚未通过简单路径关键字归到某个具体组件，可能包含：
  - 通用工具类（utils）、配置类、部分尚未命名规范化的算子/执行逻辑；
  - 「两边几乎一致但带少量业务差异」的实现。
- 建议做一次有针对性的人工梳理：
  - 先从 `renamed` 文件入手，这些按规则可直接视为“仅命名差异”，优先抽取并保留 velox 命名；
  - 对 `minor-modified` 文件，结合具体 diff 判断是否可以：
    - 抽取公共部分到 common；
    - 将差异部分下沉到 velox/bolt 适配层（例如通过 trait + 两个实现类）。

**风险点：**
- “other” 类目中可能混入对外暴露接口或关键配置类，抽取时要保持对外 API 不变，避免破坏向后兼容性。

---

## 9. 资源 / META-INF 层面的注意事项

在本轮扫描中，资源文件（尤其是 `META-INF/services`）同样参与了内容比对与分类；在后续抽取中需要重点关注：

- ServiceLoader：
  - `META-INF/services/org.apache.gluten.spi.SharedLibraryLoader` 已在现有重构中统一到 `backends-common`；
  - 对其它 SPI（如 UDF 注册、connector SPI）也适用类似模式：公共接口与默认实现放在 common，后端仅保留必要的扩展实现和声明。
- 反射：
  - 对于通过 `Class.forName` 或 Spark 插件机制加载的类，抽取时要优先保证类的 FQN 不变；
  - 如需变更包路径，应在后端适配层提供兼容别名或转发。

---

## 10. 实施顺序建议

结合本次分类结果，推荐的抽取顺序（从风险最低到最高）：

1. **测试与基准基础设施（test_or_benchmark 中的 identical/renamed 辅助类）**；
2. **CSV datasource（csv_datasource 全部）**；
3. **extension rules 中的 identical/renamed**；
4. **metrics / fs / udf 中的 identical 与明显仅命名差异的 renamed**；
5. **Iceberg / Delta connectors 的公共逻辑**；
6. **对 minor-modified 和 other 类目做精细化 review 后的增量抽取。**

在所有步骤中，继续遵循“若唯一区别是 velox/bolt 叫法或包路径中的 velox/bolt 替换，则保留 velox 命名放入 backends-common”的规则，必要时通过后端适配层或配置参数承载真正的差异，以控制风险并便于回滚。