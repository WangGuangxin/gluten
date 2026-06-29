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
# builddeps-boltbe.sh  --  one-command joint native build of Gluten + the Bolt engine.
#
# This is the dev/builddeps-veloxbe.sh analogue for the Bolt backend. It folds the
# PR #11261 root-Makefile targets (bolt-recipe / build / arrow) into a single
# script so the joint compilation recipe matches the original, working flow:
#
#   PR #11261 Makefile                       this script (function)
#   ----------------------------------       ------------------------------------
#   (dev/install-conan.sh)                   install_conan
#   make bolt-recipe BOLT_BUILD_VERSION=..   bolt_recipe
#   make release / make debug                build_gluten_cpp_bolt   (conan install + cmake)
#   make arrow                               build_bolt_arrow        (dev/build_bolt_arrow.sh)
#
# Conan coordinates are IDENTICAL to the PR:
#   * bolt recipe : name=bolt  version=$BOLT_BUILD_VERSION  user=$BUILD_USER  channel=$BUILD_CHANNEL
#                   (exported from bytedance/bolt's conanfile.py; target bolt::bolt)
#   * gluten      : cpp/conanfile.py requires bolt/<ver>@<user>/<channel> and sets the
#                   ENABLE_BOLT cmake cache var -> cpp/CMakeLists.txt includes
#                   cpp/bolt.CMakeLists.cmake.
#
# THE ONLY DEVIATION FROM THE PR: the Gluten<->Bolt C++ bridge is GENERATED from
# cpp/velox at build time by dev/gen-bolt-cpp.sh (cpp/bolt/CMakeLists.txt drives
# it during cmake configure), instead of a committed sed-renamed copy of cpp/velox.
#
# TOOLCHAIN (same as the PR): GCC 10/11/12 or Clang 16, conan 2.x, Ninja, JDK 11/17.
# Building a Velox-class engine from scratch is multi-hour / multi-GB the first time
# (conan compiles all missing third-party deps). Use the reference docker images
# dev/docker/Dockerfile.centos8-bolt or dev/docker/Dockerfile.ubuntu22-bolt.
#
# QUICK START (mirrors docs/velox-to-bolt-migration-guide.md):
#   export JAVA_HOME=/path/to/jdk11
#   bash dev/builddeps-boltbe.sh                      # conan flow: recipe + cpp + arrow
#   # then package the jar:
#   ./build/mvn package -Pbackends-bolt -Pspark-3.5 -DskipTests
#
# Individual steps can be run as subcommands, e.g.:
#   bash dev/builddeps-boltbe.sh install_conan
#   bash dev/builddeps-boltbe.sh bolt_recipe
#   bash dev/builddeps-boltbe.sh build_gluten_cpp_bolt
#   bash dev/builddeps-boltbe.sh build_bolt_arrow
#
# Prebuilt-bolt / no-conan fallback (skips bolt_recipe + uses a source tree):
#   BOLT_BUILD_PATH=/path/to/built/bolt bash dev/builddeps-boltbe.sh --build_bolt=OFF
####################################################################################################
set -exu

CURRENT_DIR=$(cd "$(dirname "$BASH_SOURCE")"; pwd)
GLUTEN_DIR="$CURRENT_DIR/.."

BUILD_TYPE=Release
BUILD_TESTS=OFF
BUILD_BENCHMARKS=OFF
ENABLE_HDFS=ON
ENABLE_S3=OFF
SHARED_LIBRARY=ON
# bytedance/bolt checked out as a sibling of gluten by default; used as the
# recipe source and (in source-tree mode) as the engine header root.
BOLT_HOME="${BOLT_HOME:-$GLUTEN_DIR/../bolt}"
# When set, link an already-built bolt source tree instead of the conan package
# (skips bolt_recipe / conan resolution of bolt).
BOLT_BUILD_PATH="${BOLT_BUILD_PATH:-}"
# Whether to export the bolt conan recipe (bolt_recipe step).
BUILD_BOLT=ON
# Whether to also build the Arrow-for-bolt Java libs (make arrow). Off by default
# because it is only needed right before `mvn package`.
BUILD_ARROW=OFF
# Whether to (re)install conan + toolchain check (dev/install-conan.sh).
INSTALL_CONAN=ON
# conan package coordinates (same names/semantics as the PR Makefile).
BOLT_BUILD_VERSION="${BOLT_BUILD_VERSION:-main}"
GLUTEN_BUILD_VERSION="${GLUTEN_BUILD_VERSION:-main}"
BUILD_USER="${BUILD_USER:-}"
BUILD_CHANNEL="${BUILD_CHANNEL:-}"
# Generated bridge output (git-ignored under cpp/build, never committed).
BOLT_GEN_DIR="$GLUTEN_DIR/cpp/build/bolt_gen"

if [[ "$(uname)" == "Darwin" ]]; then
    NUM_THREADS=${NUM_THREADS:-$(sysctl -n hw.physicalcpu)}
else
    NUM_THREADS=${NUM_THREADS:-$(nproc --ignore=2)}
fi

for arg in "$@"; do
    case $arg in
        --build_type=*)        BUILD_TYPE="${arg#*=}"; shift ;;
        --build_tests=*)       BUILD_TESTS="${arg#*=}"; shift ;;
        --build_benchmarks=*)  BUILD_BENCHMARKS="${arg#*=}"; shift ;;
        --build_bolt=*)        BUILD_BOLT="${arg#*=}"; shift ;;
        --build_arrow=*)       BUILD_ARROW="${arg#*=}"; shift ;;
        --install_conan=*)     INSTALL_CONAN="${arg#*=}"; shift ;;
        --enable_hdfs=*)       ENABLE_HDFS="${arg#*=}"; shift ;;
        --enable_s3=*)         ENABLE_S3="${arg#*=}"; shift ;;
        --shared=*)            SHARED_LIBRARY="${arg#*=}"; shift ;;
        --bolt_home=*)         BOLT_HOME="${arg#*=}"; shift ;;
        --bolt_build_path=*)   BOLT_BUILD_PATH="${arg#*=}"; shift ;;
        --bolt_build_version=*) BOLT_BUILD_VERSION="${arg#*=}"; shift ;;
        --gluten_build_version=*) GLUTEN_BUILD_VERSION="${arg#*=}"; shift ;;
        --build_user=*)        BUILD_USER="${arg#*=}"; shift ;;
        --build_channel=*)     BUILD_CHANNEL="${arg#*=}"; shift ;;
        --num_threads=*)       NUM_THREADS="${arg#*=}"; shift ;;
        *) OTHER_ARGUMENTS+=("$1"); shift ;;
    esac
done

export BOLT_HOME
export BOLT_BUILD_VERSION

# conan --user/--channel are optional; only pass them when non-empty.
USER_CHANNEL_ARGS=""
[ -n "$BUILD_USER" ]    && USER_CHANNEL_ARGS="$USER_CHANNEL_ARGS --user=$BUILD_USER"
[ -n "$BUILD_CHANNEL" ] && USER_CHANNEL_ARGS="$USER_CHANNEL_ARGS --channel=$BUILD_CHANNEL"

# ---------------------------------------------------------------------------
# install_conan  (== dev/install-conan.sh): checks the GCC version and installs
# conan + a default profile (gnu17). No-op friendly.
# ---------------------------------------------------------------------------
function install_conan {
  if [ "$INSTALL_CONAN" != "ON" ]; then
    echo "Skipping conan install (INSTALL_CONAN=$INSTALL_CONAN)"
    return
  fi
  bash "$GLUTEN_DIR/dev/install-conan.sh"
}

# ---------------------------------------------------------------------------
# bolt_recipe  (== make bolt-recipe): install bolt's third-party deps and export
# its conan recipe into the local cache so cpp/conanfile.py can require
# bolt/<ver>@<user>/<channel>. Uses the local $BOLT_HOME checkout when present,
# otherwise clones bytedance/bolt at $BOLT_BUILD_VERSION (PR Makefile behavior).
# ---------------------------------------------------------------------------
function bolt_recipe {
  if [ "$BUILD_BOLT" != "ON" ] || [ -n "$BOLT_BUILD_PATH" ]; then
    echo "Skipping bolt_recipe (BUILD_BOLT=$BUILD_BOLT, BOLT_BUILD_PATH=$BOLT_BUILD_PATH)"
    return
  fi
  if ! command -v conan >/dev/null 2>&1; then
    echo "ERROR: conan not found. Run 'bash dev/builddeps-boltbe.sh install_conan' first," >&2
    echo "       or pass --build_bolt=OFF with a prebuilt BOLT_HOME/BOLT_BUILD_PATH." >&2
    exit 1
  fi
  local recipe_dir="$BOLT_HOME"
  if [ ! -f "$recipe_dir/conanfile.py" ]; then
    echo "No bolt checkout at $BOLT_HOME; cloning bytedance/bolt@$BOLT_BUILD_VERSION into ep/bolt"
    rm -rf "$GLUTEN_DIR/ep/bolt"
    git clone --depth=1 --branch "$BOLT_BUILD_VERSION" \
      https://github.com/bytedance/bolt.git "$GLUTEN_DIR/ep/bolt"
    recipe_dir="$GLUTEN_DIR/ep/bolt"
  fi
  echo "Installing bolt deps + exporting bolt recipe from $recipe_dir"
  bash "$recipe_dir/scripts/install-bolt-deps.sh"
  conan export "$recipe_dir/conanfile.py" --name=bolt \
    --version="$BOLT_BUILD_VERSION" $USER_CHANNEL_ARGS
}

# ---------------------------------------------------------------------------
# gen_bolt_bridge: generate the Gluten<->Bolt bridge from cpp/velox. In the conan
# flow cmake also runs this during configure; we pre-generate so a standalone
# `gen_bolt_bridge` subcommand works too.
# ---------------------------------------------------------------------------
function gen_bolt_bridge {
  echo "Generating Bolt bridge sources -> $BOLT_GEN_DIR"
  bash "$GLUTEN_DIR/dev/gen-bolt-cpp.sh" "$BOLT_GEN_DIR"
}

# ---------------------------------------------------------------------------
# build_gluten_cpp_bolt  (== make build): conan install (resolves bolt::bolt and
# all transitive deps, building any that are missing) + cmake preset build. The
# conanfile sets ENABLE_BOLT, so cpp/CMakeLists.txt includes bolt.CMakeLists.cmake
# and the bridge (cpp/bolt) is generated + compiled into libbolt.so.
# ---------------------------------------------------------------------------
function build_gluten_cpp_bolt {
  echo "Building Gluten CPP (Bolt backend) via conan"
  if ! command -v conan >/dev/null 2>&1; then
    echo "ERROR: conan not found (run install_conan)." >&2
    exit 1
  fi
  cd "$GLUTEN_DIR/cpp"
  local all_conan_options=" -o gluten/*:shared=$( [ "$SHARED_LIBRARY" = "ON" ] && echo True || echo False ) \
    -o gluten/*:enable_hdfs=$( [ "$ENABLE_HDFS" = "ON" ] && echo True || echo False ) \
    -o gluten/*:enable_s3=$( [ "$ENABLE_S3" = "ON" ] && echo True || echo False ) \
    -o gluten/*:build_tests=$( [ "$BUILD_TESTS" = "ON" ] && echo True || echo False ) \
    -o gluten/*:build_benchmarks=$( [ "$BUILD_BENCHMARKS" = "ON" ] && echo True || echo False ) "

  NUM_THREADS=$NUM_THREADS conan install . --name=gluten \
    --version="$GLUTEN_BUILD_VERSION" $USER_CHANNEL_ARGS \
    -s llvm-core/*:build_type=Release -s build_type="$BUILD_TYPE" \
    --build=missing $all_conan_options

  local preset
  preset="conan-$(echo "$BUILD_TYPE" | tr '[:upper:]' '[:lower:]')"
  cmake --preset "$preset"
  cmake --build "build/$BUILD_TYPE" -j "$NUM_THREADS"
  if [ "$SHARED_LIBRARY" = "ON" ]; then
    cmake --build "build/$BUILD_TYPE" --target install
  fi
}

# ---------------------------------------------------------------------------
# build_gluten_cpp_bolt_sourcetree: no-conan fallback. Links an already-built
# bolt tree ($BOLT_BUILD_PATH) and drives the bridge codegen + cmake/ninja
# directly. Transitive engine deps must already be discoverable.
# ---------------------------------------------------------------------------
function build_gluten_cpp_bolt_sourcetree {
  echo "Building Gluten CPP (Bolt backend) in source-tree mode (BOLT_BUILD_PATH=$BOLT_BUILD_PATH)"
  gen_bolt_bridge
  cd "$GLUTEN_DIR/cpp"
  rm -rf build
  mkdir build
  cd build
  cmake -G Ninja \
    -DBUILD_BOLT_BACKEND=ON \
    -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
    -DBOLT_HOME="$BOLT_HOME" \
    -DBOLT_BUILD_PATH="$BOLT_BUILD_PATH" \
    -DBOLT_GEN_DIR="$BOLT_GEN_DIR" \
    -DBOLT_SKIP_CODEGEN=ON \
    -DBUILD_TESTS="$BUILD_TESTS" \
    -DBUILD_BENCHMARKS="$BUILD_BENCHMARKS" \
    -DCMAKE_EXPORT_COMPILE_COMMANDS=ON \
    ..
  ninja -j "$NUM_THREADS"
}

# ---------------------------------------------------------------------------
# build_bolt_arrow  (== make arrow): build + install the Arrow-for-bolt Java libs.
# ---------------------------------------------------------------------------
function build_bolt_arrow {
  if [ "$BUILD_ARROW" != "ON" ]; then
    echo "Skipping arrow build (BUILD_ARROW=$BUILD_ARROW; run with --build_arrow=ON or the build_bolt_arrow subcommand)"
    return
  fi
  bash "$GLUTEN_DIR/dev/build_bolt_arrow.sh"
}

# ---------------------------------------------------------------------------
# Default end-to-end flow.
# ---------------------------------------------------------------------------
function build_bolt_backend {
  if [ -n "$BOLT_BUILD_PATH" ]; then
    # Prebuilt source-tree mode: no conan resolution of bolt.
    build_gluten_cpp_bolt_sourcetree
  else
    install_conan
    bolt_recipe
    build_gluten_cpp_bolt
  fi
  build_bolt_arrow
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
