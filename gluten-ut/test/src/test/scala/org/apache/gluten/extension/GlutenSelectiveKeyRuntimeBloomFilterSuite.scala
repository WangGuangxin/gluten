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

import org.apache.spark.sql.{GlutenQueryTest, Row, SparkSession}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.{FullOuter, LeftAnti, LeftOuter, RightOuter}
import org.apache.spark.sql.catalyst.plans.logical.{Aggregate, Filter, Join, LogicalPlan}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.{LongType, StringType, StructField, StructType}

import org.apache.commons.io.FileUtils

import java.nio.file.Files

class GlutenSelectiveKeyRuntimeBloomFilterSuite extends GlutenQueryTest with SharedSparkSession {
  private val key = "spark.gluten.sql.columnar.backend.velox.runtimeBloom.selectiveKeys.enabled"
  private val prefix = key.stripSuffix("enabled")
  private val className = "org.apache.gluten.extension.columnar.SelectiveKeyRuntimeBloomFilter"
  private val query =
    """
      |WITH s AS (SELECT * FROM selective_key_dimension),
      |a AS (SELECT id AS aid, CASE WHEN id % 11 = 0 THEN CAST(NULL AS BIGINT)
      | ELSE id % 257 - 128 END AS skey, CAST(id%89-44 AS DECIMAL(18,2)) AS amount
      | FROM range(0,8192,1,4)),
      |g AS (SELECT id%257-128 AS grouped_key,avg(CAST(id%97-48 AS DECIMAL(18,2))) AS mean
      | FROM range(0,8192,1,4) GROUP BY id%257-128)
      |SELECT a.aid,s.sid FROM s JOIN (a JOIN g ON a.skey=g.grouped_key)
      |ON s.skey=a.skey WHERE s.brand='b-1' AND s.container='c-2' AND a.amount<g.mean
      |""".stripMargin

  private lazy val dimensionDirectory = Files.createTempDirectory("selective-key-bloom-ut")
  private var dimensionWritten = false

  override protected def afterAll(): Unit = {
    try super.afterAll()
    finally FileUtils.deleteDirectory(dimensionDirectory.toFile)
  }

  private def ensureDimension(): Unit = {
    val rows = spark.sparkContext.parallelize(
      (0L until 514L).map {
        id => Row(id, id % 257 - 128, s"b-${id % 3}", s"c-${id % 5}")
      },
      4)
    val schema = StructType(Seq(
      StructField("sid", LongType),
      StructField("skey", LongType),
      StructField("brand", StringType),
      StructField("container", StringType)))
    if (!dimensionWritten) {
      spark.createDataFrame(
        rows,
        schema).write.mode("overwrite").parquet(dimensionDirectory.toString)
      dimensionWritten = true
    }
    spark.read.parquet(
      dimensionDirectory.toString).createOrReplaceTempView("selective_key_dimension")
  }

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
    "spark.sql.optimizer.runtime.bloomFilter.creationSideThreshold" -> "1",
    "spark.sql.optimizer.runtime.bloomFilter.applicationSideScanSizeThreshold" -> "0",
    key -> "false"
  ) {
    ensureDimension()
    sql(query).queryExecution.optimizedPlan
  }

  private def filters(plan: LogicalPlan): Int = plan.collect {
    case Filter(condition, _) => condition.collect {
        case BloomFilterMightContain(subquery: ScalarSubquery, _) if subquery.plan.exists {
              case aggregate: Aggregate =>
                aggregate.aggregateExpressions.exists(_.name == "gluten_selective_key_bloom_filter")
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
      assert(filters(changed) >= 1)
      assert(changed.exists {
        case a: Aggregate if a.groupingExpressions.nonEmpty =>
          a.child.exists {
            case Filter(condition, _) => condition.exists(_.isInstanceOf[BloomFilterMightContain])
            case _ => false
          }
        case _ => false
      })
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

  test("one equality predicate does not justify extra dimension scans") {
    ensureDimension()
    val one = query.replace(" AND s.container='c-2'", "")
    val input = withSQLConf(
      key -> "false",
      "spark.sql.optimizer.runtime.bloomFilter.creationSideThreshold" -> "1") {
      sql(one).queryExecution.optimizedPlan
    }
    withSQLConf(
      key -> "true",
      "spark.sql.optimizer.runtime.bloomFilter.applicationSideScanSizeThreshold" -> "0") {
      assert(rule.apply(input) eq input)
    }
  }

  test("anti and outer joins do not prune a preserved side") {
    val input = prepared.collectFirst {
      case join: Join if join.left.exists {
            case Filter(condition, _) => condition.toString.contains("brand")
            case _ => false
          } => join
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
    ensureDimension()
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
