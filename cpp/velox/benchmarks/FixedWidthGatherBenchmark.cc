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

#include <benchmark/benchmark.h>

#include <numeric>
#include <random>
#include <vector>

#include "shuffle/FixedWidthGather.h"

namespace gluten {
namespace {

template <typename T, bool optimized>
void fixedWidthGather(benchmark::State& state) {
  constexpr uint32_t kRows = 8192;
  const auto partitions = state.range(0);
  const auto columns = state.range(1);
  std::mt19937 rng(42);
  std::vector<std::vector<uint32_t>> rowIds(partitions);
  for (uint32_t row = 0; row < kRows; ++row) {
    rowIds[rng() % partitions].push_back(row);
  }
  std::vector<T> src(kRows * columns);
  std::iota(src.begin(), src.end(), T{0});
  std::vector<std::vector<T>> dst(partitions);
  std::vector<int32_t> usedPartitions;
  for (int32_t pid = 0; pid < partitions; ++pid) {
    dst[pid].resize(rowIds[pid].size());
    if (!rowIds[pid].empty()) {
      usedPartitions.push_back(pid);
    }
  }
  for (auto _ : state) {
    for (int32_t col = 0; col < columns; ++col) {
      const auto* values = src.data() + col * kRows;
      for (auto pid : usedPartitions) {
        const auto* ids = rowIds[pid].data();
        auto* output = dst[pid].data();
        const uint32_t size = rowIds[pid].size();
        if constexpr (optimized) {
          detail::gatherFixedWidth(values, ids, size, output);
        } else {
          for (uint32_t pos = 0; pos < size; ++pos) {
            output[pos] = values[ids[pos]];
          }
        }
      }
    }
    benchmark::ClobberMemory();
  }
  state.SetItemsProcessed(state.iterations() * kRows * columns);
}

void gatherArgs(benchmark::internal::Benchmark* benchmark) {
  for (auto partitions : {8, 64, 256, 1000, 4096}) {
    for (auto columns : {1, 16}) {
      benchmark->Args({partitions, columns});
    }
  }
}

BENCHMARK_TEMPLATE(fixedWidthGather, uint32_t, false)->Apply(gatherArgs);
BENCHMARK_TEMPLATE(fixedWidthGather, uint32_t, true)->Apply(gatherArgs);
BENCHMARK_TEMPLATE(fixedWidthGather, uint64_t, false)->Apply(gatherArgs);
BENCHMARK_TEMPLATE(fixedWidthGather, uint64_t, true)->Apply(gatherArgs);

} // namespace
} // namespace gluten

BENCHMARK_MAIN();
