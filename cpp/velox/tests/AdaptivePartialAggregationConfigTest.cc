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
#include "compute/AdaptivePartialAggregationConfig.h"
#include <gtest/gtest.h>

namespace gluten::detail {
TEST(AdaptivePartialAggregationConfigTest, respectsBudgetsAndExplicitCaps) {
  constexpr int64_t mib = 1LL << 20;
  auto limit = [](bool enabled,
                  int64_t budget,
                  std::optional<int64_t> partial,
                  std::optional<int64_t> extended,
                  int64_t requested) {
    return computeAdaptivePartialAggregationLimit(enabled, budget, partial, extended, requested);
  };
  EXPECT_EQ(limit(true, 2816 * mib, {}, {}, 1024 * mib), 1024 * mib);
  EXPECT_EQ(limit(true, 128 * mib, {}, {}, 1024 * mib), 64 * mib);
  EXPECT_EQ(limit(true, 2816 * mib, {}, {}, 128 * mib), 128 * mib);
  EXPECT_EQ(limit(true, 4096 * mib, {}, {}, 2048 * mib), 1024 * mib);
  EXPECT_EQ(limit(false, 2816 * mib, {}, {}, 1024 * mib), 0);
  for (auto budget : {int64_t{-1}, int64_t{0}, std::numeric_limits<int64_t>::max()}) {
    EXPECT_EQ(limit(true, budget, {}, {}, 1024 * mib), 0);
  }
  for (auto userCap : {int64_t{0}, 256 * mib, 2048 * mib}) {
    EXPECT_EQ(limit(true, 2816 * mib, userCap, {}, 1024 * mib), 0);
    EXPECT_EQ(limit(true, 2816 * mib, {}, userCap, 1024 * mib), 0);
  }
  EXPECT_EQ(limit(true, 2816 * mib, {}, {}, 0), 0);
  EXPECT_EQ(limit(true, 2816 * mib, {}, {}, -1), 0);
}
} // namespace gluten::detail
