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

#include "ArrowMemoryPool.h"

#include <arrow/device.h>
#include <arrow/util/bit_util.h>
#include <atomic>
#include <limits>
#include "utils/Exception.h"

namespace gluten {
namespace {
std::atomic<bool> memoryManagerReuseEnabled{false};

// Matches Arrow PoolBuffer's allocation, rounding, resize and padding contract.
// Only Gluten-owned pools use this class. As with Arrow's custom-pool buffers,
// the pool must outlive all buffers allocated from it.
class GlutenPoolBuffer final : public arrow::ResizableBuffer {
 public:
  GlutenPoolBuffer(std::shared_ptr<arrow::MemoryManager> manager, ArrowMemoryPool* pool, int64_t alignment)
      : ResizableBuffer(nullptr, 0, std::move(manager)), pool_(pool), alignment_(alignment) {}

  ~GlutenPoolBuffer() override {
    if (mutable_data()) {
      pool_->Free(mutable_data(), capacity_, alignment_);
    }
  }

  arrow::Status Reserve(int64_t capacity) override {
    if (capacity < 0) {
      return arrow::Status::Invalid("Negative buffer capacity: ", capacity);
    }
    auto* ptr = mutable_data();
    if (!ptr || capacity > capacity_) {
      ARROW_ASSIGN_OR_RAISE(auto rounded, roundCapacity(capacity));
      if (ptr) {
        ARROW_RETURN_NOT_OK(pool_->Reallocate(capacity_, rounded, alignment_, &ptr));
      } else {
        ARROW_RETURN_NOT_OK(pool_->Allocate(rounded, alignment_, &ptr));
      }
      data_ = ptr;
      capacity_ = rounded;
    }
    return arrow::Status::OK();
  }

  arrow::Status Resize(int64_t size, bool shrinkToFit = true) override {
    if (size < 0) {
      return arrow::Status::Invalid("Negative buffer resize: ", size);
    }
    auto* ptr = mutable_data();
    if (ptr && shrinkToFit && size <= size_) {
      ARROW_ASSIGN_OR_RAISE(auto rounded, roundCapacity(size));
      if (capacity_ != rounded) {
        ARROW_RETURN_NOT_OK(pool_->Reallocate(capacity_, rounded, alignment_, &ptr));
        data_ = ptr;
        capacity_ = rounded;
      }
    } else {
      ARROW_RETURN_NOT_OK(Reserve(size));
    }
    size_ = size;
    return arrow::Status::OK();
  }

 private:
  static arrow::Result<int64_t> roundCapacity(int64_t capacity) {
    if (capacity > std::numeric_limits<int64_t>::max() - 63) {
      return arrow::Status::OutOfMemory("capacity too large");
    }
    return arrow::bit_util::RoundUpToMultipleOf64(capacity);
  }
  ArrowMemoryPool* const pool_;
  const int64_t alignment_;
};
} // namespace

void setArrowMemoryManagerReuseEnabled(bool enabled) {
  memoryManagerReuseEnabled.store(enabled, std::memory_order_relaxed);
}

std::shared_ptr<arrow::MemoryManager> ArrowMemoryPool::cachedMemoryManager() {
  std::call_once(memoryManagerOnce_, [&]() { memoryManager_ = arrow::CPUDevice::memory_manager(this); });
  return memoryManager_;
}

arrow::Result<std::unique_ptr<arrow::ResizableBuffer>>
allocateResizableBuffer(int64_t size, arrow::MemoryPool* pool, int64_t alignment) {
  if (memoryManagerReuseEnabled.load(std::memory_order_relaxed)) {
    if (auto* glutenPool = dynamic_cast<ArrowMemoryPool*>(pool)) {
      auto buffer = std::make_unique<GlutenPoolBuffer>(glutenPool->cachedMemoryManager(), glutenPool, alignment);
      ARROW_RETURN_NOT_OK(buffer->Resize(size));
      buffer->ZeroPadding();
      return std::unique_ptr<arrow::ResizableBuffer>(std::move(buffer));
    }
  }
  return arrow::AllocateResizableBuffer(size, alignment, pool);
}

arrow::Status ArrowMemoryPool::Allocate(int64_t size, int64_t alignment, uint8_t** out) {
  if (!allocator_->allocateAligned(alignment, size, reinterpret_cast<void**>(out))) {
    return arrow::Status::Invalid("WrappedMemoryPool: Error allocating " + std::to_string(size) + " bytes");
  }
  return arrow::Status::OK();
}

arrow::Status ArrowMemoryPool::Reallocate(int64_t oldSize, int64_t newSize, int64_t alignment, uint8_t** ptr) {
  if (!allocator_->reallocateAligned(*ptr, alignment, oldSize, newSize, reinterpret_cast<void**>(ptr))) {
    return arrow::Status::Invalid("WrappedMemoryPool: Error reallocating " + std::to_string(newSize) + " bytes");
  }
  return arrow::Status::OK();
}

void ArrowMemoryPool::Free(uint8_t* buffer, int64_t size, int64_t alignment) {
  allocator_->free(buffer, size);
}

int64_t ArrowMemoryPool::bytes_allocated() const {
  return allocator_->getBytes();
}

int64_t ArrowMemoryPool::max_memory() const {
  return allocator_->peakBytes();
}

int64_t ArrowMemoryPool::total_bytes_allocated() const {
  throw GlutenException("Not implement");
}

int64_t ArrowMemoryPool::num_allocations() const {
  throw GlutenException("Not implement");
}

std::string ArrowMemoryPool::backend_name() const {
  return "gluten arrow allocator";
}

MemoryAllocator* ArrowMemoryPool::allocator() const {
  return allocator_.get();
}

} // namespace gluten
