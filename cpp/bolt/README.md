# cpp/bolt — Gluten <-> Bolt 原生桥接（联合编译）

本目录**只**包含联合编译的 CMake 胶水，不提交任何生成的源码。

## 设计：从 cpp/velox 构建期生成，而非提交副本

Bolt（github.com/bytedance/bolt）是 Velox 的一个分支：
- 命名空间 `bytedance::bolt`（而非 `facebook::velox`）
- include 前缀 `#include "bolt/..."`（而非 `velox/`）
- conan 包目标 `bolt::bolt`（cmake 文件名 `bolt`，库 `bolt_engine`）

Gluten 的 `cpp/velox` 桥接层只通过上述两个面（命名空间 + include 前缀）与引擎
交互，因此可以用一组**保守的、仅针对引擎引用**的替换，把 Velox 桥接机械地
重定向到 Bolt 之上。这一步由 [`dev/gen-bolt-cpp.sh`](../../dev/gen-bolt-cpp.sh)
在构建期完成，输出到 `cpp/build/bolt_gen`（已被 `.gitignore` 忽略，永不提交）。

> 对比 PR #11261：它把 `cpp/velox` 整目录（≈183 文件）复制成 `cpp/bolt`。本方案
> 用构建期 codegen 取代该副本，仓库里只保留本目录的 CMake 胶水。

## 文件

| 文件 | 说明 |
| --- | --- |
| `CMakeLists.txt` | 由顶层 `cpp/bolt.CMakeLists.cmake`（经 `ENABLE_BOLT`/`BUILD_BOLT_BACKEND` 守卫 include）`add_subdirectory` 进入：定位 Bolt 引擎（conan `find_package(bolt)` 或 `BOLT_HOME`/`BOLT_BUILD_PATH` 源码树回退）→ 运行 `dev/gen-bolt-cpp.sh` → `add_subdirectory` 生成树，产出 `libbolt.so` |

## 与 PR #11261 的对齐

除「构建期 codegen 取代提交副本」这一点外，联合编译机制与 PR #11261 完全一致：
- conan 坐标：`bolt/<ver>@<user>/<channel>`，目标 `bolt::bolt`；
- gluten recipe：[`cpp/conanfile.py`](../conanfile.py) 依赖 bolt 并设置 `ENABLE_BOLT`；
- include 模式：`cpp/CMakeLists.txt` 顶部 `if(ENABLE_BOLT ...) include(bolt.CMakeLists.cmake); return()`；
- 顶层 [`cpp/bolt.CMakeLists.cmake`](../bolt.CMakeLists.cmake) 构建 gluten-core 后再 `add_subdirectory(bolt)`。

## 替换规则（仅引擎引用）

C/C++ 源文件与头文件：
1. `#include "velox/` → `#include "bolt/`
2. `#include <velox/` → `#include <bolt/`
3. `facebook::velox` → `bytedance::bolt`
4. **定向**：`kVeloxBackendKind{"velox"}` → `kVeloxBackendKind{"bolt"}`
   （只改这一个 backend-kind 字面量，使其与 JVM 侧 `BoltBackend.name()=="bolt"`
   及 `NativeBackendInitializer.forBackend("bolt")` 对齐；**不做** `"velox"`→`"bolt"`
   全量替换，以免破坏 velox 配置键 / 路径 / 日志字符串。）

链接版本脚本 `symbols.map`：`facebook::velox` → `bytedance::bolt`（导出
`bytedance::bolt::*`，与 libvelox.so 导出引擎命名空间一致）。

**保持不变**：gluten-core 的 include（`compute/Runtime.h`、`memory/...`、`jni/...`
等，均不以 `velox/` 开头）以及 gluten 符号名。

## 联合编译命令

见 [`dev/builddeps-boltbe.sh`](../../dev/builddeps-boltbe.sh) 与
[`backends-bolt/README.md`](../../backends-bolt/README.md) 的「原生侧与
bytedance/bolt 联合编译」一节。
