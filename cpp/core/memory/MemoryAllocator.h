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

#include <array>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <utility>

#include "memory/AllocationListener.h"

namespace gluten {

class MemoryAllocator {
 public:
  virtual ~MemoryAllocator() = default;

  virtual bool allocate(int64_t size, void** out) = 0;
  virtual bool allocateZeroFilled(int64_t nmemb, int64_t size, void** out) = 0;
  virtual bool allocateAligned(uint64_t alignment, int64_t size, void** out) = 0;

  virtual bool reallocate(void* p, int64_t size, int64_t newSize, void** out) = 0;
  virtual bool reallocateAligned(void* p, uint64_t alignment, int64_t size, int64_t newSize, void** out) = 0;

  virtual bool free(void* p, int64_t size) = 0;

  virtual int64_t getBytes() const = 0;

  virtual int64_t peakBytes() const = 0;
};

// The class must be thread safe
class ListenableMemoryAllocator final : public MemoryAllocator {
 public:
  explicit ListenableMemoryAllocator(MemoryAllocator* delegated, AllocationListener* listener)
      : delegated_(delegated), listener_(listener) {}

 public:
  bool allocate(int64_t size, void** out) override;

  bool allocateZeroFilled(int64_t nmemb, int64_t size, void** out) override;

  bool allocateAligned(uint64_t alignment, int64_t size, void** out) override;

  bool reallocate(void* p, int64_t size, int64_t newSize, void** out) override;

  bool reallocateAligned(void* p, uint64_t alignment, int64_t size, int64_t newSize, void** out) override;

  bool free(void* p, int64_t size) override;

  int64_t getBytes() const override;

  int64_t peakBytes() const override;

 private:
  void updateUsage(int64_t size);
  MemoryAllocator* const delegated_;
  AllocationListener* const listener_;
  std::atomic_int64_t usedBytes_{0L};
  std::atomic_int64_t peakBytes_{0L};
};

class StdMemoryAllocator final : public MemoryAllocator {
 public:
  bool allocate(int64_t size, void** out) override;

  bool allocateZeroFilled(int64_t nmemb, int64_t size, void** out) override;

  bool allocateAligned(uint64_t alignment, int64_t size, void** out) override;

  bool reallocate(void* p, int64_t size, int64_t newSize, void** out) override;

  bool reallocateAligned(void* p, uint64_t alignment, int64_t size, int64_t newSize, void** out) override;

  bool free(void* p, int64_t size) override;

  int64_t getBytes() const override;

  int64_t peakBytes() const override;

 private:
  // The default allocator is a process-wide singleton shared by every task thread. A single atomic byte counter
  // turns into a contended cache line when many threads allocate and free small (shuffle) buffers, so the count is
  // spread over cache-line-sized stripes and only summed when read.
  class StripedCounter {
   public:
    void add(int64_t delta) {
      stripes_[stripeIndex()].value.fetch_add(delta, std::memory_order_relaxed);
    }

    int64_t sum() const {
      int64_t total = 0;
      for (const auto& s : stripes_) {
        total += s.value.load(std::memory_order_relaxed);
      }
      return total;
    }

   private:
    static constexpr size_t kNumStripes = 64;
    struct alignas(64) Stripe {
      std::atomic_int64_t value{0};
    };

    static size_t stripeIndex() {
      static std::atomic<size_t> nextIndex{0};
      thread_local const size_t index = nextIndex.fetch_add(1, std::memory_order_relaxed) % kNumStripes;
      return index;
    }

    std::array<Stripe, kNumStripes> stripes_;
  };

  StripedCounter bytes_;
};

std::shared_ptr<MemoryAllocator> defaultMemoryAllocator();

} // namespace gluten
