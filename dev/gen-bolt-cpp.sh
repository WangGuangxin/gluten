#!/usr/bin/env bash

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

####################################################################################################
# gen-bolt-cpp.sh
#
# WHAT THIS DOES
#   Generates the Gluten<->Bolt native bridge sources *at build time* from the
#   existing Gluten<->Velox bridge (cpp/velox), instead of committing a wholesale
#   copy of cpp/velox into cpp/bolt. (The original PR #11261 copied cpp/velox ->
#   cpp/bolt, ~183 files; we deliberately avoid that redundancy.)
#
#   Bolt (github.com/bytedance/bolt) is a Velox *fork*: it keeps Velox's source
#   layout but renames the engine namespace facebook::velox -> bytedance::bolt and
#   the include prefix velox/ -> bolt/. Because the Gluten bridge only ever talks
#   to the engine through those two surfaces, we can mechanically re-target the
#   Velox bridge onto Bolt with a small set of *conservative* substitutions that
#   touch ENGINE references only and leave gluten-core symbols/includes untouched.
#
# SUBSTITUTION RULES (engine references only)
#   C/C++ sources & headers (*.cc *.cpp *.h *.hpp *.inc):
#     1. #include "velox/   ->  #include "bolt/      (quoted engine includes)
#     2. #include <velox/   ->  #include <bolt/      (angled engine includes)
#     3. facebook::velox    ->  bytedance::bolt       (engine namespace)
#     4. TARGETED kind const: kVeloxBackendKind{"velox"} -> kVeloxBackendKind{"bolt"}
#        This is the single backend-kind literal used by gluten registration
#        (see cpp/velox/compute/VeloxBackend.h). It must equal "bolt" so it lines
#        up with the JVM side BoltBackend.name()=="bolt" and
#        NativeBackendInitializer.forBackend("bolt"). We replace ONLY this exact
#        initializer, NOT a blanket "velox"->"bolt" (a blanket replace would
#        corrupt velox config keys, file paths, log strings and the ColumnarBatch
#        type discriminator kType{"velox"}, which stays internally consistent).
#   Linker version script (symbols.map):
#     - facebook::velox -> bytedance::bolt (so bytedance::bolt::* is exported, the
#       same way *facebook::velox::* is exported from libvelox.so).
#
# WHAT STAYS UNCHANGED
#   - gluten-core includes such as "compute/Runtime.h", "memory/...", "jni/...",
#     "operators/...", "shuffle/..." (they never start with velox/).
#   - gluten symbol names (kVeloxBackendKind the *identifier*, Velox* class names,
#     Java_org_apache_gluten_* JNI entry points).
#
# CMAKE
#   This script does NOT translate cpp/velox/CMakeLists.txt. Instead it writes a
#   purpose-built CMakeLists.txt into the output dir that realises the engine
#   CMake mapping directly: it links bolt::bolt (conan target) instead of the
#   imported facebook::velox / libvelox.a, and builds libbolt.so exporting the
#   same JNI symbols as libvelox.so. The committed cpp/bolt/CMakeLists.txt drives
#   this script and locates the bolt engine (conan find_package or BOLT_HOME).
#
# HAND-WRITTEN OVERRIDES (cpp/bolt/substrait/)
#   Some files in the substrait conversion layer (SubstraitToVeloxPlan,
#   SubstraitToVeloxExpr, validators, ...) diverge enough between Velox and
#   Bolt that mechanical sed substitution does not produce a buildable result.
#   Instead of forking the entire bridge for those few files, we keep the
#   whole-tree codegen and let the user drop hand-written replacements under
#   cpp/bolt/substrait/<relpath> mirroring cpp/velox/substrait/<relpath>. After
#   the namespace/include substitutions run, this script OVERLAYS those files
#   onto $OUT_DIR/substrait/<relpath>, replacing the generated counterpart.
#   The override copy is NOT passed through sed (the hand-written file is
#   already in bytedance::bolt / bolt/ form). Because the source list in the
#   generated CMakeLists is keyed by relative path, the overlaid file takes
#   the slot of the generated one with no duplicate compilation.
#
# CAVEAT (honest boundary)
#   These are mechanical, source-level substitutions. Bolt is a living fork, so a
#   handful of edge cases (APIs that diverged from upstream Velox, headers that
#   were renamed beyond the velox/->bolt/ prefix, etc.) may still need fixups
#   during a real-toolchain compile pass against a built Bolt engine. This script
#   gets the bulk of the re-targeting done deterministically and idempotently.
#
# USAGE
#   bash dev/gen-bolt-cpp.sh [OUTPUT_DIR]
#     OUTPUT_DIR defaults to cpp/build/bolt_gen (git-ignored, never committed).
####################################################################################################

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GLUTEN_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC_DIR="$GLUTEN_DIR/cpp/velox"

OUT_DIR_ARG="${1:-$GLUTEN_DIR/cpp/build/bolt_gen}"
# Resolve OUT_DIR to an absolute path (it may not exist yet).
mkdir -p "$OUT_DIR_ARG"
OUT_DIR="$(cd "$OUT_DIR_ARG" && pwd)"

if [[ ! -d "$SRC_DIR" ]]; then
  echo "ERROR: source bridge dir not found: $SRC_DIR" >&2
  exit 1
fi

echo "=== gen-bolt-cpp ==="
echo "  source : $SRC_DIR"
echo "  output : $OUT_DIR"

# ----------------------------------------------------------------------------
# 1. Idempotent clean copy of the C/C++ bridge sources (engine bridge only).
#    We skip build artifacts; tests/benchmarks are copied so the tree stays a
#    faithful mirror, but the generated CMakeLists compiles only the library
#    source set (mirrored from cpp/velox VELOX_SRCS).
# ----------------------------------------------------------------------------
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

# Copy source files preserving directory structure.
( cd "$SRC_DIR"
  find . -type f \
    \( -name '*.cc' -o -name '*.cpp' -o -name '*.h' -o -name '*.hpp' \
       -o -name '*.inc' -o -name '*.cu' -o -name '*.cuh' -o -name 'symbols.map' \) \
    -print0 | while IFS= read -r -d '' f; do
      dest="$OUT_DIR/$f"
      mkdir -p "$(dirname "$dest")"
      cp "$f" "$dest"
    done
)

# ----------------------------------------------------------------------------
# 2. Apply the conservative engine-only substitutions.
# ----------------------------------------------------------------------------
CPP_FILES=()
while IFS= read -r -d '' f; do CPP_FILES+=("$f"); done < <(
  find "$OUT_DIR" -type f \
    \( -name '*.cc' -o -name '*.cpp' -o -name '*.h' -o -name '*.hpp' \
       -o -name '*.inc' -o -name '*.cu' -o -name '*.cuh' \) -print0
)

# Count occurrences (in the pristine source copy) for the summary.
count_in_files() {
  local pattern="$1"; shift
  if [[ ${#CPP_FILES[@]} -eq 0 ]]; then echo 0; return; fi
  # `|| true`: grep exits non-zero when there are no matches, which would trip
  # `set -e`/`pipefail`; a zero count is a legitimate result here.
  grep -aoh -- "$pattern" "${CPP_FILES[@]}" 2>/dev/null | wc -l | tr -d ' ' || true
}

N_INC_QUOTE=$(count_in_files '#include "velox/')
N_INC_ANGLE=$(count_in_files '#include <velox/')
N_NS=$(count_in_files 'facebook::velox')
N_KIND=$(count_in_files 'kVeloxBackendKind{"velox"}')

for f in "${CPP_FILES[@]}"; do
  # Rule 4 FIRST (targeted), before the broad namespace rule, so the exact
  # initializer is matched verbatim.
  sed -i 's/kVeloxBackendKind{"velox"}/kVeloxBackendKind{"bolt"}/g' "$f"
  # Rules 1 & 2: engine include prefix.
  sed -i 's@#include "velox/@#include "bolt/@g' "$f"
  sed -i 's@#include <velox/@#include <bolt/@g' "$f"
  # Rule 3: engine namespace.
  sed -i 's/facebook::velox/bytedance::bolt/g' "$f"
done

# symbols.map: namespace export only.
if [[ -f "$OUT_DIR/symbols.map" ]]; then
  N_SYMMAP=$(grep -aoh 'facebook::velox' "$OUT_DIR/symbols.map" 2>/dev/null | wc -l | tr -d ' ' || true)
  sed -i 's/facebook::velox/bytedance::bolt/g' "$OUT_DIR/symbols.map"
else
  N_SYMMAP=0
fi

# ----------------------------------------------------------------------------
# 2b. Overlay hand-written Bolt-specific overrides from cpp/bolt/substrait/.
#     Any C/C++ source/header file the user drops under cpp/bolt/substrait/
#     (mirroring cpp/velox/substrait/<relpath>) is copied verbatim onto
#     $OUT_DIR/substrait/<relpath>, replacing the just-generated counterpart.
#     The override file is NOT sed-processed (it is already authored in
#     bytedance::bolt / bolt/ form). The generated CMakeLists.txt source list
#     is keyed by relative path, so the overlaid file takes the slot of the
#     generated one with no duplicate compilation.
# ----------------------------------------------------------------------------
OVERRIDE_DIR="$GLUTEN_DIR/cpp/bolt/substrait"
N_OVERRIDES=0
if [[ -d "$OVERRIDE_DIR" ]]; then
  OVERRIDE_FILES=()
  while IFS= read -r -d '' f; do OVERRIDE_FILES+=("$f"); done < <(
    cd "$OVERRIDE_DIR" && find . -type f \
      \( -name '*.cc' -o -name '*.cpp' -o -name '*.h' -o -name '*.hpp' \
         -o -name '*.inc' -o -name '*.cu' -o -name '*.cuh' \) -print0
  )
  for rel in "${OVERRIDE_FILES[@]}"; do
    rel="${rel#./}"
    src="$OVERRIDE_DIR/$rel"
    dest="$OUT_DIR/substrait/$rel"
    mkdir -p "$(dirname "$dest")"
    cp "$src" "$dest"
    if [[ -f "$SRC_DIR/substrait/$rel" ]]; then
      echo "  override: substrait/$rel  <-  cpp/bolt/substrait/$rel (replaces generated)"
    else
      echo "  override: substrait/$rel  <-  cpp/bolt/substrait/$rel (new file, no generated counterpart)"
    fi
    N_OVERRIDES=$((N_OVERRIDES + 1))
  done
fi

# ----------------------------------------------------------------------------
# 3. Emit the generated CMakeLists.txt (engine CMake mapping lives here).
#    The library source list is extracted live from cpp/velox/CMakeLists.txt's
#    VELOX_SRCS block so it auto-tracks upstream changes. The ${VELOX_PROTO_SRCS}
#    entry is dropped here and handled by the proto custom command below.
# ----------------------------------------------------------------------------
SRC_LIST_FILE="$OUT_DIR/.bolt_bridge_srcs.cmake.in"
awk '
  /set\(VELOX_SRCS/ {capture=1; next}
  capture==1 {
    line=$0
    gsub(/\)[[:space:]]*$/, "", line)         # strip trailing )
    if ($0 ~ /\)[[:space:]]*$/) capture=2      # this was the last entry
    gsub(/^[[:space:]]+/, "", line)
    gsub(/[[:space:]]+$/, "", line)
    if (line != "" && line !~ /VELOX_PROTO_SRCS/) print "  " line
    if (capture==2) exit
  }
' "$SRC_DIR/CMakeLists.txt" > "$SRC_LIST_FILE"

N_SRCS=$(wc -l < "$SRC_LIST_FILE" | tr -d ' ')

cat > "$OUT_DIR/CMakeLists.txt" <<'GENCMAKE'
# AUTO-GENERATED by dev/gen-bolt-cpp.sh -- DO NOT EDIT, DO NOT COMMIT.
#
# Builds libbolt.so: the Gluten<->Bolt native bridge, mechanically re-targeted
# from the Gluten<->Velox bridge (cpp/velox). It exports the same JNI surface as
# libvelox.so (Java_org_apache_gluten_*, JNI_OnLoad/OnUnload) plus the engine
# namespace bytedance::bolt::* (mirroring the engine namespace exported by
# libvelox.so).
#
# Engine CMake mapping (vs cpp/velox/CMakeLists.txt):
#   the imported Velox engine target / libvelox.a   ->   bolt::bolt (conan target)
# bolt::bolt must already be defined by the parent cpp/bolt/CMakeLists.txt
# (conan find_package(bolt) or a BOLT_HOME/BOLT_BUILD_PATH source-tree import).

cmake_minimum_required(VERSION 3.16)

if(NOT TARGET bolt::bolt)
  message(
    FATAL_ERROR
      "bolt::bolt target not found. cpp/bolt/CMakeLists.txt must locate the Bolt "
      "engine (conan find_package(bolt) or BOLT_HOME/BOLT_BUILD_PATH) before "
      "add_subdirectory() of the generated tree.")
endif()

# ---- Gluten proto (backend-agnostic; identical to the Velox backend) ----
set(BOLT_PROTO_SRC_DIR
    ${GLUTEN_HOME}/backends-velox/src/main/resources/org/apache/gluten/proto)
set(BOLT_PROTO_OUTPUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/proto")
file(MAKE_DIRECTORY ${BOLT_PROTO_OUTPUT_DIR})

file(GLOB BOLT_PROTO_FILES ${BOLT_PROTO_SRC_DIR}/*.proto)
foreach(PROTO ${BOLT_PROTO_FILES})
  file(RELATIVE_PATH REL_PROTO ${BOLT_PROTO_SRC_DIR} ${PROTO})
  string(REGEX REPLACE "\\.proto" "" PROTO_NAME ${REL_PROTO})
  list(APPEND BOLT_PROTO_SRCS "${BOLT_PROTO_OUTPUT_DIR}/${PROTO_NAME}.pb.cc")
  list(APPEND BOLT_PROTO_HDRS "${BOLT_PROTO_OUTPUT_DIR}/${PROTO_NAME}.pb.h")
endforeach()
set_source_files_properties(${BOLT_PROTO_SRCS} ${BOLT_PROTO_HDRS}
                            PROPERTIES GENERATED TRUE)

# ---- Bridge sources (mirrored live from cpp/velox VELOX_SRCS) ----
set(BOLT_BRIDGE_SRCS
@BOLT_BRIDGE_SRCS@
)

add_library(bolt SHARED ${BOLT_BRIDGE_SRCS} ${BOLT_PROTO_SRCS})

find_protobuf()
add_custom_command(
  OUTPUT ${BOLT_PROTO_SRCS} ${BOLT_PROTO_HDRS}
  COMMAND ${PROTOC_BIN} --proto_path ${BOLT_PROTO_SRC_DIR}/ --cpp_out
          ${BOLT_PROTO_OUTPUT_DIR} ${BOLT_PROTO_FILES}
  COMMENT "Running Gluten Bolt PROTO compiler"
  VERBATIM)
add_custom_target(bolt_jni_proto ALL DEPENDS ${BOLT_PROTO_SRCS} ${BOLT_PROTO_HDRS})
add_dependencies(bolt_jni_proto protobuf::libprotobuf)
add_dependencies(bolt bolt_jni_proto)

target_include_directories(
  bolt
  PUBLIC ${CMAKE_SYSTEM_INCLUDE_PATH}
         ${JNI_INCLUDE_DIRS}
         ${CMAKE_CURRENT_SOURCE_DIR}
         ${BOLT_PROTO_OUTPUT_DIR}
         ${PROTOBUF_INCLUDE}
         # Bolt engine headers come transitively from bolt::bolt (conan). For a
         # BOLT_HOME source-tree build the parent also adds ${BOLT_HOME} here.
         ${BOLT_HOME})

# gluten core + bolt engine. Direct deps of the bridge (re2, roaring, folly,
# icu, ...) are provided transitively by the conan bolt::bolt package.
target_link_libraries(bolt PUBLIC gluten bolt::bolt)

set_target_properties(bolt PROPERTIES LIBRARY_OUTPUT_DIRECTORY
                                      ${root_directory}/releases)

# Export the same symbol surface as libvelox.so (now bytedance::bolt::*).
if(ENABLE_GLUTEN_VCPKG AND NOT CMAKE_SYSTEM_NAME MATCHES "Darwin"
   AND EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/symbols.map)
  target_link_options(
    bolt PRIVATE -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/symbols.map)
endif()

add_custom_command(
  TARGET bolt
  POST_BUILD
  COMMAND ldd $<TARGET_FILE:bolt> || true
  COMMENT "Checking ldd result of libbolt.so")
GENCMAKE

# Inline the extracted source list into the generated CMakeLists.
# (portable in-place replacement of the @BOLT_BRIDGE_SRCS@ marker)
python3 - "$OUT_DIR/CMakeLists.txt" "$SRC_LIST_FILE" <<'PY'
import sys
cmake_path, srcs_path = sys.argv[1], sys.argv[2]
with open(srcs_path) as f:
    srcs = f.read().rstrip("\n")
with open(cmake_path) as f:
    content = f.read()
content = content.replace("@BOLT_BRIDGE_SRCS@", srcs)
with open(cmake_path, "w") as f:
    f.write(content)
PY
rm -f "$SRC_LIST_FILE"

# ----------------------------------------------------------------------------
# 4. Verify the substitutions left no engine residue, then print the summary.
# ----------------------------------------------------------------------------
RESIDUE_NS=$(grep -arl 'facebook::velox' "$OUT_DIR" 2>/dev/null | wc -l | tr -d ' ' || true)
RESIDUE_INC=$(grep -ar '#include "velox/\|#include <velox/' "$OUT_DIR" 2>/dev/null | wc -l | tr -d ' ' || true)

echo ""
echo "=== gen-bolt-cpp summary ==="
echo "  generated files (cc/h/...): $(find "$OUT_DIR" -type f \( -name '*.cc' -o -name '*.cpp' -o -name '*.h' -o -name '*.hpp' -o -name '*.inc' \) | wc -l | tr -d ' ')"
echo "  library sources compiled  : ${N_SRCS}"
echo "  substitutions applied:"
echo "    #include \"velox/  -> #include \"bolt/   : ${N_INC_QUOTE}"
echo "    #include <velox/  -> #include <bolt/   : ${N_INC_ANGLE}"
echo "    facebook::velox   -> bytedance::bolt   : ${N_NS}  (+${N_SYMMAP} in symbols.map)"
echo "    kVeloxBackendKind{\"velox\"} -> {\"bolt\"} : ${N_KIND}"
echo "  hand-written overrides applied (cpp/bolt/substrait/) : ${N_OVERRIDES}"
echo "  residue check:"
echo "    files still containing facebook::velox : ${RESIDUE_NS} (expect 0)"
echo "    lines still containing #include velox/ : ${RESIDUE_INC} (expect 0)"

if [[ "$RESIDUE_NS" != "0" || "$RESIDUE_INC" != "0" ]]; then
  echo "ERROR: engine residue remains after substitution" >&2
  exit 1
fi
echo "=== done ==="
