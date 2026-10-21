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
#include <cstdint>
#include <limits>
#include <optional>

namespace gluten::detail {
// A task may request an optional larger partial buffer within its existing
// offheap budget. Explicit user caps always win. Unknown budgets cannot grow.
inline int64_t computeAdaptivePartialAggregationLimit(
    bool enabled,
    int64_t taskOffheap,
    std::optional<int64_t> explicitPartial,
    std::optional<int64_t> explicitExtended,
    int64_t requestedBytes) {
  if (!enabled || taskOffheap <= 0 || taskOffheap == std::numeric_limits<int64_t>::max() ||
      explicitPartial.has_value() || explicitExtended.has_value() || requestedBytes <= 0) {
    return 0;
  }
  return std::min({requestedBytes, int64_t{1} << 30, taskOffheap / 2});
}
} // namespace gluten::detail
