/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include <cstdint>

#if defined(__ARM_FEATURE_SVE)
#include <arm_sve.h>
#endif

namespace gluten::detail {

inline uint8_t gatherBitmapByte(const uint8_t* src, const uint32_t* rowIds, uint32_t count, bool enableSve) {
#if defined(__ARM_FEATURE_SVE) && (!defined(__GNUC__) || defined(__clang__) || __GNUC__ > 12)
  if (enableSve && count == 8) {
    uint8_t result = 0;
    for (uint32_t i = 0; i < count; i += svcntw()) {
      const auto active = svwhilelt_b32(i, count);
      const auto rows = svld1_u32(active, rowIds + i);
      const auto bytes = svld1ub_gather_u32offset_u32(active, src, svlsr_n_u32_x(active, rows, 3));
      const auto shifts = svand_n_u32_x(active, rows, 7);
      const auto bits = svand_n_u32_x(active, svlsr_u32_x(active, bytes, shifts), 1);
      result |= svaddv_u32(active, svlsl_u32_x(active, bits, svindex_u32(i, 1)));
    }
    return result;
  }
#endif
  uint8_t result = 0;
#if defined(__GNUC__)
#pragma GCC unroll 8
#endif
  for (uint32_t i = 0; i < count; ++i) {
    const auto row = rowIds[i];
    result |= ((src[row >> 3] >> (row & 7)) & 1) << i;
  }
  return result;
}

// Append a row selection to a little-endian bool/validity bitmap. Preserve
// existing prefix bits; fill unused tail bits with ones like the writer's
// original implementation. Inputs and output must not overlap.
inline void gatherBitmap(
    const uint8_t* __restrict__ src,
    const uint32_t* __restrict__ rowIds,
    uint32_t count,
    uint8_t* __restrict__ dst,
    uint32_t dstOffset,
    bool enableSve = true) {
  uint32_t position = 0;
  while (position < count && (dstOffset & 7)) {
    const auto row = rowIds[position++];
    const auto bit = (src[row >> 3] >> (row & 7)) & 1;
    const auto mask = uint8_t{1} << (dstOffset & 7);
    auto& output = dst[dstOffset >> 3];
    output = (output & ~mask) | (bit << (dstOffset & 7));
    ++dstOffset;
  }
  while (count - position >= 8) {
    dst[dstOffset >> 3] = gatherBitmapByte(src, rowIds + position, 8, enableSve);
    position += 8;
    dstOffset += 8;
  }
  if (position < count) {
    const auto remaining = count - position;
    dst[dstOffset >> 3] = gatherBitmapByte(src, rowIds + position, remaining, false) | (0xff << remaining);
  }
}

} // namespace gluten::detail
