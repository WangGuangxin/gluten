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

# ============================================================================
# Gluten <-> Bolt native bridge (joint build with github.com/bytedance/bolt).
#
# Instead of committing a wholesale copy of cpp/velox (PR #11261 copied ~183
# files), the Gluten<->Bolt bridge is GENERATED at build time from cpp/velox by
# dev/gen-bolt-cpp.sh and compiled against the external Bolt engine. Bolt is a
# Velox fork (namespace bytedance::bolt, include prefix bolt/, conan package
# bolt::bolt), so the bridge is the Velox bridge with engine-only substitutions.
#
# This file (committed) only does the wiring:
#   1. Locate the Bolt engine -> target bolt::bolt
#        a. conan:        find_package(bolt CONFIG)           (preferred)
#        b. source tree:  BOLT_HOME + BOLT_BUILD_PATH import  (fallback, mirrors
#                         the Velox import_library pattern for users who built
#                         bolt from /workspace/.../bolt without a conan package)
#   2. Run dev/gen-bolt-cpp.sh -> ${BOLT_GEN_DIR} (git-ignored, never committed)
#   3. add_subdirectory(${BOLT_GEN_DIR}) -> builds libbolt.so (target `bolt`)
#
# Gated by BUILD_BOLT_BACKEND (default OFF) in the top-level cpp/CMakeLists.txt,
# so the default Velox build is unaffected.
# ============================================================================

cmake_minimum_required(VERSION 3.16)

project(bolt)

# ----------------------------------------------------------------------------
# 0. Resolve BOLT_HOME (external bolt source checkout, sibling of gluten).
# ----------------------------------------------------------------------------
if(NOT DEFINED BOLT_HOME)
  if(DEFINED ENV{BOLT_HOME})
    set(BOLT_HOME $ENV{BOLT_HOME})
  else()
    set(BOLT_HOME ${GLUTEN_HOME}/../bolt)
  endif()
endif()
message(STATUS "Bolt backend: BOLT_HOME=${BOLT_HOME}")

# ----------------------------------------------------------------------------
# 1. Locate the Bolt engine -> bolt::bolt
# ----------------------------------------------------------------------------
# BOLT_BUILD_PATH (or env) selects the source-tree fallback. Otherwise we use
# the conan package, which also brings the engine's transitive dependencies
# (arrow/folly/re2/icu/...). The source-tree fallback imports only the engine
# archive; transitive deps must already be discoverable (documented limitation).
if(NOT DEFINED BOLT_BUILD_PATH AND DEFINED ENV{BOLT_BUILD_PATH})
  set(BOLT_BUILD_PATH $ENV{BOLT_BUILD_PATH})
endif()

if(DEFINED BOLT_BUILD_PATH)
  message(STATUS "Bolt backend: source-tree mode, BOLT_BUILD_PATH=${BOLT_BUILD_PATH}")
  # Mirror cpp/velox/CMakeLists.txt import_library(facebook::velox libvelox.a).
  # Bolt's main archive is libbolt_engine.* (see bolt conanfile package_info /
  # bolt/CMakeLists.txt add_library(bolt_engine ...)).
  set(_bolt_engine_lib "")
  foreach(_ext a so)
    if(EXISTS "${BOLT_BUILD_PATH}/lib/libbolt_engine.${_ext}")
      set(_bolt_engine_lib "${BOLT_BUILD_PATH}/lib/libbolt_engine.${_ext}")
      break()
    endif()
  endforeach()
  if(_bolt_engine_lib STREQUAL "")
    message(
      FATAL_ERROR
        "Bolt engine archive not found under ${BOLT_BUILD_PATH}/lib "
        "(looked for libbolt_engine.a / libbolt_engine.so). Build bolt first, "
        "or use the conan package and unset BOLT_BUILD_PATH.")
  endif()
  add_library(bolt::bolt STATIC IMPORTED GLOBAL)
  set_target_properties(
    bolt::bolt PROPERTIES IMPORTED_LOCATION "${_bolt_engine_lib}"
                          INTERFACE_INCLUDE_DIRECTORIES "${BOLT_HOME}")
else()
  message(STATUS "Bolt backend: conan mode, find_package(bolt CONFIG REQUIRED)")
  find_package(bolt CONFIG REQUIRED)
endif()

# ----------------------------------------------------------------------------
# 2. Generate the bridge sources from cpp/velox (never committed).
# ----------------------------------------------------------------------------
if(NOT DEFINED BOLT_GEN_DIR)
  set(BOLT_GEN_DIR ${CMAKE_BINARY_DIR}/bolt_gen)
endif()

# BOLT_SKIP_CODEGEN lets callers point at a pre-generated tree (e.g. produced by
# dev/builddeps-boltbe.sh) instead of regenerating during configure.
if(NOT BOLT_SKIP_CODEGEN)
  message(STATUS "Bolt backend: running dev/gen-bolt-cpp.sh -> ${BOLT_GEN_DIR}")
  execute_process(
    COMMAND bash ${GLUTEN_HOME}/dev/gen-bolt-cpp.sh ${BOLT_GEN_DIR}
    RESULT_VARIABLE _bolt_gen_rc)
  if(NOT _bolt_gen_rc EQUAL 0)
    message(FATAL_ERROR "dev/gen-bolt-cpp.sh failed (rc=${_bolt_gen_rc})")
  endif()
endif()

if(NOT EXISTS ${BOLT_GEN_DIR}/CMakeLists.txt)
  message(
    FATAL_ERROR
      "Generated bridge not found at ${BOLT_GEN_DIR}. Run dev/gen-bolt-cpp.sh "
      "or unset BOLT_SKIP_CODEGEN.")
endif()

# ----------------------------------------------------------------------------
# 3. Build libbolt.so from the generated tree (defines target `bolt`).
# ----------------------------------------------------------------------------
add_subdirectory(${BOLT_GEN_DIR} ${CMAKE_BINARY_DIR}/bolt_gen_build)
