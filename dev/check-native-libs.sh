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

# Checks how the native libraries were compiled, independent of the host OS or compiler defaults:
# distribution hardening left in the code (stack protector calls, _FORTIFY_SOURCE *_chk calls) and,
# on aarch64, the instruction-set level actually emitted (SVE instructions, LSE vs. outline atomics).
# Writes <releases dir>/native-build-check.json.
#
# With hardening turned off (the default, see get_distro_hardening_off_flags), a release libvelox.so
# has a few hundred to ~1,200 __stack_chk_fail call sites, all from prebuilt dependencies; built with
# Ubuntu GCC's default hardening it has ~60,000 plus ~4,000 *_chk calls, which costs ~3-4% on NDS.
# Mismatches print a warning; GLUTEN_NATIVE_CHECK_STRICT=ON turns them into a build failure.
#
# Usage: check-native-libs.sh <dir with libvelox.so / libgluten.so>

set -eu

DIR=${1:?usage: check-native-libs.sh <releases dir>}
MAX_STACK_CHK=${GLUTEN_MAX_STACK_CHK_CALLS:-10000}
MAX_FORTIFY=${GLUTEN_MAX_FORTIFY_CALLS:-1000}
EXPECT_HARDENING_OFF=$([ "${VELOX_KEEP_DISTRO_HARDENING:-OFF}" = "ON" ] && echo false || echo true)

if ! command -v objdump >/dev/null || ! command -v readelf >/dev/null; then
  echo "WARNING: objdump/readelf not found; skipping the native library check." >&2
  exit 0
fi

problems=()
json="{\"expect_hardening_off\": ${EXPECT_HARDENING_OFF}, \"arm_build_target\": \"${ARM_BUILD_TARGET:-}\", \"libraries\": {"
first=true
for lib in libvelox.so libgluten.so; do
  path="$DIR/$lib"
  [ -f "$path" ] || continue
  # One disassembly pass; counts call sites and a few instruction classes.
  read -r stack_chk fortify sve outline lse < <(objdump -d --no-show-raw-insn "$path" | LC_ALL=C awk '
    /\tbl?\t.*<__stack_chk_fail(@plt)?>/ { s++ }
    /\tbl?\t.*<__[a-z0-9_]+_chk(@plt)?>/ && !/__stack_chk_fail/ { f++ }
    /\t(whilelo|whilelt|ptrue)\t/ { v++ }
    /\tbl\t.*<__aarch64_(cas|swp|ldadd|ldclr|ldeor|ldset)/ { o++ }
    /\t(ldadd|ldaddal|ldaddl|ldadda|cas|casal|casl|casa|swp|swpal|swpl|swpa)[bh]?\t/ { l++ }
    END { printf "%d %d %d %d %d\n", s, f, v, o, l }')
  compilers=$(readelf -p .comment "$path" 2>/dev/null | grep -oE '(GCC|clang)[^]]*' | sort -u | paste -sd ';' -)
  needed=$(readelf -d "$path" | grep NEEDED | grep -oE '\[[^]]+\]' | tr -d '[]' | paste -sd ' ' -)
  $first || json+=", "
  first=false
  json+="\"$lib\": {\"stack_chk_fail_calls\": $stack_chk, \"fortify_chk_calls\": $fortify,"
  json+=" \"sve_predicate_insns\": $sve, \"outline_atomic_calls\": $outline, \"lse_atomic_insns\": $lse,"
  json+=" \"compilers\": \"$compilers\", \"needed\": \"$needed\"}"
  echo "$lib: __stack_chk_fail calls=$stack_chk, *_chk calls=$fortify, SVE predicate insns=$sve," \
    "outline atomic calls=$outline, LSE atomic insns=$lse; compilers: $compilers"
  if [ "$EXPECT_HARDENING_OFF" = true ]; then
    [ "$stack_chk" -le "$MAX_STACK_CHK" ] ||
      problems+=("$lib has $stack_chk __stack_chk_fail calls (> $MAX_STACK_CHK): compiler-default stack protector is on")
    [ "$fortify" -le "$MAX_FORTIFY" ] ||
      problems+=("$lib has $fortify _FORTIFY_SOURCE *_chk calls (> $MAX_FORTIFY): compiler-default fortify is on")
  fi
  if [ "$(uname -m)" = "aarch64" ] && [ "${ARM_BUILD_TARGET:-}" = "generic" ]; then
    [ "$sve" -eq 0 ] || problems+=("$lib has $sve SVE predicate instructions although ARM_BUILD_TARGET=generic (sources or prebuilt dependencies were compiled for an SVE CPU; the library will not run on ARMv8.0-v8.x CPUs without SVE)")
  fi
done
json+="}, \"problems\": ["
for i in "${!problems[@]}"; do
  [ "$i" -eq 0 ] || json+=", "
  json+="\"${problems[$i]}\""
done
json+="]}"
echo "$json" > "$DIR/native-build-check.json"

if [ ${#problems[@]} -gt 0 ]; then
  echo "================================================================================" >&2
  printf 'WARNING: native library check: %s\n' "${problems[@]}" >&2
  echo "See dev/check-native-libs.sh. Rebuild from a clean build directory, or set" >&2
  echo "VELOX_KEEP_DISTRO_HARDENING=ON if the hardening is intended." >&2
  echo "================================================================================" >&2
  if [ "${GLUTEN_NATIVE_CHECK_STRICT:-OFF}" = "ON" ]; then
    exit 1
  fi
fi
