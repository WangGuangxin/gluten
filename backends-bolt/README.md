# Gluten Bolt 后端

## 架构概览

Bolt 是字节跳动内部的 Velox 派生引擎。在 JVM 侧，Bolt 与 Velox 完全二进制兼容：
共享 JNI 入口、`SubstraitBackend` 契约、`VeloxListenerApi`/`VeloxIteratorApi` 等
所有 *Api 实现，差异仅在于 backend 名称与对应的原生库。

```
gluten-substrait
        ▲
        │ implements
backends-velox / VeloxLikeBackend (abstract)
        ▲                           ▲
        │ extends                   │ extends
   VeloxBackend (name="velox")  BoltBackend (name="bolt")
   libvelox.so                  libbolt.so
```

* `backends-velox/.../velox/VeloxBackend.scala` 抽离出 `abstract class VeloxLikeBackend`；
  `VeloxBackend` 只重写 `name() = "velox"`，`BoltBackend` 只重写 `name() = "bolt"`。
* `VeloxListenerApi(backendName: String)` 会执行：
  * `System.loadLibrary` → `System.mapLibraryName("bolt")` 即加载 `libbolt.so`；
  * `NativeBackendInitializer.forBackend("bolt").initialize(...)`，进而调用
    libbolt.so 中导出的 `Java_org_apache_gluten_init_NativeBackendInitializer_initialize`。
* SPI 发现机制由 `gluten-core` 的 `Discovery` 完成，扫描 classpath 中所有
  `META-INF/gluten-components/<className>` 文件；`backends-bolt` 的 SPI 标记位于
  [`src/main/resources/META-INF/gluten-components/org.apache.gluten.backendsapi.bolt.BoltBackend`](src/main/resources/META-INF/gluten-components/org.apache.gluten.backendsapi.bolt.BoltBackend)。
* 表格式/远程 shuffle 适配（iceberg/delta/hudi/paimon/celeborn/uniffle）通过
  `backends-bolt/pom.xml` 的 `profile + build-helper-maven-plugin add-source` 直接
  复用 `backends-velox/src-<format>/main/scala` 目录，**零代码拷贝**。

## 源码布局

| 路径                                                                           | 说明 |
| ------------------------------------------------------------------------------ | ---- |
| `backends-bolt/pom.xml`                                                         | 仅依赖 `backends-velox`；通过 profile 复用 velox 的 src-* 源码目录 |
| `backends-bolt/src/main/scala/.../bolt/BoltBackend.scala`                       | `extends VeloxLikeBackend`；只覆写 `name()` |
| `backends-bolt/src/main/resources/META-INF/gluten-components/...BoltBackend`    | SPI 标记，使 `Component.sorted()` 能发现 Bolt 后端 |
| `cpp/bolt/CMakeLists.txt`                                                       | 受 `BUILD_BOLT_BACKEND` 控制，默认 `OFF`，编译产出 `libbolt.so` |
| `cpp/bolt/compute/BoltBackend.cc`                                               | 原生注册脚手架，按 "bolt" 注册 `Runtime`/`MemoryManager`/`ThreadManager` 工厂；工厂体目前 throw，待接入字节内部 Bolt 引擎 |

## 构建命令

### 1. 原生（可选；脚手架）

> ⚠️ Bolt 的专有引擎源码并未包含在本仓库；`cpp/bolt` 仅是 *honest* 脚手架，
> 用于演示注册契约。默认 `BUILD_BOLT_BACKEND=OFF`，不影响 velox 主线构建。

打开开关后会同时构建 `libvelox.so` 与 `libbolt.so`：

```bash
./dev/builddeps-veloxbe.sh --build_tests=OFF \
  -- -DBUILD_VELOX_BACKEND=ON -DBUILD_BOLT_BACKEND=ON
```

或直接调用 CMake：

```bash
cmake -S cpp -B cpp/build -DBUILD_VELOX_BACKEND=ON -DBUILD_BOLT_BACKEND=ON
cmake --build cpp/build --target bolt -j
```

构建产物输出在 `cpp/build/releases/libbolt.so`，与 `libvelox.so` 同目录。

### 2. JVM 侧

```bash
# 必须与 backends-velox 一起激活：bolt 模块依赖 backends-velox 编译产物
./build/mvn install \
  -pl gluten-core,gluten-substrait,gluten-arrow,backends-velox,backends-bolt -am \
  -Pbackends-velox -Pbackends-bolt \
  -Pspark-3.5 -Pscala-2.12 \
  -DskipTests
```

附加表格式/远程 shuffle profile 时，与 velox 的用法完全一致：

```bash
./build/mvn package \
  -pl backends-bolt -am \
  -Pbackends-velox -Pbackends-bolt \
  -Pspark-3.5 -Pscala-2.12 \
  -Piceberg -Pdelta -Pceleborn \
  -DskipTests
```

### 3. UT

```bash
./build/mvn test -pl gluten-ut -am \
  -Pspark-ut -Pbackends-bolt \
  -Pspark-3.5 -Pscala-2.12
```

`gluten-ut/spark35/pom.xml` 与 `gluten-ut/test/pom.xml` 都新增了 `backends-bolt`
profile，复用 `src/test/backends-velox` 测试源码，仅替换后端依赖。

## 运行时 Spark 配置

Bolt 的后端选择**完全通过 classpath 上的 SPI 标记决定**（参见
`gluten-core` 的 `Discovery` 与 `BackendsApiManager`，后者断言 classpath
上**有且仅有一个** `SubstraitBackend` 实例）。因此使用 Bolt 时，运行任务的
jar 包中应当包含 `backends-bolt` 而**不包含** `backends-velox`（或反之）。

| 配置项                                | 含义                              | Bolt 推荐值 |
| ------------------------------------- | --------------------------------- | ----------- |
| `spark.gluten.sql.columnar.libname`   | 跨平台库基名（首先加载）          | `gluten`（默认） |
| `spark.gluten.sql.columnar.libpath`   | 直接以全路径加载 backend lib，绕过 `libname` 解析 | `/path/to/libbolt.so` |
| `spark.gluten.sql.columnar.executor.libpath` | Executor 端的 libpath 覆盖 | 同上 |

backend lib 的解析逻辑见
[`VeloxListenerApi.scala`](../backends-velox/src/main/scala/org/apache/gluten/backendsapi/velox/VeloxListenerApi.scala)
第 ~228 行：

```scala
val libPath = conf.get(GlutenConfig.GLUTEN_LIB_PATH)
if (StringUtils.isBlank(libPath)) {
  val baseLibName = conf.get(GlutenConfig.GLUTEN_LIB_NAME)
  loader.load(s"$platformLibDir/${System.mapLibraryName(baseLibName)}") // libgluten.so
  loader.load(s"$platformLibDir/${System.mapLibraryName(backendName)}") // libbolt.so
} else {
  JniLibLoader.loadFromPath(libPath)
}
```

因为 `backendName` 由 `BoltBackend.name()` 提供（即 `"bolt"`），无需任何 Spark
conf 显式声明，加载的就是 `libbolt.so`。

后端命名空间的 conf 前缀通过 `GlutenConfig.prefixOf("bolt")` 派生为
`spark.gluten.sql.columnar.backend.bolt.*`（参见 `BoltBackend.CONF_PREFIX`）。

## 从 PR #11261 移植的 Bolt 特性

下表记录了把 PR #11261 中**真正属于 Bolt 的增量特性**逐 commit 迁移到 thin 设计的情况。
纯粹的 `velox→bolt` 重命名拷贝、以及 PR 提交时尚未合入、但**现已存在于上游 main**
的改动（如 ORC/Parquet 按位置映射 #10697、`SparkExprToSubfieldFilterParser` 注册、
`trunc`/`sequence`/`map_from_*` 的基础 `Sig` 注册）不再重复移植。

| PR commit | 特性 | 落地方式 | 状态 |
| --- | --- | --- | --- |
| `826c0735` | InSet 大集合 heap OOM 修复（延迟构造 LiteralNode） | gluten-substrait 共享层（`ExpressionBuilder`/`SingularOrListNode`/`PredicateExpressionTransformer`），velox 同样受益 | ✅ 已移植 |
| `3aaa02c6` / `a0619eb1` | `BoltConfig`：batchsize 默认值覆盖、`...backend.bolt.shuffle.check.ratio` | 新建 `backends-bolt/.../config/BoltConfig.scala` + `ConfigRegistry` 纯新增 hook | ✅ 已移植（shuffle 写路径读取点见下方 TODO） |
| `30579e4d` | `sequence` 函数（timestamp 输入回退） | 共享 `SparkPlanExecApi.genSequenceTransformer` + `ExpressionConverter` 分发；`BoltSparkPlanExecApi` 覆写 | ✅ 已移植 |
| `2942287a` | `format_number` 函数 | 共享 `ExpressionNames`/`ExpressionMappings` 新增 `FORMAT_NUMBER` | ✅ 已移植 |
| `fb8974c8` / `af2e3bbf` | `map_from_arrays`（FIRST_WIN 回退） | `BoltSparkPlanExecApi.extraExpressionConverter` 覆写 + `MapFromArraysRestrictions` | ✅ 已移植（JVM 侧） |
| `d34ca59b` | `map_from_entries` | main 已等价支持，Bolt 直接继承，无需覆写 | ✅ 已在上游 |
| `a9e29e1e` | first/last 中间态用 `row_constructor` | 新建 `BoltIntermediateData`；派发 hook 见下方 TODO | ✅ 数据对象已移植 |
| `06b80cec`（拷贝） | 复制整个 backends-velox / cpp/velox | thin 设计用继承+SPI+profile 复用替代 | ❌ 不移植（即本方案目标） |
| `6dbdc7c3` / `faf6d0d2` | Paimon 多后端重构 + 元数据列过滤修复 | **后端无关**的 Paimon 上游重构，非 Bolt 专属；Bolt 已经由 `-Ppaimon` profile 复用 velox 的 `src-paimon` 自动获得 Paimon 支持 | ⏸️ 主动延后（依赖大规模重构，与 thin-bolt 目标正交） |
| 8 个 `*.md` 文档 commit | bolt-quick-start / Bolt.md 等 | 内容针对“全拷贝”后端，已被本 README 取代 | ⏸️ 不单独移植 |
| 多个 native commit（`cpp/bolt` 函数校验、`__cxa_throw`、符号冲突等） | 依赖字节内部 Bolt 引擎源码 | 已在 `cpp/bolt/compute/BoltBackend.cc` 以 `TODO(bolt native)` 注释记录需在 `SubstraitToBoltPlanValidator` 放行的函数清单 | 📝 脚手架注释 |

### 已知 TODO（需触及共享/原生代码，按风险延后）

* **shuffle.check.ratio 读取点**：配置项已定义，但读取它的 `ColumnarShuffleWriter`
  及 `shuffle_writer_info.proto` 属于与 velox 共享的代码，接线需新增 proto 字段，
  存在影响 velox 默认行为的风险，故在 `BoltConfig` 中以 `// TODO(bolt)` 标注。
* **first/last `row_constructor` 派发**：`BoltIntermediateData` 已就位，但
  `HashAggregateExecTransformer`（共享）当前硬编码调用 `VeloxIntermediateData`，
  需经 API hook 派发，且依赖 native 验证，故留作 TODO。
* **原生函数放行**：`sequence`/`map_from_arrays`/`format_number` 等需要在 Bolt
  专有引擎的 `SubstraitToBoltPlanValidator` 中放行，本仓库无法构建该引擎。

## 与 PR #11261 的差异

PR #11261 通过**复制** `backends-velox` 与 `cpp/velox`（≈1100 文件）建立 Bolt
后端。本实现：

* JVM 侧 0 拷贝：`BoltBackend extends VeloxLikeBackend` + SPI 标记 + 6 个 profile
  `add-source` 块；
* 原生侧 1 个目录、2 个文件的脚手架，受 `BUILD_BOLT_BACKEND` 开关保护，
  默认不影响 velox 构建。

后续接入字节内部 Bolt 引擎时，只需替换 `cpp/bolt/compute/BoltBackend.cc` 中
带 `TODO` 标记的工厂体即可。
