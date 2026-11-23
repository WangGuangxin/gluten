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
#include "memory/AllocationListener.h"

#include <cstdint>

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

namespace {
class CountingAllocationListener final : public AllocationListener {
 public:
  void allocationChanged(int64_t diff) override {
    ++numCalls_;
    bytes_ += diff;
  }

  int64_t currentBytes() override {
    return bytes_;
  }

  int64_t numCalls() const {
    return numCalls_;
  }

 private:
  int64_t bytes_{0};
  int64_t numCalls_{0};
};
} // namespace

TEST(BlockAllocationListener, keepsOneSpareBlockWhenShrinking) {
  constexpr int64_t kBlockSize = 1024;
  CountingAllocationListener delegated;
  {
    BlockAllocationListener listener(&delegated, kBlockSize);
    listener.allocationChanged(kBlockSize);
    ASSERT_EQ(delegated.currentBytes(), kBlockSize);
    ASSERT_EQ(delegated.numCalls(), 1);

    // Usage oscillating around a block boundary reserves the second block once and then keeps it.
    for (int i = 0; i < 100; ++i) {
      listener.allocationChanged(10);
      listener.allocationChanged(-10);
    }
    ASSERT_EQ(delegated.currentBytes(), 2 * kBlockSize);
    ASSERT_EQ(delegated.numCalls(), 2);

    // Shrinking to zero releases everything but the spare block.
    listener.allocationChanged(-kBlockSize);
    ASSERT_EQ(delegated.currentBytes(), kBlockSize);

    // Growing again within the spare block does not call the delegated listener.
    const auto numCalls = delegated.numCalls();
    listener.allocationChanged(kBlockSize / 2);
    ASSERT_EQ(delegated.numCalls(), numCalls);
    listener.allocationChanged(-kBlockSize / 2);
  }
  // The spare block is returned on destruction.
  ASSERT_EQ(delegated.currentBytes(), 0);
}

TEST(BlockAllocationListener, shrinksBeyondTheSpareBlock) {
  constexpr int64_t kBlockSize = 1024;
  CountingAllocationListener delegated;
  BlockAllocationListener listener(&delegated, kBlockSize);
  listener.allocationChanged(10 * kBlockSize);
  ASSERT_EQ(delegated.currentBytes(), 10 * kBlockSize);
  listener.allocationChanged(-8 * kBlockSize);
  // Two blocks are in use and one spare block is kept.
  ASSERT_EQ(delegated.currentBytes(), 3 * kBlockSize);
  listener.allocationChanged(-2 * kBlockSize);
  ASSERT_EQ(delegated.currentBytes(), kBlockSize);
}
