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
#include "operators/hashjoin/HashTableBuilder.h"
#include "velox/vector/tests/utils/VectorTestBase.h"

#include <gtest/gtest.h>

namespace gluten {
using namespace facebook::velox;
class HashTableBuilderBatchStoreTest : public testing::Test, public facebook::velox::test::VectorTestBase {
 protected:
  static void SetUpTestSuite() {
    memory::MemoryManager::testingSetInstance(memory::MemoryManager::Options{});
  }

  RowVectorPtr store(const RowVectorPtr& input, bool enabled, uint64_t& batchRows, uint64_t& scalarRows) {
    const auto type = asRowType(input->type());
    HashTableBuilder builder(
        core::JoinType::kInner,
        false,
        false,
        0,
        {std::make_shared<core::FieldAccessTypedExpr>(BIGINT(), "k")},
        {},
        false,
        type,
        pool(),
        1000,
        1000000,
        100000,
        0,
        enabled);
    builder.addInput(input);
    builder.addInput(input);
    batchRows = builder.batchStoredRows();
    scalarRows = builder.scalarStoredRows();
    auto table = builder.uniqueTable();
    auto* rows = table->rows();
    const auto count = rows->numRows();
    std::vector<char*> pointers(count);
    exec::RowContainerIterator iterator;
    EXPECT_EQ(rows->listRows(&iterator, count, pointers.data()), count);
    auto result = std::dynamic_pointer_cast<RowVector>(BaseVector::create(type, count, pool()));
    for (auto column = 0; column < result->childrenSize(); ++column) {
      rows->extractColumn(pointers.data(), count, column, result->childAt(column));
    }
    return result;
  }
};

TEST_F(HashTableBuilderBatchStoreTest, denseAndSparseNullablePayloads) {
  constexpr int count = 129;
  auto key = makeFlatVector<int64_t>(count, [](auto row) { return row % 17; });
  auto text = makeFlatVector<std::string>(
      count,
      [](auto row) { return std::string(row * 37 + 1, 's'); },
      facebook::velox::test::VectorMaker::nullEvery(11));
  auto amount = makeFlatVector<int128_t>(count, [](auto row) { return int128_t(row) << 70; });
  auto array = makeArrayVector<int64_t>(std::vector<std::vector<int64_t>>(count, {1, 2, 3}));
  auto input = makeRowVector({"k", "s", "d", "a"}, {key, text, amount, array});
  for (bool sparse : {false, true}) {
    if (sparse) {
      key->setNull(1, true);
      key->setNull(7, true);
    }
    uint64_t batchRows = 0;
    uint64_t scalarRows = 0;
    uint64_t ignored = 0;
    auto expected = store(input, false, ignored, ignored);
    auto actual = store(input, true, batchRows, scalarRows);
    ASSERT_EQ(actual->size(), expected->size());
    for (auto row = 0; row < actual->size(); ++row) {
      EXPECT_TRUE(actual->equalValueAt(expected.get(), row, row));
    }
    EXPECT_EQ(batchRows, sparse ? 0 : count * 2);
    EXPECT_EQ(scalarRows, sparse ? (count - 2) * 2 : 0);
  }
}
} // namespace gluten
