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

#include "BoltColumnarBatchSerializer.h"

#include <arrow/buffer.h>

#include <cstring>

#include "memory/ArrowMemory.h"
#include "memory/BoltColumnarBatch.h"
#include "bolt/common/memory/Memory.h"
#include "bolt/vector/FlatVector.h"
#include "bolt/vector/arrow/Bridge.h"
#include "bolt/common/memory/ByteStream.h"

#include <iostream>

using namespace bytedance::bolt;

namespace gluten {
namespace {

std::unique_ptr<ByteInputStream> toByteStream(uint8_t* data, int32_t size) {
  std::vector<ByteRange> byteRanges;
  byteRanges.push_back(ByteRange{data, size, 0});
  auto byteStream = std::make_unique<ByteInputStream>(byteRanges);
  return byteStream;
}

} // namespace

BoltColumnarBatchSerializer::BoltColumnarBatchSerializer(
    arrow::MemoryPool* arrowPool,
    std::shared_ptr<memory::MemoryPool> boltPool,
    struct ArrowSchema* cSchema)
    : ColumnarBatchSerializer(arrowPool), boltPool_(std::move(boltPool)) {
  // serializeColumnarBatches don't need rowType_
  if (cSchema != nullptr) {
    rowType_ = asRowType(importFromArrow(*cSchema));
    ArrowSchemaRelease(cSchema); // otherwise the c schema leaks memory
  }
  arena_ = std::make_unique<StreamArena>(boltPool_.get());
  serde_ = std::make_unique<serializer::presto::PrestoVectorSerde>();
  options_.useLosslessTimestamp = true;
}

void BoltColumnarBatchSerializer::append(const std::shared_ptr<ColumnarBatch>& batch) {
  auto rowVector = BoltColumnarBatch::from(boltPool_.get(), batch)->getRowVector();
  if (serializer_ == nullptr) {
    serializer_ = serde_->createBatchSerializer(boltPool_.get(), &options_);
  }
  batches_.push_back(rowVector);
  serializedBufferStream_.reset();
}

int64_t BoltColumnarBatchSerializer::maxSerializedSize() {
  BOLT_DCHECK(!batches_.empty(), "Should serialize at least 1 vector");
  materializeSerializedBuffer();
  return serializedBufferStream_->tellp();
}

void BoltColumnarBatchSerializer::serializeTo(uint8_t* address, int64_t size) {
  BOLT_DCHECK(serializer_ != nullptr, "Should serialize at least 1 vector");
  materializeSerializedBuffer();
  auto sizeNeeded = serializedBufferStream_->tellp();
  GLUTEN_CHECK(
      size >= sizeNeeded,
      "The target buffer size is insufficient: " + std::to_string(size) + " vs." + std::to_string(sizeNeeded));
  auto buffer = serializedBufferStream_->getBuffer();
  std::memcpy(address, buffer->as<char>(), sizeNeeded);
}

std::shared_ptr<ColumnarBatch> BoltColumnarBatchSerializer::deserialize(uint8_t* data, int32_t size) {
  RowVectorPtr result;
  auto byteStream = toByteStream(data, size);
  serde_->deserialize(byteStream.get(), boltPool_.get(), rowType_, &result, &options_);
  return std::make_shared<BoltColumnarBatch>(result);
}

void BoltColumnarBatchSerializer::materializeSerializedBuffer() {
  BOLT_DCHECK(serializer_ != nullptr, "Should serialize at least 1 vector");
  if (serializedBufferStream_ != nullptr) {
    return;
  }

  serializedBufferStream_ = std::make_unique<BufferOutputStream>(boltPool_.get());
  for (const auto& rowVector : batches_) {
    serializer_->serialize(rowVector, serializedBufferStream_.get());
  }
}

} // namespace gluten
