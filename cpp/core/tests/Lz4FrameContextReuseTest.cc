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
#include <algorithm>
#include <atomic>
#include <random>
#include <thread>
#include <vector>
#include "utils/Compression.h"

namespace gluten {
namespace {
class Lz4ReuseGuard {
 public:
  Lz4ReuseGuard() {
    setLz4FrameContextReuseEnabled(true);
  }
  ~Lz4ReuseGuard() {
    setLz4FrameContextReuseEnabled(false);
  }
};
} // namespace
TEST(Lz4FrameContextReuse, IndependentFramesDecodeWithOriginalArrowAndRecoverAfterError) {
  Lz4ReuseGuard guard;
  auto codec = createCompressionCodec(arrow::Compression::LZ4_FRAME, CodecBackend::NONE);
  auto original = arrow::util::Codec::Create(arrow::Compression::LZ4_FRAME).ValueOrDie();
  EXPECT_EQ(codec->compression_type(), original->compression_type());
  EXPECT_EQ(codec->compression_level(), original->compression_level());
  std::mt19937 rng(1781);
  for (int round = 0; round < 4; ++round) {
    for (int size : {0, 1, 63, 64, 65, 1024, 65535, 65536, 65537, 262145, 1048577}) {
      std::vector<uint8_t> input(size);
      for (auto& byte : input) {
        byte = round % 2 ? rng() % 256 : rng() % 4;
      }
      const auto bound = codec->MaxCompressedLen(size, input.data());
      EXPECT_EQ(bound, original->MaxCompressedLen(size, input.data()));
      std::vector<uint8_t> compressed(bound);
      auto result = codec->Compress(size, input.data(), bound, compressed.data());
      ASSERT_TRUE(result.ok()) << result.status();
      const auto compressedSize = result.ValueOrDie();
      // Decode a single frame with a fresh, unmodified Arrow codec. No prior
      // compressed frame, source bytes or shared dictionary is available.
      std::vector<uint8_t> decoded(size);
      auto read = original->Decompress(compressedSize, compressed.data(), size, decoded.data());
      ASSERT_TRUE(read.ok()) << read.status();
      EXPECT_EQ(read.ValueOrDie(), size);
      EXPECT_EQ(decoded, input);
      std::vector<uint8_t> oldFrame(bound);
      auto oldSize = original->Compress(size, input.data(), bound, oldFrame.data()).ValueOrDie();
      EXPECT_EQ(codec->Decompress(oldSize, oldFrame.data(), size, decoded.data()).ValueOrDie(), size);
      EXPECT_EQ(decoded, input);
      uint8_t dummy = 0;
      EXPECT_FALSE(codec->Compress(size, input.data(), 0, &dummy).ok());
      ASSERT_TRUE(codec->Compress(size, input.data(), bound, compressed.data()).ok());
    }
  }
}
TEST(Lz4FrameContextReuse, SharedCodecHasIndependentThreadLocalScratch) {
  Lz4ReuseGuard guard;
  auto codec = createCompressionCodec(arrow::Compression::LZ4_FRAME, CodecBackend::NONE);
  std::atomic<int> failures{0};
  std::vector<std::thread> threads;
  for (int id = 0; id < 8; ++id) {
    threads.emplace_back([&, id]() {
      auto decoder = arrow::util::Codec::Create(arrow::Compression::LZ4_FRAME).ValueOrDie();
      std::mt19937 random(id);
      for (int iteration = 0; iteration < 40; ++iteration) {
        const int size = 1 + random() % 150000;
        std::vector<uint8_t> input(size), decoded(size);
        for (auto& b : input) {
          b = random() % (iteration % 2 ? 256 : 8);
        }
        std::vector<uint8_t> compressed(codec->MaxCompressedLen(size, input.data()));
        auto written = codec->Compress(size, input.data(), compressed.size(), compressed.data());
        if (!written.ok()) {
          ++failures;
          continue;
        }
        auto read = decoder->Decompress(written.ValueOrDie(), compressed.data(), size, decoded.data());
        if (!read.ok() || read.ValueOrDie() != size || decoded != input) {
          ++failures;
        }
      }
    });
  }
  for (auto& thread : threads) {
    thread.join();
  }
  EXPECT_EQ(failures, 0);
}
TEST(Lz4FrameContextReuse, DecompressionErrorsAndResetDoNotLeakAcrossFrames) {
  Lz4ReuseGuard guard;
  auto codec = createCompressionCodec(arrow::Compression::LZ4_FRAME, CodecBackend::NONE);
  auto original = arrow::util::Codec::Create(arrow::Compression::LZ4_FRAME).ValueOrDie();
  std::vector<uint8_t> input(10000, 'z'), output(input.size());
  std::vector<uint8_t> frame(original->MaxCompressedLen(input.size(), input.data()));
  const auto n = original->Compress(input.size(), input.data(), frame.size(), frame.data()).ValueOrDie();
  EXPECT_FALSE(codec->Decompress(n, frame.data(), 1, output.data()).ok());
  EXPECT_EQ(codec->Decompress(n, frame.data(), output.size(), output.data()).ValueOrDie(), input.size());
  EXPECT_EQ(output, input);
  EXPECT_FALSE(codec->Decompress(n - 1, frame.data(), output.size(), output.data()).ok());
  EXPECT_FALSE(codec->Decompress(0, frame.data(), output.size(), output.data()).ok());
  std::vector<uint8_t> concatenated(frame.begin(), frame.begin() + n);
  concatenated.insert(concatenated.end(), frame.begin(), frame.begin() + n);
  EXPECT_FALSE(codec->Decompress(concatenated.size(), concatenated.data(), output.size(), output.data()).ok());
  auto invalid = frame;
  invalid[0] ^= 0xff;
  EXPECT_FALSE(codec->Decompress(n, invalid.data(), output.size(), output.data()).ok());
  EXPECT_EQ(codec->Decompress(n, frame.data(), output.size(), output.data()).ValueOrDie(), input.size());
  EXPECT_EQ(output, input);
}

TEST(Lz4FrameContextReuse, DisabledAndOtherCodecsRemainUnchanged) {
  setLz4FrameContextReuseEnabled(false);
  auto lz4 = createCompressionCodec(arrow::Compression::LZ4_FRAME, CodecBackend::NONE);
  EXPECT_EQ(lz4->compression_type(), arrow::Compression::LZ4_FRAME);
  Lz4ReuseGuard guard;
  auto zstd = createCompressionCodec(arrow::Compression::ZSTD, CodecBackend::NONE);
  EXPECT_EQ(zstd->compression_type(), arrow::Compression::ZSTD);
  EXPECT_EQ(
      zstd->compression_level(),
      arrow::util::Codec::Create(arrow::Compression::ZSTD).ValueOrDie()->compression_level());
}
} // namespace gluten
