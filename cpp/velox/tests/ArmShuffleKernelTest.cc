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

#include "shuffle/BitmapGather.h"
#include "shuffle/PartitionId.h"

#include <gtest/gtest.h>
#include <sys/mman.h>
#include <unistd.h>
#include <array>
#include <limits>
#include <numeric>
#include <random>
#include <vector>

namespace gluten::detail {
namespace {

uint32_t referencePid(int32_t value, int32_t divisor) {
  const auto remainder = value % divisor;
  return remainder < 0 ? remainder + divisor : remainder;
}

TEST(ArmShuffleKernelTest, SignedPositiveModulus) {
  std::mt19937 random(23);
  std::vector<int32_t> hashes(8193);
  for (auto& hash : hashes) {
    hash = static_cast<int32_t>(random());
  }
  hashes[0] = std::numeric_limits<int32_t>::min();
  hashes[1] = std::numeric_limits<int32_t>::max();
  hashes[2] = -1;
  hashes[3] = 0;
  hashes[4] = 1;
  for (int32_t divisor :
       {1,
        2,
        3,
        7,
        8,
        31,
        127,
        128,
        1000,
        4000,
        65537,
        1 << 30,
        std::numeric_limits<int32_t>::max() - 1,
        std::numeric_limits<int32_t>::max()}) {
    PartitionIdModulus modulus(divisor);
    std::vector<uint32_t> output(hashes.size() + 1, 0xdeadbeef);
    for (int size : {0, 1, 2, 3, 4, 5, 7, 8, 15, 16, 31, 127, 8193}) {
      modulus.compute(hashes.data(), size, output.data());
      for (int i = 0; i < size; ++i) {
        ASSERT_EQ(output[i], referencePid(hashes[i], divisor)) << divisor << ":" << hashes[i];
        ASSERT_EQ(modulus(hashes[i]), output[i]);
      }
      ASSERT_EQ(output.back(), 0xdeadbeef);
    }
  }
  for (int i = 0; i < 100000; ++i) {
    const int32_t divisor = 1 + random() % 0x7fffffff;
    const int32_t hash = random();
    ASSERT_EQ(PartitionIdModulus(divisor)(hash), referencePid(hash, divisor));
  }
}

TEST(ArmShuffleKernelTest, BitmapSelectionsAndAppendOffsets) {
  std::mt19937 random(41);
  std::array<uint8_t, 513> source;
  for (auto& byte : source) {
    byte = random();
  }
  for (int count : {0, 1, 2, 3, 4, 7, 8, 9, 15, 16, 17, 31, 64, 129, 8192}) {
    std::vector<uint32_t> rows(count);
    for (auto& row : rows) {
      row = random() % (source.size() * 8);
    }
    for (int offset = 0; offset < 16; ++offset) {
      std::vector<uint8_t> expected((count + offset + 7) / 8 + 2, 0xff);
      for (int bit = 0; bit < offset; ++bit) {
        if (random() & 1) {
          expected[bit / 8] &= ~(1 << (bit % 8));
        }
      }
      auto initial = expected;
      for (int i = 0; i < count; ++i) {
        const auto bit = (source[rows[i] / 8] >> (rows[i] % 8)) & 1;
        if (!bit) {
          expected[(offset + i) / 8] &= ~(1 << ((offset + i) % 8));
        }
      }
      for (bool native : {false, true}) {
        auto actual = initial;
        gatherBitmap(source.data(), rows.data(), count, actual.data(), offset, native);
        ASSERT_EQ(actual, expected) << count << ":" << offset << ":" << native;
        actual = initial;
        gatherBitmap(source.data(), rows.data(), count / 2, actual.data(), offset, native);
        gatherBitmap(
            source.data(), rows.data() + count / 2, count - count / 2, actual.data(), offset + count / 2, native);
        ASSERT_EQ(actual, expected);
      }
    }
  }
}

TEST(ArmShuffleKernelTest, GuardedByteAndIndexLoads) {
  const auto page = sysconf(_SC_PAGESIZE);
  auto* source = static_cast<uint8_t*>(mmap(nullptr, page * 2, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
  auto* input = static_cast<uint8_t*>(mmap(nullptr, page * 2, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
  auto* output = static_cast<uint8_t*>(mmap(nullptr, page * 2, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
  ASSERT_NE(source, MAP_FAILED);
  ASSERT_NE(input, MAP_FAILED);
  ASSERT_NE(output, MAP_FAILED);
  ASSERT_EQ(mprotect(source, page, PROT_READ | PROT_WRITE), 0);
  ASSERT_EQ(mprotect(input, page, PROT_READ | PROT_WRITE), 0);
  ASSERT_EQ(mprotect(output, page, PROT_READ | PROT_WRITE), 0);
  source[page - 1] = 0xa5;
  auto* rows = reinterpret_cast<uint32_t*>(input + page) - 8;
  std::iota(rows, rows + 8, 0);
  gatherBitmap(source + page - 1, rows, 8, output + page - 1, 0);
  ASSERT_EQ(output[page - 1], 0xa5);
  for (int size = 0; size < 20; ++size) {
    auto* hashes = reinterpret_cast<int32_t*>(input + page) - size;
    auto* pids = reinterpret_cast<uint32_t*>(output + page) - size;
    for (int i = 0; i < size; ++i) {
      hashes[i] = i % 2 ? -i - 1 : i;
    }
    PartitionIdModulus(4000).compute(hashes, size, pids);
    for (int i = 0; i < size; ++i) {
      ASSERT_EQ(pids[i], referencePid(hashes[i], 4000));
    }
  }
  EXPECT_EQ(munmap(source, page * 2), 0);
  EXPECT_EQ(munmap(input, page * 2), 0);
  EXPECT_EQ(munmap(output, page * 2), 0);
}

} // namespace
} // namespace gluten::detail
