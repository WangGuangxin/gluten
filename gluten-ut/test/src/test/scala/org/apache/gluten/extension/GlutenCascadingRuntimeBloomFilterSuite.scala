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
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.{FullOuter, LeftAnti, LeftOuter, RightOuter}
import org.apache.spark.sql.catalyst.plans.logical.{Aggregate, Filter, Join, LogicalPlan}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.test.SharedSparkSession

class GlutenCascadingRuntimeBloomFilterSuite extends GlutenQueryTest with SharedSparkSession {
  private val key = "spark.gluten.sql.columnar.backend.velox.runtimeBloom.cascade.enabled"
  private val prefix = key.stripSuffix("enabled")
  private val className = "org.apache.gluten.extension.columnar.CascadingRuntimeBloomFilter"
  private val query =
    """
      |WITH s AS (SELECT id AS sid, id % 257 - 128 AS skey, (id % 257) % 17 AS nkey
      | FROM range(0,514,1,4)),
      |a AS (SELECT id AS aid, CASE WHEN id % 11 = 0 THEN CAST(NULL AS BIGINT)
      | ELSE id % 257 - 128 END AS skey FROM range(0,8192,1,4)),
      |n AS (SELECT id AS nkey, concat('nation-',CAST(id AS STRING)) AS name
      | FROM range(0,17,1,2))
      |SELECT a.aid,s.sid FROM s JOIN a ON s.skey=a.skey
      |JOIN n ON s.nkey=n.nkey WHERE n.name='nation-3'
      |""".stripMargin

  private def rule: Rule[LogicalPlan] = {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("Cascading runtime Bloom filters are specific to Velox")
    }
    Class.forName(className).getConstructor(classOf[SparkSession])
      .newInstance(spark).asInstanceOf[Rule[LogicalPlan]]
  }

  private def prepared: LogicalPlan = withSQLConf(
    "spark.gluten.enabled" -> "true",
    "spark.sql.autoBroadcastJoinThreshold" -> "-1",
    "spark.sql.optimizer.runtime.bloomFilter.applicationSideScanSizeThreshold" -> "0",
    key -> "false"
  ) {
    sql(query).queryExecution.optimizedPlan
  }

  private def filters(plan: LogicalPlan): Int = plan.collect {
    case Filter(condition, _) => condition.collect {
        case BloomFilterMightContain(subquery: ScalarSubquery, _) if subquery.plan.exists {
              case aggregate: Aggregate =>
                aggregate.aggregateExpressions.exists(_.name == "gluten_cascading_bloom_filter")
              case _ => false
            } => 1
      }.sum
  }.sum

  test("default disabled preserves the existing runtime-filter plan") {
    val input = prepared
    withSQLConf(key -> "false")(assert(rule.apply(input) eq input))
  }

  test("default two-filter budget remains idempotent after predicate pushdown") {
    val input = prepared
    withSQLConf(
      "spark.gluten.enabled" -> "true",
      "spark.sql.optimizer.runtime.bloomFilter.applicationSideScanSizeThreshold" -> "0",
      key -> "true") {
      val changed = rule.apply(input)
      assert(filters(changed) == 1)
      assert(changed.output == input.output)
      assert(rule.apply(changed).fastEquals(changed))
    }
  }

  test("creation-byte and filter-count limits prevent extra subqueries") {
    val input = prepared
    Seq(prefix + "maxFilters" -> "0", prefix + "maxCreationKeyBytes" -> "1").foreach {
      case (name, value) =>
        withSQLConf(
          "spark.gluten.enabled" -> "true",
          "spark.sql.optimizer.runtime.bloomFilter.applicationSideScanSizeThreshold" -> "0",
          key -> "true",
          name -> value) {
          assert(rule.apply(input) eq input)
        }
    }
  }

  test("anti and outer joins do not prune a preserved side") {
    val input = prepared.collectFirst {
      case join: Join if join.condition.exists(_.references.exists(_.name == "skey")) => join
    }.get
    Seq(LeftAnti, LeftOuter, RightOuter, FullOuter).foreach {
      joinType =>
        val other = input.copy(joinType = joinType)
        withSQLConf(
          "spark.gluten.enabled" -> "true",
          "spark.sql.optimizer.runtime.bloomFilter.applicationSideScanSizeThreshold" -> "0",
          key -> "true") {
          assert(rule.apply(other) eq other)
        }
    }
  }

  test("negative nullable keys and duplicate dimension rows match Spark with AQE on and off") {
    val expected = withSQLConf("spark.gluten.enabled" -> "false", key -> "false") {
      sql(query).collect().toSeq
    }
    Seq("false", "true").foreach {
      aqe =>
        withSQLConf(
          "spark.gluten.enabled" -> "true",
          "spark.sql.adaptive.enabled" -> aqe,
          "spark.sql.autoBroadcastJoinThreshold" -> "-1",
          "spark.sql.optimizer.runtime.bloomFilter.applicationSideScanSizeThreshold" -> "0",
          key -> "true"
        )(checkAnswer(sql(query), expected))
    }
  }
}
