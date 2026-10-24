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
package org.apache.spark.sql

class GlutenMathFunctionsSuite extends MathFunctionsSuite with GlutenSQLTestsTrait {
  test("ARM round constant precision preserves Spark special values") {
    import testImplicits._
    withTempView("arm_round_values") {
      Seq[java.lang.Double](
        null,
        -0.0,
        0.0,
        1.005,
        -1.005,
        2.675,
        -2.675,
        Double.NaN,
        Double.PositiveInfinity,
        Double.NegativeInfinity)
        .toDF("v").createOrReplaceTempView("arm_round_values")
      for (scale <- Seq(-2, 0, 2)) {
        val query = s"select v, round(v, $scale) from arm_round_values"
        val expected = withSQLConf("spark.gluten.enabled" -> "false")(sql(query).collect())
        withSQLConf("spark.gluten.enabled" -> "true") {
          checkAnswer(sql(query), expected.toSeq)
        }
      }
    }
  }

  test("ARM shuffle keeps Spark partition IDs for signed hashes") {
    import testImplicits._
    import org.apache.spark.sql.functions.{col, spark_partition_id}
    withSQLConf("spark.sql.adaptive.enabled" -> "false") {
      val keys = (Seq(Int.MinValue, Int.MaxValue, -1, 0, 1) ++ (-1000 until 1000)).toDF("v")
      for (partitions <- Seq(3, 7, 64)) {
        def partitioned = keys.repartition(partitions, col("v"))
          .select(col("v"), spark_partition_id().as("pid"))
        val expected = withSQLConf("spark.gluten.enabled" -> "false")(partitioned.collect())
        withSQLConf("spark.gluten.enabled" -> "true") {
          checkAnswer(partitioned, expected.toSeq)
        }
      }
    }
  }
}
