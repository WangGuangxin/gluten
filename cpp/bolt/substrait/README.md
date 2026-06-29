# cpp/bolt/substrait — Bolt 专属 substrait 转换层覆盖目录

## 用途

`cpp/velox/substrait/` 下的 Substrait 转换层（`SubstraitToVeloxPlan`、
`SubstraitToVeloxExpr`、`SubstraitToVeloxPlanValidator`、`SubstraitParser`、
`VariantToVectorConverter`、`VeloxSubstraitSignature`、`VeloxToSubstrait*` 等）
是 Gluten 与引擎耦合最重的一块：它直接依赖引擎的算子工厂、类型系统、函数注册表
与表达式 AST。Bolt 作为 Velox 的分支，这一层与上游会出现**真实的语义/接口差异**
（不只是 `facebook::velox` → `bytedance::bolt` 这种命名空间改名），因此通常无法
仅靠 [`dev/gen-bolt-cpp.sh`](../../../dev/gen-bolt-cpp.sh) 的机械替换生成可
编译的版本。

本目录提供一个**可见、手写、按需覆盖**的入口：把任意需要手写的源文件放在
`cpp/bolt/substrait/<相对路径>` 下，使其与 `cpp/velox/substrait/<相对路径>`
一一对应；codegen 完成全树生成与命名空间替换后，本目录中的同名文件会**原样**
覆盖到生成树 `${BOLT_GEN_DIR}/substrait/<相对路径>`，从而胜出参与编译。

## 何时使用

> 默认情况下你**不需要**使用本目录。优先依赖 `dev/gen-bolt-cpp.sh` 的全树
> codegen，只有当机械替换无法产出可编译/正确的结果时，才把对应文件提升为
> 手写覆盖。

典型场景：

- Bolt 引擎的算子/类型 API 与上游 Velox 已发生不可移植的分叉，需要替换算子
  下发或类型映射的实现；
- Bolt 的 `Plan/Expr` 校验器（`SubstraitToBoltPlanValidator`）放行集合与
  Velox 不一致，需要在桥接侧手写校验扩展；
- 临时绕开某段上游 Velox 代码路径（例如尚未在 Bolt 实现的算子）。

## 使用规则

1. **目录结构镜像**：放在本目录的相对路径必须与 `cpp/velox/substrait/` 下
   要被覆盖的文件保持一致。例如：
   - 覆盖 `cpp/velox/substrait/SubstraitToVeloxPlan.cc`
     → 文件路径为 `cpp/bolt/substrait/SubstraitToVeloxPlan.cc`
   - 覆盖头文件 `cpp/velox/substrait/SubstraitToVeloxExpr.h`
     → 文件路径为 `cpp/bolt/substrait/SubstraitToVeloxExpr.h`
2. **直接以 Bolt 命名空间/include 前缀书写**：
   - 命名空间使用 `bytedance::bolt`（**不要**写 `facebook::velox`）；
   - 引擎 include 使用 `#include "bolt/..."` / `#include <bolt/...>`
     （**不要**写 `velox/`）；
   - 后端 kind 字面量直接写 `kVeloxBackendKind{"bolt"}`（标识符仍是
     `kVeloxBackendKind`，只是其字面量值是 `"bolt"`）。
3. **不会被 sed 处理**：本目录下的文件在 codegen 中**不会**经过命名空间/
   include 替换，按字面意义编译。这意味着：
   - 若仍然写了 `facebook::velox` 或 `#include "velox/..."`，最终的残留检查
     （`dev/gen-bolt-cpp.sh` 末尾的 `RESIDUE_NS`/`RESIDUE_INC`）会**直接报错**；
   - gluten-core 的 include（如 `compute/Runtime.h`、`memory/...`、`jni/...`）
     无需修改，保持原样即可。
4. **支持的文件扩展名**：与 codegen 一致，仅匹配 `.cc / .cpp / .h / .hpp /
   .inc / .cu / .cuh`。本 `README.md` 及 `.gitkeep` 等非源文件**不会**被
   叠加进生成树。
5. **CMake 来源列表自动消费**：生成树的 `CMakeLists.txt` 中的源文件列表（从
   `cpp/velox/CMakeLists.txt` 的 `VELOX_SRCS` 提取）以**相对路径**为键。覆盖
   文件替换的是生成树中**同一相对路径**上的文件，因此不会出现「生成版 +
   覆盖版」同时被编译造成的重复符号问题。
6. **新增非覆盖文件**：若放入的相对路径在 `cpp/velox/substrait/` 中**不存在**
   对应原始文件（即新增文件而非覆盖），codegen 会以 `(new file, no
   generated counterpart)` 日志提示。这种情况下需要相应地在 Bolt 自己的
   `CMakeLists.txt` 钩子中把它纳入构建（或扩展 `VELOX_SRCS` 提取规则），否则
   仅会被叠加但不会参与编译。

## 验证 codegen 是否正确叠加

```bash
bash dev/gen-bolt-cpp.sh cpp/build/bolt_gen
# 期望看到：
#   override: substrait/SubstraitToVeloxPlan.cc  <-  cpp/bolt/substrait/SubstraitToVeloxPlan.cc (replaces generated)
#   ...
#   hand-written overrides applied (cpp/bolt/substrait/) : <N>
```

然后比较生成树中的文件与本目录是否字节一致：

```bash
diff -q cpp/bolt/substrait/<相对路径> cpp/build/bolt_gen/substrait/<相对路径>
```

## 当前状态

本目录目前**只**保留本说明与 `.gitkeep` 占位；尚未提交任何具体的覆盖源码。
机制已经就绪，按需添加即可生效。
