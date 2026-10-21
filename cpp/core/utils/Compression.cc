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

#include "utils/Compression.h"

#include <lz4.h>
#include <lz4frame.h>
#include <atomic>
#include "Exception.h"

#ifdef GLUTEN_ENABLE_QAT
#include "utils/qat/QatCodec.h"
#endif

namespace gluten {
namespace {
std::atomic<bool> lz4FrameContextReuseEnabled{false};
std::atomic<int32_t> lz4FrameFastCompressionLevel{0};
#if LZ4_VERSION_NUMBER >= 11000
// A complete, independent LZ4 frame is emitted on each call. Only the scratch
// compression context is retained, per thread, with no cross-frame dictionary.
class ReusableLz4FrameCodec final : public arrow::util::Codec {
 public:
  explicit ReusableLz4FrameCodec(std::unique_ptr<arrow::util::Codec> delegate) : delegate_(std::move(delegate)) {
    preferences_.compressionLevel = delegate_->compression_level();
  }
  int64_t MaxCompressedLen(int64_t size, const uint8_t* input) override {
    return delegate_->MaxCompressedLen(size, input);
  }
  arrow::Result<int64_t> Compress(int64_t size, const uint8_t* input, int64_t capacity, uint8_t* output) override {
    if (size < 0 || capacity < 0) {
      return arrow::Status::Invalid("Negative LZ4 input/output length");
    }
    struct ThreadContext {
      LZ4F_cctx* context = nullptr;
      ~ThreadContext() {
        LZ4F_freeCompressionContext(context);
      }
    };
    thread_local ThreadContext state;
    if (!state.context) {
      const auto error = LZ4F_createCompressionContext(&state.context, LZ4F_VERSION);
      if (LZ4F_isError(error)) {
        return arrow::Status::IOError("LZ4 context creation: ", LZ4F_getErrorName(error));
      }
    }
    const auto written =
        LZ4F_compressFrame_usingCDict(state.context, output, capacity, input, size, nullptr, &preferences_);
    if (LZ4F_isError(written)) {
      LZ4F_freeCompressionContext(state.context);
      state.context = nullptr;
      return arrow::Status::IOError("LZ4 frame compression: ", LZ4F_getErrorName(written));
    }
    return static_cast<int64_t>(written);
  }
  arrow::Result<int64_t> Decompress(int64_t size, const uint8_t* input, int64_t capacity, uint8_t* output) override {
    if (size < 0 || capacity < 0) {
      return arrow::Status::Invalid("Negative LZ4 input/output length");
    }
    // Arrow's LZ4 decompressor owns its scratch buffer and supports Reset().
    // Reuse only that state; every call starts a new, independent frame.
    thread_local std::shared_ptr<arrow::util::Decompressor> decoder;
    if (!decoder) {
      ARROW_ASSIGN_OR_RAISE(decoder, delegate_->MakeDecompressor());
    }
    ARROW_RETURN_NOT_OK(decoder->Reset());
    int64_t written = 0;
    while (!decoder->IsFinished() && size != 0) {
      ARROW_ASSIGN_OR_RAISE(auto result, decoder->Decompress(size, input, capacity, output));
      input += result.bytes_read;
      size -= result.bytes_read;
      output += result.bytes_written;
      capacity -= result.bytes_written;
      written += result.bytes_written;
      if (result.need_more_output) {
        return arrow::Status::IOError("Lz4 decompression buffer too small");
      }
    }
    if (!decoder->IsFinished()) {
      return arrow::Status::IOError("Lz4 compressed input contains less than one frame");
    }
    if (size != 0) {
      return arrow::Status::IOError("Lz4 compressed input contains more than one frame");
    }
    return written;
  }
  arrow::Result<std::shared_ptr<arrow::util::Compressor>> MakeCompressor() override {
    return delegate_->MakeCompressor();
  }
  arrow::Result<std::shared_ptr<arrow::util::Decompressor>> MakeDecompressor() override {
    return delegate_->MakeDecompressor();
  }
  arrow::Compression::type compression_type() const override {
    return arrow::Compression::LZ4_FRAME;
  }
  int minimum_compression_level() const override {
    return delegate_->minimum_compression_level();
  }
  int maximum_compression_level() const override {
    return delegate_->maximum_compression_level();
  }
  int default_compression_level() const override {
    return delegate_->default_compression_level();
  }
  int compression_level() const override {
    return delegate_->compression_level();
  }

 private:
  std::unique_ptr<arrow::util::Codec> delegate_;
  LZ4F_preferences_t preferences_{};
};
#endif
} // namespace

void setLz4FrameContextReuseEnabled(bool enabled) {
#if LZ4_VERSION_NUMBER < 11000
  GLUTEN_CHECK(!enabled, "LZ4 frame context reuse requires LZ4 1.10 or newer");
#endif
  lz4FrameContextReuseEnabled.store(enabled, std::memory_order_relaxed);
}

void setLz4FrameFastCompressionLevel(int32_t level) {
  GLUTEN_CHECK(level >= 0 && level <= 32, "LZ4 fast compression level must be in[0,32]");
  lz4FrameFastCompressionLevel.store(level, std::memory_order_relaxed);
}

std::unique_ptr<arrow::util::Codec>
createCompressionCodec(arrow::Compression::type compressedType, CodecBackend codecBackend, int32_t compressionLevel) {
  std::unique_ptr<arrow::util::Codec> codec;
  switch (compressedType) {
    case arrow::Compression::LZ4_FRAME: {
      const auto fastLevel = lz4FrameFastCompressionLevel.load(std::memory_order_relaxed);
      // Construct the delegate at the actual level so one-shot and streaming
      // compressors, bounds and level metadata remain consistent. Frames stay
      // standard independent LZ4F and decode with an unmodified Arrow codec.
      GLUTEN_ASSIGN_OR_THROW(
          codec,
          arrow::util::Codec::Create(
              compressedType, fastLevel == 0 ? arrow::util::kUseDefaultCompressionLevel : -fastLevel));
#if LZ4_VERSION_NUMBER >= 11000
      if (lz4FrameContextReuseEnabled.load(std::memory_order_relaxed)) {
        codec = std::make_unique<ReusableLz4FrameCodec>(std::move(codec));
      }
#endif
    } break;
    case arrow::Compression::ZSTD: {
      if (codecBackend == CodecBackend::NONE) {
        GLUTEN_ASSIGN_OR_THROW(codec, arrow::util::Codec::Create(compressedType, compressionLevel));
      } else if (codecBackend == CodecBackend::QAT) {
#if defined(GLUTEN_ENABLE_QAT)
        codec = qat::makeDefaultQatZstdCodec();
#else
        throw GlutenException("Backend QAT but not compile with option GLUTEN_ENABLE_QAT");
#endif
      }
    } break;
    case arrow::Compression::GZIP: {
      if (codecBackend == CodecBackend::NONE) {
        return nullptr;
      } else if (codecBackend == CodecBackend::QAT) {
#if defined(GLUTEN_ENABLE_QAT)
        codec = qat::makeDefaultQatGZipCodec();
#else
        throw GlutenException("Backend QAT but not compile with option GLUTEN_ENABLE_QAT");
#endif
      }
    } break;
    default:
      return nullptr;
  }
  return codec;
}
} // namespace gluten
