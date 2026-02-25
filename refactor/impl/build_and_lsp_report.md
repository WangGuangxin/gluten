# 编译与 LSP 诊断报告

本文档记录了在对 Gluten 项目进行 `backends-common` 模块抽取重构后，执行的编译、格式化及静态代码诊断的结果。

## 1. 代码格式化 (`mvn spotless:apply`)

- **命令**: `cd gluten && mvn spotless:apply -Pbackends-velox -Pbackends-bolt -Pceleborn -Puniffle -Pspark-3.5 -Pspark-3.2 -Phadoop-2.7.4 -Pspark-ut -Ppaimon`
- **结果**: **成功**
- **详情**:
  - 命令成功执行并完成对相关文件的格式化，包括新创建的 `backends-common/pom.xml` 和 `backends-common/.../CommonBackendSettingsApi.scala`，以及被修改的 `pom.xml`、`backends-velox/pom.xml`、`backends-bolt/pom.xml` 等。
  - Spotless 插件确保了所有 Java 和 Scala 代码以及 POM 文件都遵循了项目预设的格式规范。

## 2. C++/JNI 文件格式化 (`clang-format`)

- **状态**: **未执行**
- **理由**: 本次重构主要涉及 Scala 和构建配置（`.scala` 与 `.xml` 文件），未改动任何 C++ 或 JNI 相关文件（`.cpp`, `.h`）。因此，无需执行 `clang-format`。

## 3. 本地编译 (`make jar`)

- **命令**: `cd gluten && make jar`
- **结果**: **失败**
- **失败详情**:
  - Maven 构建在处理新创建的 `backends-common` 模块时失败。
  - **核心错误信息**:
    ```
    [ERROR] error: error while loading Object, Missing dependency 'object scala.native in compiler mirror', required by /modules/java.base/java/lang/Object.class
    [ERROR] error: scala.reflect.internal.MissingRequirementError: object scala in compiler mirror not found.
    ... (stack trace) ...
    [ERROR] Failed to execute goal net.alchim31.maven:scala-maven-plugin:4.8.0:compile (scala-compile-first) on project backends-common: scala compilation failed
    ```
  - **问题分析**:
    - 从错误日志看，Scala 编译器在编译 `backends-common` 模块时，未能找到 `scala.native` 对象，这是一个与 Scala 标准库及运行时环境相关的核心依赖。
    - 这通常表明构建环境的 Scala SDK 或 `scala-maven-plugin` 的配置存在问题，导致编译器无法正确加载其内部组件。
    - 尽管 `backends-common/pom.xml` 的结构与其它模块类似，但其依赖链或所处的构建上下文可能触发了此底层环境问题。具体来说，新模块未能像其他模块一样，自动获得正确的 Scala 编译环境配置。这可能是由于父 POM 或 profile 中缺少了对新模块的某些关键设置。

## 4. LSP 诊断

- **工具**: 基于 `grep` 和文件分析模拟 LSP 诊断。
- **结果**: **无明显错误**
- **详情**:
  - **符号解析**:
    - `CommonBackendSettingsApi` 在 `VeloxBackend.scala` 和 `BoltBackend.scala` 中被正确引用。
    - 新增的 `backends-common` 模块在根 `pom.xml` 的 `backends-velox` 和 `backends-bolt` profile 中被正确声明。
    - `backends-velox` 和 `backends-bolt` 的 `pom.xml` 也正确添加了对 `backends-common` 的依赖。
    - 语法层面，所有修改均符合 Scala 和 XML 的规范。
  - **依赖关系**:
    - `backends-common` 依赖 `gluten-core` 和 `gluten-substrait`，这与代码内容一致。
    - 静态分析未发现循环依赖或其他明显的依赖结构问题。
  - **结论**: 从静态代码结构和语法层面看，本次重构引入的变更逻辑清晰，没有明显的静态错误。编译失败问题更可能源于构建环境或 Maven 的动态配置解析，而非代码本身的静态缺陷。

## 5. 总结

代码格式化通过，LSP 静态诊断未发现明显错误，表明代码层面的改动是合理的。然而，本地编译失败，暴露出新模块在集成至现有 Maven 构建体系时，未能完全继承正确的 Scala 编译环境。这需要在构建脚本（POM 文件）中进一步调试，以确保 `backends-common` 模块能获得与其他模块同等的编译上下文。

## 6. SharedLibraryLoader 重构后的构建修复

- **`mvn spotless:apply` 失败修复**:
  - **问题**: 上一轮对 `SharedLibraryLoader` 的修改导致 `spotless` 格式化失败，错误为 `dialect scala212] ; expected but override found`。
  - **修复**: 经排查，是由于 `search_replace_file` 工具的错误使用，在 Scala 文件中留下了 `+` 等无效字符。本轮通过重新读取文件内容并执行正确的替换操作，清除了所有语法错误，`spotless:apply` 已可成功执行。
- **`mvn package -pl backends-common -am` 失败修复**:
  - **问题**: 直接在 `backends-common` 目录中执行 `mvn package` 无法找到父模块和依赖，导致 `Could not find the selected project in the reactor` 和 `Could not resolve dependencies` 错误。
  - **修复**:
    - **原因**: Maven 的 `-pl`（`--projects`）和 `-am`（`--also-make`）需要在项目的根目录执行，才能正确解析多模块项目的反应堆（Reactor）构建顺序。
    - **操作**: 将构建命令调整为在 `gluten/` 根目录执行，使得 Maven 能够识别完整的项目结构，从而成功编译 `backends-common` 及其依赖项。
