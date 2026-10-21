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
package org.apache.gluten.extension

import org.apache.gluten.backendsapi.BackendsApiManager

import org.apache.spark.sql.{GlutenQueryTest, SparkSession}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.exchange.ShuffleExchangeExec
import org.apache.spark.sql.test.SharedSparkSession

class GlutenFilteredFinalAggregateReuseSuite extends GlutenQueryTest with SharedSparkSession {
  private val key = "spark.gluten.sql.columnar.backend.velox.reuseFilteredFinalAggregate.enabled"
  private val query = """
                        |WITH r AS (
                        | SELECT id % 17 AS k,
                        |   CASE WHEN id % 17 = 4 OR id % 7 = 0 THEN CAST(NULL AS DECIMAL(18,2))
                        |        ELSE CAST(id % 97 - 40 AS DECIMAL(18,2)) END AS v
                        | FROM range(0,4096,1,4)
                        |), g AS (SELECT k FROM r GROUP BY k HAVING sum(v) > 50)
                        |SELECT k FROM g UNION ALL SELECT k FROM g
                        |""".stripMargin

  // Keep this shared UT compilable with either backend selected. The rule is
  // intentionally provided only by the Velox backend.
  private def rule: Rule[SparkPlan] = {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("Filtered final aggregation reuse is specific to Velox")
    }
    Class.forName("org.apache.gluten.extension.columnar.ReuseFilteredFinalAggregate")
      .getConstructor(classOf[SparkSession])
      .newInstance(spark)
      .asInstanceOf[Rule[SparkPlan]]
  }

  private def vanilla(text: String): SparkPlan = {
    withSQLConf(
      "spark.gluten.enabled" -> "false",
      "spark.sql.adaptive.enabled" -> "false",
      "spark.sql.codegen.wholeStage" -> "false",
      "spark.sql.shuffle.partitions" -> "8",
      key -> "false"
    ) {
      sql(text).queryExecution.executedPlan
    }
  }

  private def exchanges(plan: SparkPlan): Int =
    plan.collect { case _: ShuffleExchangeExec => 1 }.sum

  test("default disabled leaves repeated final aggregation untouched") {
    val input = vanilla(query)
    withSQLConf(key -> "false") {
      assert(rule.apply(input) eq input)
    }
  }

  test("identity aliases are shared while exact output attributes and idempotence hold") {
    val input = vanilla(query)
    withSQLConf(key -> "true") {
      val changed = rule.apply(input)
      assert(exchanges(changed) == exchanges(input) + 2)
      assert(changed.output == input.output)
      assert(rule.apply(changed).fastEquals(changed))
    }
  }

  test("single nondeterministic and floating-point aggregation do not materialize") {
    val cases = Seq(
      query.split("UNION ALL").head,
      query.replace("sum(v) > 50", "sum(v) > 50 AND rand(37) > 0.3"),
      query.replace("DECIMAL(18,2)", "DOUBLE"))
    cases.foreach {
      text =>
        val input = vanilla(text)
        withSQLConf(key -> "true") {
          assert(rule.apply(input) eq input)
        }
    }
  }

  test("AQE and non-AQE preserve nullable decimal and duplicate branch results") {
    val expected = withSQLConf("spark.gluten.enabled" -> "false", key -> "false") {
      sql(query).collect().toSeq
    }
    Seq("false", "true").foreach {
      aqe =>
        withSQLConf(
          "spark.gluten.enabled" -> "true",
          "spark.sql.adaptive.enabled" -> aqe,
          "spark.sql.shuffle.partitions" -> "8",
          key -> "true") {
          checkAnswer(sql(query), expected)
        }
    }
  }
}
