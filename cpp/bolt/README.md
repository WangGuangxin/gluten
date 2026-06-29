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
| `substrait/` | **手写覆盖目录**。放在此处、与 `cpp/velox/substrait/` 同相对路径的源文件，codegen 完成后会**原样**覆盖生成树同名文件（不经 sed 处理）。仅 substrait 转换层使用，详见 [`substrait/README.md`](substrait/README.md) |

## Substrait 转换层覆盖机制

`cpp/velox/substrait/` 是引擎耦合最重的一块（算子/类型/校验器），与上游 Velox
存在真实语义差异，通常无法用纯字符串替换得到可用版本。为此提供一个**可见、
手写、按需覆盖**的入口：

- 把需要手写的源文件放到 [`cpp/bolt/substrait/<相对路径>`](substrait/README.md)，
  使其与 `cpp/velox/substrait/<相对路径>` 一一对应；
- `dev/gen-bolt-cpp.sh` 完成全树 codegen 与 sed 替换之后，会把本目录中的同名
  文件**原样**覆盖到生成树 `${BOLT_GEN_DIR}/substrait/<相对路径>`；
- 覆盖文件**不经过** sed 替换，需要直接以 `bytedance::bolt` 命名空间 / `bolt/`
  include 前缀书写；
- 生成树 `CMakeLists.txt` 的源文件列表以相对路径为键，因此覆盖文件**替换**
  生成版的同一构建单元，**不会**出现重复符号。

> 默认情况下你**不需要**使用本目录；优先依赖整树 codegen。当某个 substrait
> 文件因 Velox/Bolt 接口分叉而无法自动生成可编译版本时，再把它提升为手写覆盖。
> 详见 [`substrait/README.md`](substrait/README.md)。

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
