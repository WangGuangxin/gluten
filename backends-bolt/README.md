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

## 与 PR #11261 的差异

PR #11261 通过**复制** `backends-velox` 与 `cpp/velox`（≈1100 文件）建立 Bolt
后端。本实现：

* JVM 侧 0 拷贝：`BoltBackend extends VeloxLikeBackend` + SPI 标记 + 6 个 profile
  `add-source` 块；
* 原生侧 1 个目录、2 个文件的脚手架，受 `BUILD_BOLT_BACKEND` 开关保护，
  默认不影响 velox 构建。

后续接入字节内部 Bolt 引擎时，只需替换 `cpp/bolt/compute/BoltBackend.cc` 中
带 `TODO` 标记的工厂体即可。
