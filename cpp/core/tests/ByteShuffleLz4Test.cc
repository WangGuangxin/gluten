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

#include <arrow/io/memory.h>
#include <gflags/gflags.h>
#include <gtest/gtest.h>
#include <algorithm>
#include <atomic>
#include <random>
#include <stdexcept>
#include <thread>
#include "memory/ArrowMemoryPool.h"
#include "shuffle/Payload.h"
#include "utils/ByteShuffle.h"
#include "utils/Compression.h"
#include "utils/tac/TypeAwareCompressCodec.h"

namespace gluten {
namespace {
constexpr size_t kPrefix = sizeof(Payload::Type) + 2 * sizeof(uint32_t);
template <class T>
T checked(arrow::Result<T> result) {
  if (!result.ok()) {
    throw std::runtime_error(result.status().ToString());
  }
  return std::move(result).ValueOrDie();
}
void require(arrow::Status status) {
  if (!status.ok()) {
    throw std::runtime_error(status.ToString());
  }
}
template <class T>
void append(std::vector<uint8_t>& out, T value) {
  const auto* p = reinterpret_cast<const uint8_t*>(&value);
  out.insert(out.end(), p, p + sizeof(value));
}
int64_t field(const std::vector<uint8_t>& data, size_t offset) {
  int64_t value;
  memcpy(&value, data.data() + offset, 8);
  return value;
}
void field(std::vector<uint8_t>& data, size_t offset, int64_t value) {
  memcpy(data.data() + offset, &value, 8);
}
void mode(int value) {
  setByteShuffleLz4Enabled(value != 0);
  setArmNeonByteShuffleEnabled(value == 2);
}
std::shared_ptr<arrow::util::Codec> codec(arrow::Compression::type type = arrow::Compression::LZ4_FRAME) {
  return std::shared_ptr<arrow::util::Codec>(createCompressionCodec(type, CodecBackend::NONE));
}
std::vector<uint8_t> values(size_t n, int pattern) {
  std::vector<uint8_t> data(n * 8);
  std::mt19937_64 rng(7183);
  for (size_t i = 0; i < n; ++i) {
    uint64_t v = pattern == 0 ? i * 17 + 123
        : pattern == 1        ? 17
        : pattern == 2        ? rng()
        : pattern == 3        ? uint64_t(int64_t(rng() % 2000001) - 1000000)
                              : 0x7ff8000000000000ULL | (i * 13 + 1);
    memcpy(data.data() + i * 8, &v, 8);
  }
  return data;
}
std::shared_ptr<arrow::Buffer> buffer(const std::vector<uint8_t>& data, arrow::MemoryPool* pool) {
  auto out = checked(allocateResizableBuffer(data.size(), pool));
  if (data.size())
    memcpy(out->mutable_data(), data.data(), data.size());
  return out;
}
std::vector<uint8_t> encode(
    const std::vector<std::shared_ptr<arrow::Buffer>>& buffers,
    const std::shared_ptr<arrow::util::Codec>& c,
    arrow::MemoryPool* pool,
    const std::vector<int8_t>* types = nullptr) {
  std::vector<bool> validity(buffers.size(), false);
  auto packet =
      checked(BlockPayload::fromBuffers(Payload::kCompressed, 8192, buffers, &validity, pool, c.get(), types));
  auto stream = checked(arrow::io::BufferOutputStream::Create(1024, pool));
  require(packet->serialize(stream.get()));
  auto wire = checked(stream->Finish());
  return {wire->data(), wire->data() + wire->size()};
}
arrow::Result<std::vector<std::shared_ptr<arrow::Buffer>>>
decode(const std::vector<uint8_t>& wire, const std::shared_ptr<arrow::util::Codec>& c, arrow::MemoryPool* pool) {
  auto view = std::make_shared<arrow::Buffer>(wire.data(), wire.size());
  arrow::io::BufferReader stream(view);
  uint32_t rows = 0;
  int64_t deserialize = 0, decompress = 0;
  auto result = BlockPayload::deserialize(&stream, c, pool, rows, deserialize, decompress);
  if (result.ok()) {
    EXPECT_EQ(rows, 8192);
  }
  return result;
}
class ByteShuffleLz4Test : public testing::Test {
 protected:
  gflags::FlagSaver flags_;
  void SetUp() override {
    mode(0);
    setByteShuffleLz4LogUsage(false);
    setArrowMemoryManagerReuseEnabled(true);
    setLz4FrameContextReuseEnabled(true);
    setLz4FrameFastCompressionLevel(0);
  }
  void TearDown() override {
    setLz4FrameContextReuseEnabled(false);
    setArrowMemoryManagerReuseEnabled(false);
  }
};
TEST_F(ByteShuffleLz4Test, TransposeTailsAlignmentAndIndependentReference) {
  std::mt19937 rng(13);
  for (unsigned width : {8, 16})
    for (size_t n : {0, 1, 2, 7, 8, 9, 15, 16, 17, 31, 32, 33, 127, 128, 129, 8192})
      for (unsigned align = 0; align < 16; ++align) {
        const auto size = n * width;
        std::vector<uint8_t> in(size + 32), out(size + 32, 0x72), expected(size), decoded(size + 32, 0x73);
        auto* src = in.data() + align;
        for (size_t i = 0; i < size; ++i)
          src[i] = rng();
        for (size_t r = 0; r < n; ++r)
          for (unsigned b = 0; b < width; ++b)
            expected[b * n + r] = src[r * width + b];
        for (bool neon : {false, true}) {
          ASSERT_TRUE(byte_shuffle::reorder<false>(src, out.data() + align, n, width, neon));
          EXPECT_TRUE(std::equal(expected.begin(), expected.end(), out.data() + align));
          ASSERT_TRUE(byte_shuffle::reorder<true>(out.data() + align, decoded.data() + align, n, width, neon));
          EXPECT_TRUE(std::equal(src, src + size, decoded.data() + align));
          EXPECT_EQ(out[align + size], 0x72);
          EXPECT_EQ(decoded[align + size], 0x73);
        }
      }
  uint8_t sentinel = 0x72;
  EXPECT_FALSE(byte_shuffle::reorder<false>(nullptr, &sentinel, SIZE_MAX / 8 + 1, 8, true));
  EXPECT_EQ(sentinel, 0x72);
}
TEST_F(ByteShuffleLz4Test, ActualPayloadThreeCellsNullEmptyAndReaderOptInIndependence) {
  auto listener = AllocationListener::noop();
  ArrowMemoryPool pool(listener.get());
  auto c = codec();
  auto source = values(8192, 0);
  std::vector<uint8_t> scalar;
  for (int config : {0, 1, 2}) {
    {
      mode(config);
      auto wire = encode({nullptr, buffer({}, &pool), buffer(source, &pool)}, c, &pool);
      if (config == 1)
        scalar = wire;
      if (config == 2) {
        EXPECT_EQ(wire, scalar);
      }
      for (bool neon : {false, true}) {
        mode(0);
        setArmNeonByteShuffleEnabled(neon);
        auto decoded = checked(decode(wire, c, &pool));
        ASSERT_EQ(decoded.size(), 3);
        EXPECT_FALSE(decoded[0]);
        ASSERT_TRUE(decoded[1]);
        EXPECT_EQ(decoded[1]->size(), 0);
        ASSERT_TRUE(decoded[2]);
        EXPECT_EQ(decoded[2]->size(), source.size());
        EXPECT_EQ(memcmp(decoded[2]->data(), source.data(), source.size()), 0);
      }
    }
    EXPECT_EQ(pool.bytes_allocated(), 0);
  }
}
TEST_F(ByteShuffleLz4Test, DisabledAndIneligibleMatchOriginalFrameBytes) {
  auto listener = AllocationListener::noop();
  ArrowMemoryPool pool(listener.get());
  auto c = codec();
  for (int pattern : {0, 1, 2, 3, 4}) {
    auto source = values(8192, pattern);
    auto b = buffer(source, &pool);
    mode(0);
    auto wire = encode({b}, c, &pool);
    std::vector<uint8_t> expected;
    append(expected, Payload::kCompressed);
    append<uint32_t>(expected, 8192);
    append<uint32_t>(expected, 1);
    std::vector<uint8_t> compressed(c->MaxCompressedLen(source.size(), source.data()));
    auto n = checked(c->Compress(source.size(), source.data(), compressed.size(), compressed.data()));
    append<int64_t>(expected, n >= int64_t(source.size()) ? -2 : n);
    append<int64_t>(expected, source.size());
    if (n >= int64_t(source.size()))
      expected.insert(expected.end(), source.begin(), source.end());
    else
      expected.insert(expected.end(), compressed.begin(), compressed.begin() + n);
    EXPECT_EQ(wire, expected);
    if (pattern == 1 || pattern == 2 || pattern == 3)
      for (int config : {1, 2}) {
        mode(config);
        EXPECT_EQ(encode({b}, c, &pool), expected);
      }
  }
}
TEST_F(ByteShuffleLz4Test, TaggedMetadataFallbackTypesAndUnalignedFrames) {
  auto listener = AllocationListener::noop();
  ArrowMemoryPool pool(listener.get());
  auto c = codec();
  mode(2);
  for (int pattern : {0, 4})
    for (size_t n : {1024, 1025, 8192}) {
      auto source = values(n, pattern);
      auto wire = encode({buffer(source, &pool)}, c, &pool);
      ASSERT_EQ(field(wire, kPrefix), -4);
      EXPECT_EQ(field(wire, kPrefix + 8), source.size());
      EXPECT_EQ(field(wire, kPrefix + 24), 8);
      auto decoded = checked(decode(wire, c, &pool));
      EXPECT_EQ(memcmp(decoded[0]->data(), source.data(), source.size()), 0);
    }
  for (size_t n : {0, 1, 7, 8191, 8193}) {
    std::vector<uint8_t> source(n, 0x71);
    auto wire = encode({buffer(source, &pool)}, c, &pool);
    EXPECT_NE(field(wire, kPrefix), -4);
    auto decoded = checked(decode(wire, c, &pool));
    EXPECT_EQ(decoded[0]->size(), n);
    if (n) {
      EXPECT_EQ(memcmp(decoded[0]->data(), source.data(), n), 0);
    }
  }
}
TEST_F(ByteShuffleLz4Test, CorruptAndTruncatedTaggedFramesRejectWithoutLeaks) {
  auto listener = AllocationListener::noop();
  ArrowMemoryPool pool(listener.get());
  auto c = codec();
  mode(2);
  auto wire = encode({buffer(values(8192, 0), &pool)}, c, &pool);
  ASSERT_EQ(field(wire, kPrefix), -4);
  auto baseline = pool.bytes_allocated();
  for (auto [offset, value] : std::vector<std::pair<size_t, int64_t>>{
           {0, -7}, {8, -1}, {8, 0}, {8, 7}, {16, 0}, {16, -1}, {16, INT64_MAX}, {24, 0}, {24, 4}, {24, 16}}) {
    auto bad = wire;
    field(bad, kPrefix + offset, value);
    EXPECT_FALSE(decode(bad, c, &pool).ok());
    EXPECT_EQ(pool.bytes_allocated(), baseline);
  }
  for (size_t n = kPrefix; n < kPrefix + 32; ++n) {
    auto bad = wire;
    bad.resize(n);
    EXPECT_FALSE(decode(bad, c, &pool).ok());
    EXPECT_EQ(pool.bytes_allocated(), baseline);
  }
  auto bad = wire;
  bad.pop_back();
  EXPECT_FALSE(decode(bad, c, &pool).ok());
  EXPECT_EQ(pool.bytes_allocated(), baseline);
  bad = wire;
  bad[kPrefix + 32] ^= 0xff;
  EXPECT_FALSE(decode(bad, c, &pool).ok());
  EXPECT_EQ(pool.bytes_allocated(), baseline);
}
TEST_F(ByteShuffleLz4Test, OtherCodecKeepsOldWireAndRejectsTaggedLz4) {
  auto listener = AllocationListener::noop();
  ArrowMemoryPool pool(listener.get());
  auto zstd = codec(arrow::Compression::ZSTD);
  auto source = values(8192, 0);
  auto b = buffer(source, &pool);
  mode(0);
  auto original = encode({b}, zstd, &pool);
  mode(2);
  EXPECT_EQ(encode({b}, zstd, &pool), original);
  auto output = checked(decode(original, zstd, &pool));
  EXPECT_EQ(memcmp(output[0]->data(), source.data(), source.size()), 0);
  auto tagged = encode({b}, codec(), &pool);
  ASSERT_EQ(field(tagged, kPrefix), -4);
  EXPECT_FALSE(decode(tagged, zstd, &pool).ok());
}
TEST_F(ByteShuffleLz4Test, ExistingTypeAwareMarkerRetainsPrecedence) {
  auto listener = AllocationListener::noop();
  ArrowMemoryPool pool(listener.get());
  auto c = codec();
  auto source = values(8192, 0);
  auto b = buffer(source, &pool);
  std::vector<int8_t> types{tac::kUInt64};
  mode(0);
  auto old = encode({b}, c, &pool, &types);
  ASSERT_EQ(field(old, kPrefix), -3);
  mode(2);
  EXPECT_EQ(encode({b}, c, &pool, &types), old);
  auto output = checked(decode(old, c, &pool));
  EXPECT_EQ(memcmp(output[0]->data(), source.data(), source.size()), 0);
}
TEST_F(ByteShuffleLz4Test, ConcurrentPoolsAndIndependentFrames) {
  mode(2);
  auto c = codec();
  std::atomic<int> errors{0};
  std::vector<std::thread> workers;
  for (int id = 0; id < 4; ++id)
    workers.emplace_back([&, id]() {
      try {
        auto listener = AllocationListener::noop();
        ArrowMemoryPool pool(listener.get());
        for (int i = 0; i < 5; ++i) {
          {
            auto source = values(1024 + id * 13 + i, 0);
            auto wire = encode({buffer(source, &pool)}, c, &pool);
            auto out = checked(decode(wire, c, &pool));
            if (out[0]->size() != int64_t(source.size()) || memcmp(out[0]->data(), source.data(), source.size()))
              ++errors;
          }
          if (pool.bytes_allocated() != 0)
            ++errors;
        }
      } catch (...) {
        ++errors;
      }
    });
  for (auto& t : workers)
    t.join();
  EXPECT_EQ(errors.load(), 0);
}
TEST_F(ByteShuffleLz4Test, FlagsRemainOptIn) {
  for (const char* key :
       {"gluten_shuffle_byte_lz4_enabled",
        "gluten_arm_neon_byte_shuffle_enabled",
        "gluten_shuffle_byte_lz4_log_usage"}) {
    gflags::CommandLineFlagInfo info;
    ASSERT_TRUE(gflags::GetCommandLineFlagInfo(key, &info));
    EXPECT_EQ(info.default_value, "false");
  }
}
} // namespace
} // namespace gluten
