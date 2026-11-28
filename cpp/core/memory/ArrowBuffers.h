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
#include <memory>

#include <arrow/buffer.h>
#include <arrow/device.h>
#include <arrow/memory_pool.h>
#include <arrow/result.h>
#include <arrow/status.h>
#include <arrow/util/bit_util.h>

namespace gluten {

namespace detail {

/// arrow::AllocateBuffer / arrow::AllocateResizableBuffer call arrow::CPUDevice::memory_manager(pool) for every
/// buffer, which copies the process-wide CPUDevice singleton shared_ptr and heap-allocates a new CPUMemoryManager.
/// With many task threads allocating small shuffle buffers, the atomic reference count of the singleton becomes a
/// contended cache line (up to ~1/3 of the CPU of a wide-row shuffle). A CPUMemoryManager only records the device and
/// the raw pool pointer, so one instance per pool can be shared by all buffers of that pool. The cache is
/// thread-local, so reference counting stays on a thread-private control block. Reusing an entry for a new pool that
/// happens to live at the address of a destroyed one is still correct because the manager carries no other state.
inline std::shared_ptr<arrow::MemoryManager> cpuMemoryManager(arrow::MemoryPool* pool) {
  struct Entry {
    arrow::MemoryPool* pool{nullptr};
    std::shared_ptr<arrow::MemoryManager> mm;
  };
  static constexpr size_t kCacheSize = 4;
  thread_local std::array<Entry, kCacheSize> cache;
  thread_local size_t next = 0;
  for (const auto& e : cache) {
    if (e.pool == pool && e.mm != nullptr) {
      return e.mm;
    }
  }
  auto mm = arrow::CPUDevice::memory_manager(pool);
  cache[next] = Entry{pool, mm};
  next = (next + 1) % kCacheSize;
  return mm;
}

/// Same semantics as arrow's internal PoolBuffer (arrow/memory_pool.cc), but takes the memory manager from the cache
/// above.
class PoolBuffer final : public arrow::ResizableBuffer {
 public:
  PoolBuffer(std::shared_ptr<arrow::MemoryManager> mm, arrow::MemoryPool* pool, int64_t alignment)
      : arrow::ResizableBuffer(nullptr, 0, std::move(mm)), pool_(pool), alignment_(alignment) {}

  using arrow::ResizableBuffer::Resize;

  ~PoolBuffer() override {
    uint8_t* ptr = mutable_data();
    if (ptr != nullptr) {
      pool_->Free(ptr, capacity_, alignment_);
    }
  }

  arrow::Status Reserve(const int64_t capacity) override {
    if (capacity < 0) {
      return arrow::Status::Invalid("Negative buffer capacity: ", capacity);
    }
    uint8_t* ptr = mutable_data();
    if (ptr == nullptr || capacity > capacity_) {
      const int64_t newCapacity = arrow::bit_util::RoundUpToMultipleOf64(capacity);
      if (ptr != nullptr) {
        RETURN_NOT_OK(pool_->Reallocate(capacity_, newCapacity, alignment_, &ptr));
      } else {
        RETURN_NOT_OK(pool_->Allocate(newCapacity, alignment_, &ptr));
      }
      data_ = ptr;
      capacity_ = newCapacity;
    }
    return arrow::Status::OK();
  }

  arrow::Status Resize(const int64_t newSize, bool shrinkToFit = true) override {
    if (newSize < 0) {
      return arrow::Status::Invalid("Negative buffer resize: ", newSize);
    }
    uint8_t* ptr = mutable_data();
    if (ptr != nullptr && shrinkToFit && newSize <= size_) {
      // Buffer is non-null and is not growing, so shrink to the requested size without excess space.
      const int64_t newCapacity = arrow::bit_util::RoundUpToMultipleOf64(newSize);
      if (capacity_ != newCapacity) {
        // Buffer hasn't got yet the requested size.
        RETURN_NOT_OK(pool_->Reallocate(capacity_, newCapacity, alignment_, &ptr));
        data_ = ptr;
        capacity_ = newCapacity;
      }
    } else {
      RETURN_NOT_OK(Reserve(newSize));
    }
    size_ = newSize;
    return arrow::Status::OK();
  }

 private:
  arrow::MemoryPool* const pool_;
  const int64_t alignment_;
};

template <typename BufferPtr>
inline arrow::Result<BufferPtr> makePoolBuffer(int64_t size, arrow::MemoryPool* pool, int64_t alignment) {
  if (pool == nullptr) {
    pool = arrow::default_memory_pool();
  }
  auto buffer = std::make_unique<PoolBuffer>(cpuMemoryManager(pool), pool, alignment);
  RETURN_NOT_OK(buffer->Resize(size));
  buffer->ZeroPadding();
  return BufferPtr(std::move(buffer));
}

} // namespace detail

/// Drop-in replacements for arrow::AllocateResizableBuffer / arrow::AllocateBuffer.
inline arrow::Result<std::unique_ptr<arrow::ResizableBuffer>> allocateResizableBuffer(
    int64_t size,
    arrow::MemoryPool* pool = nullptr,
    int64_t alignment = arrow::kDefaultBufferAlignment) {
  return detail::makePoolBuffer<std::unique_ptr<arrow::ResizableBuffer>>(size, pool, alignment);
}

inline arrow::Result<std::unique_ptr<arrow::Buffer>>
allocateBuffer(int64_t size, arrow::MemoryPool* pool = nullptr, int64_t alignment = arrow::kDefaultBufferAlignment) {
  return detail::makePoolBuffer<std::unique_ptr<arrow::Buffer>>(size, pool, alignment);
}

} // namespace gluten
