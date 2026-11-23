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

#include "memory/MemoryAllocator.h"
#include <gtest/gtest.h>

#include <cstdint>
#include <thread>
#include <vector>

using namespace gluten;

TEST(StdMemoryAllocator, allocateZeroFilledAccounting) {
  StdMemoryAllocator allocator;
  ASSERT_EQ(allocator.getBytes(), 0);

  // allocateZeroFilled with nmemb=10, size=64 should track 10*64=640 bytes.
  const int64_t expectedBytes = 10 * 64;
  void* buf = nullptr;
  bool ok = allocator.allocateZeroFilled(10, 64, &buf);
  ASSERT_TRUE(ok);
  ASSERT_NE(buf, nullptr);
  ASSERT_EQ(allocator.getBytes(), expectedBytes);

  ASSERT_TRUE(allocator.free(buf, expectedBytes));
  ASSERT_EQ(allocator.getBytes(), 0);
}

TEST(StdMemoryAllocator, shrinkKeepsAlignment) {
  StdMemoryAllocator allocator;
  constexpr uint64_t kAlignment = 64;
  constexpr int64_t kSize = 1024;
  // On macOS, realloc can move a shrunk block to an address that is only 16-byte aligned.
  for (int64_t newSize = kAlignment; newSize < kSize; newSize += kAlignment) {
    void* buf = nullptr;
    ASSERT_TRUE(allocator.allocateAligned(kAlignment, kSize, &buf));
    for (int64_t i = 0; i < kSize; ++i) {
      static_cast<uint8_t*>(buf)[i] = static_cast<uint8_t>(i);
    }

    void* shrunk = nullptr;
    ASSERT_TRUE(allocator.reallocateAligned(buf, kAlignment, kSize, newSize, &shrunk));
    ASSERT_EQ(reinterpret_cast<uintptr_t>(shrunk) % kAlignment, 0) << "newSize " << newSize;
    for (int64_t i = 0; i < newSize; ++i) {
      ASSERT_EQ(static_cast<uint8_t*>(shrunk)[i], static_cast<uint8_t>(i)) << "newSize " << newSize;
    }
    ASSERT_EQ(allocator.getBytes(), newSize);

    ASSERT_TRUE(allocator.free(shrunk, newSize));
    ASSERT_EQ(allocator.getBytes(), 0);
  }
}

TEST(StdMemoryAllocator, concurrentAccounting) {
  StdMemoryAllocator allocator;
  constexpr int kNumThreads = 128;
  constexpr int kNumIterations = 1000;
  constexpr int64_t kSize = 256;
  // Every thread keeps one buffer per thread index alive so that the total is known while the threads run, and
  // frees and allocates it repeatedly. Threads are assigned to counter stripes round-robin; with more threads than
  // stripes, several threads share each stripe.
  std::vector<std::thread> threads;
  std::vector<void*> kept(kNumThreads, nullptr);
  for (int t = 0; t < kNumThreads; ++t) {
    threads.emplace_back([&, t]() {
      for (int i = 0; i < kNumIterations; ++i) {
        void* buf = nullptr;
        ASSERT_TRUE(allocator.allocate(kSize, &buf));
        ASSERT_TRUE(allocator.free(buf, kSize));
      }
      ASSERT_TRUE(allocator.allocate(kSize + t, &kept[t]));
    });
  }
  for (auto& thread : threads) {
    thread.join();
  }

  int64_t expected = 0;
  for (int t = 0; t < kNumThreads; ++t) {
    expected += kSize + t;
  }
  ASSERT_EQ(allocator.getBytes(), expected);

  // Freeing from a different thread than the allocating one moves bytes between stripes; only the sum matters.
  for (int t = 0; t < kNumThreads; ++t) {
    ASSERT_TRUE(allocator.free(kept[t], kSize + t));
  }
  ASSERT_EQ(allocator.getBytes(), 0);
}
