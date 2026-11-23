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
#include <memory>
#include <mutex>

namespace gluten {

class AllocationListener {
 public:
  static std::unique_ptr<AllocationListener> noop();

  virtual ~AllocationListener() = default;

  // Value of diff can be either positive or negative
  virtual void allocationChanged(int64_t diff) = 0;

  virtual int64_t currentBytes() {
    return 0;
  }

  virtual int64_t peakBytes() {
    return 0;
  }

 protected:
  AllocationListener() = default;
};

/// Memory changes will be round to specified block size which aim to decrease delegated listener calls.
///
/// When usage shrinks, one spare block is kept reserved instead of being returned immediately. Without
/// this hysteresis, a usage pattern oscillating around a block boundary (e.g. a shuffle reader allocating
/// and freeing a small decompression buffer for every input stream) turns every allocation and every free
/// into a delegated call, which for the JNI listener means a round trip into Spark's globally synchronized
/// memory manager. The spare block is returned on destruction.
// The class must be thread safe
class BlockAllocationListener final : public AllocationListener {
 public:
  BlockAllocationListener(AllocationListener* delegated, int64_t blockSize)
      : delegated_(delegated), blockSize_(blockSize) {}

  ~BlockAllocationListener() override {
    int64_t toRelease;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      toRelease = blocksReserved_ - requiredBlocks();
      blocksReserved_ -= toRelease;
    }
    if (toRelease > 0) {
      try {
        delegated_->allocationChanged(-toRelease * static_cast<int64_t>(blockSize_));
      } catch (const std::exception&) {
        // Never throw from a destructor.
      }
    }
  }

  void allocationChanged(int64_t diff) override {
    if (diff == 0) {
      return;
    }
    int64_t grantedBlocks = reserve(diff);
    if (grantedBlocks == 0) {
      return;
    }
    try {
      delegated_->allocationChanged(grantedBlocks * static_cast<int64_t>(blockSize_));
    } catch (const std::exception&) {
      std::lock_guard<std::mutex> lock(mutex_);
      usedBytes_ -= diff;
      blocksReserved_ -= grantedBlocks;
      throw;
    }
  }

  int64_t currentBytes() override {
    return reservationBytes_;
  }

  int64_t peakBytes() override {
    return peakBytes_;
  }

 private:
  static constexpr int64_t kSpareBlocks = 1;

  // Must be called with mutex_ held.
  inline int64_t requiredBlocks() const {
    // ceil to get the required block number
    return usedBytes_ <= 0 ? 0 : (usedBytes_ - 1) / static_cast<int64_t>(blockSize_) + 1;
  }

  // Returns the number of blocks to grant (positive) or release (negative).
  inline int64_t reserve(int64_t diff) {
    std::lock_guard<std::mutex> lock(mutex_);
    usedBytes_ += diff;
    const int64_t required = requiredBlocks();
    int64_t newBlockCount;
    if (required >= blocksReserved_) {
      newBlockCount = required;
    } else {
      // Shrinking: keep up to kSpareBlocks extra blocks to absorb oscillation around a block boundary.
      newBlockCount = std::min(blocksReserved_, required + kSpareBlocks);
    }
    const int64_t blocksGranted = newBlockCount - blocksReserved_;
    blocksReserved_ = newBlockCount;
    peakBytes_ = std::max(peakBytes_, usedBytes_);
    return blocksGranted;
  }

  AllocationListener* const delegated_;
  const uint64_t blockSize_;
  int64_t blocksReserved_{0L};
  int64_t usedBytes_{0L};
  int64_t peakBytes_{0L};
  int64_t reservationBytes_{0L};

  mutable std::mutex mutex_;
};

} // namespace gluten
