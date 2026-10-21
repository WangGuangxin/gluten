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

#include <gtest/gtest.h>
#include <atomic>
#include <limits>
#include <thread>
#include <vector>
#include "memory/ArrowMemoryPool.h"

namespace gluten {
namespace {
class ReuseGuard {
 public:
  ReuseGuard() {
    setArrowMemoryManagerReuseEnabled(true);
  }
  ~ReuseGuard() {
    setArrowMemoryManagerReuseEnabled(false);
  }
};
} // namespace
TEST(ArrowMemoryManagerReuse, SizesAlignmentResizePaddingAndAccounting) {
  ReuseGuard guard;
  auto listener = AllocationListener::noop();
  ArrowMemoryPool pool(listener.get());
  for (int64_t alignment : {int64_t(64), int64_t(4096), int64_t(65536)}) {
    for (int64_t size : {int64_t(0), int64_t(1), int64_t(63), int64_t(64), int64_t(65), int64_t(8193)}) {
      {
        auto allocated = allocateResizableBuffer(size, &pool, alignment);
        ASSERT_TRUE(allocated.ok()) << allocated.status();
        auto buffer = std::move(allocated).ValueOrDie();
        EXPECT_EQ(buffer->size(), size);
        EXPECT_EQ(buffer->capacity(), (size + 63) / 64 * 64);
        EXPECT_EQ(reinterpret_cast<uintptr_t>(buffer->data()) % alignment, 0);
        EXPECT_EQ(pool.bytes_allocated(), buffer->capacity());
        for (auto i = size; i < buffer->capacity(); ++i) {
          EXPECT_EQ(buffer->data()[i], 0);
        }
        if (size) {
          memset(buffer->mutable_data(), 0x55, size);
        }
        ASSERT_TRUE(buffer->Resize(size + 257, false).ok());
        for (int64_t i = 0; i < size; ++i) {
          ASSERT_EQ(buffer->data()[i], 0x55);
        }
        EXPECT_EQ(pool.bytes_allocated(), buffer->capacity());
        ASSERT_TRUE(buffer->Resize(1, true).ok());
        EXPECT_EQ(buffer->size(), 1);
        EXPECT_EQ(buffer->capacity(), 64);
        ASSERT_TRUE(buffer->Reserve(1000).ok());
        EXPECT_EQ(buffer->size(), 1);
        EXPECT_EQ(buffer->capacity(), 1024);
        const auto* oldData = buffer->data();
        EXPECT_FALSE(buffer->Reserve(std::numeric_limits<int64_t>::max()).ok());
        EXPECT_FALSE(buffer->Reserve(-1).ok());
        EXPECT_FALSE(buffer->Resize(-1).ok());
        EXPECT_EQ(buffer->data(), oldData);
        EXPECT_EQ(buffer->size(), 1);
        EXPECT_EQ(buffer->capacity(), 1024);
        // The current Gluten allocator rejects reallocation to zero. Match
        // Arrow PoolBuffer's failure and preserve data/size/capacity.
        EXPECT_FALSE(buffer->Resize(0, true).ok());
        EXPECT_EQ(buffer->size(), 1);
        EXPECT_EQ(buffer->capacity(), 1024);
        EXPECT_EQ(pool.bytes_allocated(), 1024);
      }
      EXPECT_EQ(pool.bytes_allocated(), 0);
    }
  }
  EXPECT_FALSE(allocateResizableBuffer(-1, &pool).ok());
  EXPECT_EQ(pool.bytes_allocated(), 0);
}
TEST(ArrowMemoryManagerReuse, PoolScopedManagerAndConcurrentInitialization) {
  ReuseGuard guard;
  auto listener = AllocationListener::noop();
  ArrowMemoryPool pool(listener.get());
  ArrowMemoryPool otherPool(listener.get());
  std::vector<std::shared_ptr<arrow::MemoryManager>> managers(16);
  std::atomic<int> failures{0};
  std::vector<std::thread> threads;
  for (int i = 0; i < 16; ++i) {
    threads.emplace_back([&, i]() {
      for (int n = 0; n < 100; ++n) {
        auto result = allocateResizableBuffer(129, &pool);
        if (!result.ok()) {
          ++failures;
          continue;
        }
        auto buffer = std::move(result).ValueOrDie();
        managers[i] = buffer->memory_manager();
        memset(buffer->mutable_data(), i, buffer->size());
      }
    });
  }
  for (auto& thread : threads) {
    thread.join();
  }
  EXPECT_EQ(failures, 0);
  EXPECT_EQ(pool.bytes_allocated(), 0);
  for (const auto& manager : managers) {
    EXPECT_EQ(manager, managers[0]);
  }
  auto other = allocateResizableBuffer(64, &otherPool).ValueOrDie();
  EXPECT_NE(other->memory_manager(), managers[0]);
  auto first = allocateResizableBuffer(64, &pool).ValueOrDie();
  auto second = allocateResizableBuffer(64, &pool).ValueOrDie();
  first.reset();
  EXPECT_EQ(second->memory_manager(), managers[0]);
  second.reset();
  EXPECT_EQ(pool.bytes_allocated(), 0);
}
TEST(ArrowMemoryManagerReuse, DisabledAndForeignPoolsRetainArrowPath) {
  auto listener = AllocationListener::noop();
  ArrowMemoryPool pool(listener.get());
  setArrowMemoryManagerReuseEnabled(false);
  auto first = allocateResizableBuffer(64, &pool).ValueOrDie();
  auto second = allocateResizableBuffer(64, &pool).ValueOrDie();
  EXPECT_NE(first->memory_manager(), second->memory_manager());
  ReuseGuard guard;
  auto global = allocateResizableBuffer(65, arrow::default_memory_pool()).ValueOrDie();
  EXPECT_EQ(global->size(), 65);
  EXPECT_EQ(global->capacity(), 128);
  EXPECT_TRUE(global->Resize(4096).ok());
  EXPECT_FALSE(global->Reserve(-1).ok());
}
} // namespace gluten
