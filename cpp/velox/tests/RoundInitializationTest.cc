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

#include "operators/functions/overlay/Round.h"

#include <gtest/gtest.h>
#include <cstring>
#include <random>

namespace gluten {
namespace {

TEST(RoundInitializationTest, ConstantAndDynamicPrecision) {
  facebook::velox::core::QueryConfig config({});
  RoundFunction<void> dynamic;
  std::mt19937_64 random(23);
  std::vector<double> values = {
      0,
      -0.0,
      1.005,
      -1.005,
      2.675,
      -2.675,
      std::numeric_limits<double>::infinity(),
      -std::numeric_limits<double>::infinity(),
      std::numeric_limits<double>::quiet_NaN(),
      std::numeric_limits<double>::max()};
  for (int i = 0; i < 1000; ++i) {
    uint64_t bits = random();
    double value;
    std::memcpy(&value, &bits, sizeof(value));
    values.push_back(value);
  }
  for (int32_t precision :
       {-4000,
        -400,
        -10,
        -2,
        0,
        2,
        10,
        400,
        4000,
        std::numeric_limits<int32_t>::min(),
        std::numeric_limits<int32_t>::max()}) {
    RoundFunction<void> constant;
    constant.initialize({}, config, static_cast<const double*>(nullptr), &precision);
    for (double value : values) {
      double expected, actual;
      dynamic.call(expected, value, precision);
      constant.call(actual, value, precision);
      if (std::isnan(expected)) {
        ASSERT_TRUE(std::isnan(actual));
      } else {
        ASSERT_EQ(std::memcmp(&expected, &actual, sizeof(actual)), 0) << value << ":" << precision;
      }
    }
    // Reinitializing with a non-constant scale must stop using the old cache.
    constant.initialize({}, config, static_cast<const double*>(nullptr), static_cast<const int32_t*>(nullptr));
    for (int32_t scale : {-3, 0, 2, 5}) {
      double expected, actual;
      dynamic.call(expected, 12.345, scale);
      constant.call(actual, 12.345, scale);
      EXPECT_EQ(expected, actual);
    }
  }
}

TEST(RoundInitializationTest, UnaryAndIntegralInputs) {
  facebook::velox::core::QueryConfig config({});
  RoundFunction<void> unary, dynamic;
  unary.initialize({}, config, static_cast<const double*>(nullptr));
  for (double value : {-2.5, -1.5, -0.0, 0.0, 1.5, 2.5}) {
    double expected, actual;
    dynamic.call(expected, value);
    unary.call(actual, value);
    EXPECT_EQ(std::memcmp(&expected, &actual, sizeof(actual)), 0);
  }
  for (int32_t scale : {-5, -2, 0, 2}) {
    RoundFunction<void> integral;
    integral.initialize({}, config, static_cast<const int64_t*>(nullptr), &scale);
    for (int64_t value :
         {int64_t{-1234567},
          int64_t{0},
          int64_t{1234567},
          std::numeric_limits<int64_t>::min(),
          std::numeric_limits<int64_t>::max()}) {
      if (scale < 0 && (value == std::numeric_limits<int64_t>::min() || value == std::numeric_limits<int64_t>::max())) {
        continue; // Negative scales at the integral limits are rejected by the Spark validator.
      }
      int64_t expected, actual;
      dynamic.call(expected, value, scale);
      integral.call(actual, value, scale);
      EXPECT_EQ(expected, actual);
    }
  }
}

} // namespace
} // namespace gluten
