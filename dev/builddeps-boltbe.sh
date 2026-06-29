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
# builddeps-boltbe.sh  --  joint native build of Gluten with the external Bolt engine.
#
# Mirrors dev/builddeps-veloxbe.sh, but for the Bolt backend. It:
#   (a) builds + exports the bolt conan package from $BOLT_HOME (../bolt by default),
#       OR uses a prebuilt bolt (set BOLT_HOME / BOLT_BUILD_PATH to skip the conan build);
#   (b) runs dev/gen-bolt-cpp.sh to generate the Gluten<->Bolt bridge from cpp/velox;
#   (c) configures gluten cpp with -DBUILD_VELOX_BACKEND=OFF -DBUILD_BOLT_BACKEND=ON
#       plus the bolt locate vars;
#   (d) builds libbolt.so.
#
# PREREQUISITE (conan): Bolt's native dependencies are managed by conan (see
#   bolt/Makefile and bolt/conanfile.py). The conan path requires `conan`,
#   `ninja` and a C++23 toolchain, plus network access to fetch recipes the first
#   time. Building a Velox-class engine from scratch is multi-hour / multi-GB.
#   If you already have a built bolt (conan cache OR a source-tree build dir),
#   set BOLT_HOME / BOLT_BUILD_PATH and pass `--build_bolt=OFF` to skip step (a).
#
#   Reference docker image with the toolchain preinstalled:
#     bolt/.devcontainer / the centos8-bolt image referenced by bolt's CI.
####################################################################################################
set -exu

CURRENT_DIR=$(cd "$(dirname "$BASH_SOURCE")"; pwd)
GLUTEN_DIR="$CURRENT_DIR/.."

BUILD_TYPE=Release
BUILD_TESTS=OFF
BUILD_BENCHMARKS=OFF
# bytedance/bolt checked out as a sibling of gluten by default.
BOLT_HOME="${BOLT_HOME:-$GLUTEN_DIR/../bolt}"
# When set, link an already-built bolt source tree instead of the conan package.
BOLT_BUILD_PATH="${BOLT_BUILD_PATH:-}"
# Whether to (re)build + export the bolt conan package from $BOLT_HOME.
BUILD_BOLT=ON
# conan build variant target in bolt/Makefile (release / release_spark / debug ...).
BOLT_MAKE_TARGET="${BOLT_MAKE_TARGET:-release_spark}"
BOLT_BUILD_VERSION="${BOLT_BUILD_VERSION:-main}"
# Generated bridge output (git-ignored, never committed).
BOLT_GEN_DIR="$GLUTEN_DIR/cpp/build/bolt_gen"

if [[ "$(uname)" == "Darwin" ]]; then
    NUM_THREADS=${NUM_THREADS:-$(sysctl -n hw.physicalcpu)}
else
    NUM_THREADS=${NUM_THREADS:-$(nproc --ignore=2)}
fi

for arg in "$@"; do
    case $arg in
        --build_type=*)       BUILD_TYPE="${arg#*=}"; shift ;;
        --build_tests=*)      BUILD_TESTS="${arg#*=}"; shift ;;
        --build_benchmarks=*) BUILD_BENCHMARKS="${arg#*=}"; shift ;;
        --build_bolt=*)       BUILD_BOLT="${arg#*=}"; shift ;;
        --bolt_home=*)        BOLT_HOME="${arg#*=}"; shift ;;
        --bolt_build_path=*)  BOLT_BUILD_PATH="${arg#*=}"; shift ;;
        --bolt_make_target=*) BOLT_MAKE_TARGET="${arg#*=}"; shift ;;
        --bolt_build_version=*) BOLT_BUILD_VERSION="${arg#*=}"; shift ;;
        --num_threads=*)      NUM_THREADS="${arg#*=}"; shift ;;
        *) OTHER_ARGUMENTS+=("$1"); shift ;;
    esac
done

export BOLT_HOME

# (a) Build + export the bolt conan package.  This is the heavy, conan-driven
#     step. It is skipped when --build_bolt=OFF or when BOLT_BUILD_PATH is set
#     (source-tree link mode).
function build_bolt_conan {
  if [ "$BUILD_BOLT" != "ON" ] || [ -n "$BOLT_BUILD_PATH" ]; then
    echo "Skipping bolt conan build (BUILD_BOLT=$BUILD_BOLT, BOLT_BUILD_PATH=$BOLT_BUILD_PATH)"
    return
  fi
  if ! command -v conan >/dev/null 2>&1; then
    echo "ERROR: conan not found. Install conan (and ninja + a C++23 toolchain), or"
    echo "       pass --build_bolt=OFF with a prebuilt BOLT_HOME/BOLT_BUILD_PATH." >&2
    exit 1
  fi
  echo "Start to build + export Bolt conan package from $BOLT_HOME (target=$BOLT_MAKE_TARGET)"
  pushd "$BOLT_HOME"
  # Build the engine (see bolt/Makefile: conan install ../.. + conan build).
  make "$BOLT_MAKE_TARGET" BUILD_TYPE="$BUILD_TYPE" BUILD_VERSION="$BOLT_BUILD_VERSION"
  # Export the built package into the local conan cache so find_package(bolt) works.
  make export_base BUILD_TYPE="$BUILD_TYPE" BUILD_VERSION="$BOLT_BUILD_VERSION"
  popd
}

# (b) Generate the Gluten<->Bolt bridge sources from cpp/velox.
function gen_bolt_bridge {
  echo "Start to generate Bolt bridge sources -> $BOLT_GEN_DIR"
  bash "$GLUTEN_DIR/dev/gen-bolt-cpp.sh" "$BOLT_GEN_DIR"
}

# (c)+(d) Configure + build gluten cpp with the Bolt backend only.
function build_gluten_cpp_bolt {
  echo "Start to build Gluten CPP (Bolt backend)"
  cd "$GLUTEN_DIR/cpp"
  rm -rf build
  mkdir build
  cd build

  GLUTEN_CMAKE_OPTIONS="-DBUILD_VELOX_BACKEND=OFF \
    -DBUILD_BOLT_BACKEND=ON \
    -DCMAKE_BUILD_TYPE=$BUILD_TYPE \
    -DBOLT_HOME=$BOLT_HOME \
    -DBOLT_GEN_DIR=$BOLT_GEN_DIR \
    -DBOLT_SKIP_CODEGEN=ON \
    -DBUILD_TESTS=$BUILD_TESTS \
    -DBUILD_BENCHMARKS=$BUILD_BENCHMARKS \
    -DCMAKE_EXPORT_COMPILE_COMMANDS=ON"

  # Source-tree link mode (no conan package): point at a built bolt tree.
  if [ -n "$BOLT_BUILD_PATH" ]; then
    GLUTEN_CMAKE_OPTIONS+=" -DBOLT_BUILD_PATH=$BOLT_BUILD_PATH"
  fi

  cmake -G Ninja $GLUTEN_CMAKE_OPTIONS ..
  ninja -j "$NUM_THREADS"
}

function build_bolt_backend {
  build_bolt_conan
  gen_bolt_bridge
  build_gluten_cpp_bolt
}

OS=$(uname -s)
ARCH=$(uname -m)
commands_to_run=(${OTHER_ARGUMENTS[@]:-})
(
  if [[ ${#commands_to_run[@]} -eq 0 ]]; then
    build_bolt_backend
  else
    echo "Commands to run: ${commands_to_run[@]}"
    for cmd in "${commands_to_run[@]}"; do
      "${cmd}"
    done
  fi
)
