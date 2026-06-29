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
| `cpp/bolt/CMakeLists.txt`                                                       | 受 `BUILD_BOLT_BACKEND` 控制，默认 `OFF`；定位 bolt 引擎（conan `bolt::bolt` 或 `BOLT_HOME`/`BOLT_BUILD_PATH`）→ 运行 codegen → `add_subdirectory` 生成树，产出 `libbolt.so` |
| `cpp/bolt/README.md`                                                            | 联合编译说明（codegen-from-cpp/velox，不提交副本） |
| `dev/gen-bolt-cpp.sh`                                                           | 构建期从 `cpp/velox` 生成 Gluten<->Bolt 桥接（仅引擎引用替换），输出到 `cpp/build/bolt_gen`（不提交） |
| `dev/builddeps-boltbe.sh`                                                       | 联合编译入口脚本（构建/导出 bolt conan 包 → codegen → 配置编译 gluten cpp） |

## 构建命令

### 1. 原生侧与 bytedance/bolt 联合编译

> ✅ 默认 `BUILD_BOLT_BACKEND=OFF`，不影响 velox 主线构建（velox 路径逐字节不变）。

#### 架构：从 cpp/velox 构建期生成，**不提交副本**

Bolt（github.com/bytedance/bolt）是 Velox 的一个分支：命名空间 `bytedance::bolt`、
include 前缀 `bolt/`、conan 包目标 `bolt::bolt`（库 `bolt_engine`）。Gluten 的
`cpp/velox` 桥接层只通过「命名空间 + include 前缀」两个面与引擎交互，因此可以用
一组**仅针对引擎引用**的保守替换，把 Velox 桥接机械地重定向到 Bolt：

| 文件类型 | 替换 |
| --- | --- |
| `*.cc/*.h/...` | `#include "velox/` → `#include "bolt/`；`#include <velox/` → `#include <bolt/`；`facebook::velox` → `bytedance::bolt`；**定向** `kVeloxBackendKind{"velox"}` → `{"bolt"}` |
| `symbols.map` | `facebook::velox` → `bytedance::bolt`（导出 `bytedance::bolt::*`） |
| 生成的 CMakeLists | 引擎链接目标 `facebook::velox`/`libvelox.a` → conan 目标 `bolt::bolt` |

> **保持不变**：gluten-core include（`compute/Runtime.h`、`memory/...`、`jni/...`，均不以
> `velox/` 开头）与 gluten 符号名。**不做** `"velox"`→`"bolt"` 全量替换（会破坏
> velox 配置键 / 路径 / 日志 / ColumnarBatch 类型）。

这一步由 [`dev/gen-bolt-cpp.sh`](../dev/gen-bolt-cpp.sh) 在构建期完成，输出到
`cpp/build/bolt_gen`（已被 `.gitignore` 忽略，**永不提交**）。仓库里只保留
`cpp/bolt/CMakeLists.txt` + `cpp/bolt/README.md` 这两份胶水。

> 对比 PR #11261：它把 `cpp/velox`（≈183 文件）整目录复制为 `cpp/bolt`；本方案用
> 构建期 codegen 取代该冗余副本。

#### 联合编译命令（需要 conan 工具链）

前置条件：`conan` + `ninja` + C++23 工具链；首次需联网拉取 conan recipe。构建一个
Velox 级引擎耗时数小时、占用数 GB。可使用 bolt 仓库的 `.devcontainer` / CI 引用的
centos8-bolt 镜像。

一键联合编译（A: 编译并导出 bolt 的 conan 包；B: 生成桥接；C/D: 配置并编译 gluten cpp）：

```bash
# 默认 BOLT_HOME=../bolt（与 gluten 同级）；BOLT_MAKE_TARGET 默认 release_spark
./dev/builddeps-boltbe.sh
```

若已有构建好的 bolt（conan 缓存或源码树），跳过重量级 conan 构建：

```bash
# 方式一：bolt 已 export 到本地 conan 缓存（find_package(bolt) 可发现）
./dev/builddeps-boltbe.sh --build_bolt=OFF

# 方式二：源码树链接（无 conan 包），镜像 velox 的 import_library 模式
./dev/builddeps-boltbe.sh --build_bolt=OFF \
  --bolt_home=/path/to/bolt --bolt_build_path=/path/to/bolt/_build/Release
```

等价的手工步骤：

```bash
# A. 在 bolt 仓库构建 + 导出 conan 包
cd ../bolt && make release_spark BUILD_VERSION=main && make export_base BUILD_VERSION=main && cd -

# B. 生成 Gluten<->Bolt 桥接源码（幂等；输出到 cpp/build/bolt_gen，不提交）
bash dev/gen-bolt-cpp.sh cpp/build/bolt_gen

# C/D. 仅编译 Bolt 后端（关闭 velox）
cmake -S cpp -B cpp/build \
  -G Ninja \
  -DBUILD_VELOX_BACKEND=OFF -DBUILD_BOLT_BACKEND=ON \
  -DBOLT_HOME=../bolt -DBOLT_GEN_DIR=$PWD/cpp/build/bolt_gen -DBOLT_SKIP_CODEGEN=ON
cmake --build cpp/build --target bolt -j
```

构建产物输出在 `cpp/build/releases/libbolt.so`，导出与 `libvelox.so` 相同的 JNI
符号面（`Java_org_apache_gluten_*`、`JNI_OnLoad/OnUnload`）。

#### 验证边界（诚实声明）

| 项 | 状态 |
| --- | --- |
| `dev/gen-bolt-cpp.sh` 生成 + 幂等 + 替换计数（`facebook::velox`/`#include "velox/` 残留=0；backend-kind="bolt"；gluten-core include 不变） | ✅ 已在沙箱验证 |
| JVM 侧 `backends-bolt` 编译（`./build/mvn ... compile`） | ✅ 已在沙箱验证（BUILD SUCCESS） |
| bolt `conanfile.py` recipe 可加载（`conan export`） | ✅ 已在沙箱验证（`conan 2.29.1`，导出 `bolt/main#...`） |
| **原生联合编译产出 `libbolt.so`**（`conan install` + 构建 Velox 级引擎 + 链接） | ❌ **未能在本沙箱验证**：缺少 conan 工具链依赖（ninja/C++23/网络），且构建 Velox 级引擎耗时数小时、占用数 GB，超出沙箱能力 |

> 因为 Bolt 是持续演进的 fork，少数边界情况（与上游 Velox 分叉的 API、超出
> `velox/`→`bolt/` 前缀的头文件改名等）可能仍需在**真实工具链编译期**做少量修补。
> codegen 已确定性、幂等地完成绝大部分重定向。


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
| 多个 native commit（`cpp/bolt` 函数校验、`__cxa_throw`、符号冲突等） | 依赖 bytedance/bolt 引擎源码 | 桥接由 `dev/gen-bolt-cpp.sh` 从 `cpp/velox` 构建期生成并链接外部 bolt 引擎；函数放行需在 bolt 的 `SubstraitToBoltPlanValidator` 完成 | 📝 见联合编译说明 |

### 已知 TODO（需触及共享/原生代码，按风险延后）

* **shuffle.check.ratio 读取点**：配置项已定义，但读取它的 `ColumnarShuffleWriter`
  及 `shuffle_writer_info.proto` 属于与 velox 共享的代码，接线需新增 proto 字段，
  存在影响 velox 默认行为的风险，故在 `BoltConfig` 中以 `// TODO(bolt)` 标注。
* **first/last `row_constructor` 派发**：`BoltIntermediateData` 已就位，但
  `HashAggregateExecTransformer`（共享）当前硬编码调用 `VeloxIntermediateData`，
  需经 API hook 派发，且依赖 native 验证，故留作 TODO。
* **原生函数放行**：`sequence`/`map_from_arrays`/`format_number` 等需要在 Bolt
  引擎的 `SubstraitToBoltPlanValidator` 中放行，需在 bolt 仓库（外部）完成。

## 与 PR #11261 的差异

PR #11261 通过**复制** `backends-velox` 与 `cpp/velox`（≈1100 文件）建立 Bolt
后端。本实现：

* JVM 侧 0 拷贝：`BoltBackend extends VeloxLikeBackend` + SPI 标记 + 6 个 profile
  `add-source` 块；
* 原生侧 0 拷贝：桥接由 `dev/gen-bolt-cpp.sh` 在构建期从 `cpp/velox` 生成（仅引擎
  引用替换），输出到 `cpp/build/bolt_gen`（不提交）；仓库内只保留 `cpp/bolt/` 下的
  CMake 胶水 + README，受 `BUILD_BOLT_BACKEND` 开关保护，默认不影响 velox 构建。

接入外部 bytedance/bolt 引擎时，通过 conan `bolt::bolt`（或 `BOLT_HOME`/`BOLT_BUILD_PATH`
源码树）链接，无需改动本仓库的提交内容。
