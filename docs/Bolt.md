# Gluten Bolt Backend — Build & Quick Start

[Bolt](https://github.com/bytedance/bolt) is ByteDance's Velox-derived engine. On
the JVM side it is binary-compatible with the Velox backend (shared JNI surface,
`VeloxLikeBackend`), so the Bolt backend reuses the Velox Scala/Java stack and
only differs in the backend name (`"bolt"`) and the native library
(`libbolt.so`).

This page documents how to build a Gluten JAR with the Bolt backend and how to
verify it. The joint-compilation recipe mirrors the original add_bolt_backend PR
(PR #11261); the **one intentional difference** is that the Gluten<->Bolt C++
bridge is *generated* from `cpp/velox` at build time (`dev/gen-bolt-cpp.sh`)
instead of being committed as a sed-renamed copy. See
[`cpp/bolt/README.md`](../cpp/bolt/README.md) and
[`backends-bolt/README.md`](../backends-bolt/README.md) for the design.

## 1. Prerequisites

The build host must satisfy Bolt's requirements (same as PR #11261):

- **OS**: Linux
- **Compiler**: GCC 10, 11, or 12; or Clang 16
- **Tools**: Python 3, [conan](https://conan.io) 2.x, Ninja, CMake ≥ 3.25
- **JDK**: 11 or 17 (the Bolt native build rejects JDK 8)
- **Kernel**: Linux kernel > 5.4 recommended (enables `io_uring`)

Reference toolchain images are provided:
- [`dev/docker/Dockerfile.centos8-bolt`](../dev/docker/Dockerfile.centos8-bolt)
- [`dev/docker/Dockerfile.ubuntu22-bolt`](../dev/docker/Dockerfile.ubuntu22-bolt)

`bash dev/install-conan.sh` checks the GCC version and installs conan with a
`gnu17` default profile.

> The first native build compiles every missing third-party dependency through
> conan (a Velox-class engine): expect a multi-hour, multi-GB build.

## 2. Build the native backend (`libbolt.so`)

The conan coordinates are identical to PR #11261:
`bolt/<version>@<user>/<channel>` (target `bolt::bolt`), and
[`cpp/conanfile.py`](../cpp/conanfile.py) requires it and sets the `ENABLE_BOLT`
CMake cache variable, which makes `cpp/CMakeLists.txt` include
`cpp/bolt.CMakeLists.cmake`.

[`dev/builddeps-boltbe.sh`](../dev/builddeps-boltbe.sh) folds the PR Makefile's
`bolt-recipe` / `build` / `arrow` targets into one command:

```bash
export JAVA_HOME=/path/to/jdk11
# Default BOLT_HOME=../bolt (sibling of gluten), BOLT_BUILD_VERSION=main
./dev/builddeps-boltbe.sh
```

Individual steps (mapping to the PR Makefile targets):

```bash
./dev/builddeps-boltbe.sh install_conan          # == dev/install-conan.sh
./dev/builddeps-boltbe.sh bolt_recipe            # == make bolt-recipe (conan export bolt)
./dev/builddeps-boltbe.sh build_gluten_cpp_bolt  # == make release (conan install + cmake)
./dev/builddeps-boltbe.sh build_bolt_arrow       # == make arrow
```

The native library is produced at `cpp/build/releases/libbolt.so`.

### Prebuilt-bolt / source-tree fallback (no conan)

```bash
BOLT_BUILD_PATH=/path/to/bolt/_build/Release \
  ./dev/builddeps-boltbe.sh --build_bolt=OFF --bolt_home=/path/to/bolt
```

## 3. Build the Arrow-for-bolt Java libs (before packaging)

```bash
./dev/builddeps-boltbe.sh build_bolt_arrow --build_arrow=ON
# == bash dev/build_bolt_arrow.sh / make arrow
```

## 4. Package the JAR

Switch the Maven profile from `-Pbackends-velox` to `-Pbackends-bolt` (the bolt
module reuses the velox build, so both profiles are activated):

```bash
./build/mvn package \
  -pl backends-bolt -am \
  -Pbackends-velox -Pbackends-bolt \
  -Pspark-3.5 -Pscala-2.12 \
  -DskipTests
```

Confirm `libbolt.so` is bundled:

```bash
unzip -l package/target/gluten-package-*.jar | grep libbolt.so
```

## 5. Quick verification in `spark-shell`

```bash
export GLUTEN_JAR=$(ls package/target/gluten-package-*.jar | head -n 1)

spark-shell --master 'local[4]' \
  --conf spark.plugins=org.apache.gluten.GlutenPlugin \
  --conf spark.memory.offHeap.enabled=true \
  --conf spark.memory.offHeap.size=10g \
  --conf spark.shuffle.manager=org.apache.spark.shuffle.sort.ColumnarShuffleManager \
  --jars ${GLUTEN_JAR}
```

```scala
val df = spark.range(0, 1000).selectExpr("id", "id % 10 as category")
df.filter($"category" > 5).groupBy("category").count().explain()
```

If the plan shows `*ExecTransformer` nodes (e.g. `FilterExecTransformer`,
`HashAggregateExecTransformer`), Gluten has offloaded the computation to the Bolt
backend. The Spark UI SQL tab confirms the active backend.

## 6. Rollback

To revert to the Velox backend, repackage with `-Pbackends-velox` only and
restore `spark.gluten.sql.columnar.backend.velox.*` keys. To fall back to vanilla
Spark, set `--conf spark.gluten.enabled=false` (or remove `spark.plugins`).
