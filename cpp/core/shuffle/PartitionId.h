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

#include <algorithm>
#include <cassert>
#include <cstdint>

#if defined(__ARM_FEATURE_SVE)
#include <arm_sve.h>
#endif

namespace gluten::detail {

// Spark pmod(hash, partitions). Precompute the reciprocal once per writer;
// every signed 32-bit hash, including INT_MIN, keeps exactly the same PID.
class PartitionIdModulus {
 public:
  explicit PartitionIdModulus(uint32_t divisor) : divisor_(divisor) {
    assert(divisor > 0 && divisor <= 0x7fffffff);
    reciprocal_ = static_cast<uint32_t>((uint64_t{1} << 32) / divisor);
    negativeBias_ = (uint64_t{1} << 32) % divisor;
    powerOfTwo_ = (divisor & (divisor - 1)) == 0;
  }

  uint32_t operator()(int32_t hash) const {
    const auto value = static_cast<uint32_t>(hash);
    if (powerOfTwo_) {
      return value & (divisor_ - 1);
    }
    const auto quotient = (uint64_t{value} * reciprocal_) >> 32;
    uint32_t remainder = value - quotient * divisor_;
    // The reciprocal rounds down, so this estimate is at most one short.
    remainder = std::min(remainder, remainder - divisor_);
    remainder -= (value >> 31) * negativeBias_;
    return std::min(remainder, remainder + divisor_);
  }

  void compute(const int32_t* hashes, int64_t size, uint32_t* result) const {
#if defined(__ARM_FEATURE_SVE)
    const auto divisor = svdup_u32(divisor_);
    const auto reciprocal = svdup_u32(reciprocal_);
    const auto bias = svdup_u32(negativeBias_);
    for (uint64_t i = 0; i < static_cast<uint64_t>(size); i += svcntw()) {
      const auto active = svwhilelt_b32(i, static_cast<uint64_t>(size));
      const auto values = svreinterpret_u32_s32(svld1_s32(active, hashes + i));
      if (powerOfTwo_) {
        svst1_u32(active, result + i, svand_n_u32_x(active, values, divisor_ - 1));
      } else {
        const auto quotient = svmulh_u32_x(active, values, reciprocal);
        auto remainder = svmls_u32_x(active, values, quotient, divisor);
        remainder = svmin_u32_x(active, remainder, svsub_u32_x(active, remainder, divisor));
        const auto negative = svcmplt_n_s32(active, svreinterpret_s32_u32(values), 0);
        remainder = svsub_u32_x(active, remainder, svsel_u32(negative, bias, svdup_u32(0)));
        remainder = svmin_u32_x(active, remainder, svadd_u32_x(active, remainder, divisor));
        svst1_u32(active, result + i, remainder);
      }
    }
#else
    for (int64_t i = 0; i < size; ++i) {
      result[i] = (*this)(hashes[i]);
    }
#endif
  }

 private:
  uint32_t divisor_;
  uint32_t reciprocal_;
  uint32_t negativeBias_;
  bool powerOfTwo_;
};

} // namespace gluten::detail
