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

#include <cstddef>
#include <cstdint>
#include <limits>

#if defined(__aarch64__) && !defined(GLUTEN_BYTE_SHUFFLE_FORCE_CPP)
#include <arm_neon.h>
#define GLUTEN_BYTE_SHUFFLE_NEON_AVAILABLE 1
#else
#define GLUTEN_BYTE_SHUFFLE_NEON_AVAILABLE 0
#endif

namespace gluten::byte_shuffle {

#if GLUTEN_BYTE_SHUFFLE_NEON_AVAILABLE
struct Tile8 {
  uint8x8_t a0, a1, a2, a3, a4, a5, a6, a7;
};

// An8x8 byte transpose is its own inverse. Only internal inline helpers use
// NEON values; the callable complete API below has scalar arguments.
inline __attribute__((always_inline)) Tile8 transpose(Tile8 a) {
  const auto b0 = vtrn1_u8(a.a0, a.a1);
  const auto b1 = vtrn2_u8(a.a0, a.a1);
  const auto b2 = vtrn1_u8(a.a2, a.a3);
  const auto b3 = vtrn2_u8(a.a2, a.a3);
  const auto b4 = vtrn1_u8(a.a4, a.a5);
  const auto b5 = vtrn2_u8(a.a4, a.a5);
  const auto b6 = vtrn1_u8(a.a6, a.a7);
  const auto b7 = vtrn2_u8(a.a6, a.a7);
#define HALF(A, B, WHICH) vtrn##WHICH##_u16(vreinterpret_u16_u8(A), vreinterpret_u16_u8(B))
  const auto c0 = HALF(b0, b2, 1);
  const auto c1 = HALF(b0, b2, 2);
  const auto c2 = HALF(b1, b3, 1);
  const auto c3 = HALF(b1, b3, 2);
  const auto c4 = HALF(b4, b6, 1);
  const auto c5 = HALF(b4, b6, 2);
  const auto c6 = HALF(b5, b7, 1);
  const auto c7 = HALF(b5, b7, 2);
#undef HALF
#define WORD(A, B, WHICH) vreinterpret_u8_u32(vtrn##WHICH##_u32(vreinterpret_u32_u16(A), vreinterpret_u32_u16(B)))
  return {
      WORD(c0, c4, 1),
      WORD(c2, c6, 1),
      WORD(c1, c5, 1),
      WORD(c3, c7, 1),
      WORD(c0, c4, 2),
      WORD(c2, c6, 2),
      WORD(c1, c5, 2),
      WORD(c3, c7, 2)};
#undef WORD
}
#endif

// Exact n*width bytes, including every bit of signed, nullable or floating
// payloads. Input/output must be disjoint. Unsupported/overflow sizes return
// false before touching memory; zero values require no valid data pointers.
template <bool Decode>
inline bool
reorder(const uint8_t* __restrict input, uint8_t* __restrict output, size_t n, unsigned width, bool useNeon) {
  if ((width != 8 && width != 16) || n > std::numeric_limits<size_t>::max() / width) {
    return false;
  }
  if (n == 0) {
    return true;
  }
  size_t r = 0;
#if GLUTEN_BYTE_SHUFFLE_NEON_AVAILABLE
  if (useNeon) {
    for (; r + 8 <= n; r += 8) {
      for (unsigned b = 0; b < width; b += 8) {
        const auto stride = Decode ? n : width;
        const auto start = Decode ? b * n + r : r * width + b;
        const auto* source = input + start;
        auto tile = transpose(Tile8{
            vld1_u8(source),
            vld1_u8(source + stride),
            vld1_u8(source + stride * 2),
            vld1_u8(source + stride * 3),
            vld1_u8(source + stride * 4),
            vld1_u8(source + stride * 5),
            vld1_u8(source + stride * 6),
            vld1_u8(source + stride * 7)});
        const auto outStride = Decode ? width : n;
        const auto outStart = Decode ? r * width + b : b * n + r;
        auto* target = output + outStart;
        vst1_u8(target, tile.a0);
        vst1_u8(target + outStride, tile.a1);
        vst1_u8(target + outStride * 2, tile.a2);
        vst1_u8(target + outStride * 3, tile.a3);
        vst1_u8(target + outStride * 4, tile.a4);
        vst1_u8(target + outStride * 5, tile.a5);
        vst1_u8(target + outStride * 6, tile.a6);
        vst1_u8(target + outStride * 7, tile.a7);
      }
    }
  }
#endif
  (void)useNeon;
  if constexpr (Decode) {
    for (; r < n; ++r) {
      for (unsigned b = 0; b < width; ++b) {
        output[r * width + b] = input[b * n + r];
      }
    }
  } else {
    for (unsigned b = 0; b < width; ++b) {
      for (auto tail = r; tail < n; ++tail) {
        output[b * n + tail] = input[tail * width + b];
      }
    }
  }
  return true;
}

inline bool eligible(const uint8_t* input, int64_t bytes) {
  if (bytes < 8192 || bytes % 8 != 0) {
    return false;
  }
  const uint64_t count = bytes / 8;
  uint64_t first, second;
  std::memcpy(&first, input, 8);
  std::memcpy(&second, input + ((count - 1) / 31) * 8, 8);
  if (first == second || (first >> 32) != (second >> 32)) {
    return false;
  }
  const uint64_t step = (count - 1) / 31;
  const uint64_t remainder = (count - 1) % 31;
  uint64_t previous = first;
  unsigned changes = 0;
  for (uint64_t i = 1; i < 32; ++i) {
    uint64_t word;
    std::memcpy(&word, input + (i * step + (i * remainder) / 31) * 8, 8);
    if ((word >> 32) != (first >> 32)) {
      return false;
    }
    changes += word != previous;
    previous = word;
  }
  return changes >= 16;
}

} // namespace gluten::byte_shuffle

namespace gluten {
void setByteShuffleLz4Enabled(bool enabled);
void setArmNeonByteShuffleEnabled(bool enabled);
void setByteShuffleLz4LogUsage(bool enabled);
} // namespace gluten
