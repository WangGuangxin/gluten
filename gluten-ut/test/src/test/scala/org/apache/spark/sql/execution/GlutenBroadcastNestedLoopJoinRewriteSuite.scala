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
package org.apache.spark.sql.execution

import org.apache.gluten.config.GlutenConfig
import org.apache.gluten.execution.BroadcastNestedLoopJoinExecTransformer
import org.apache.gluten.utils.BackendTestUtils

import org.apache.spark.SparkConf
import org.apache.spark.sql.{DataFrame, GlutenQueryTest, Row}
import org.apache.spark.sql.catalyst.plans.{ExistenceJoin, Inner, JoinType}
import org.apache.spark.sql.execution.adaptive.{AdaptiveSparkPlanExec, AdaptiveSparkPlanHelper}
import org.apache.spark.sql.execution.joins.BroadcastNestedLoopJoinExec
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession

/**
 * Tests for the Velox rewrites of broadcast nested loop joins that keep unmatched rows of the build
 * side, and of left semi and left anti joins. The rewrites are a Velox backend feature, hence each
 * test is guarded with `assumeVeloxBackend()`.
 */
class GlutenBroadcastNestedLoopJoinRewriteSuite
  extends GlutenQueryTest
  with SharedSparkSession
  with AdaptiveSparkPlanHelper {
  import testImplicits._

  // This shared suite is also compiled when the Velox backend is not on the classpath.
  private val joinTypeRewriteKey =
    "spark.gluten.sql.columnar.backend.velox.broadcastNLJ.joinTypeRewrite.enabled"
  private val fullOuterRewriteThresholdKey =
    "spark.gluten.sql.columnar.backend.velox.broadcastNLJ.fullOuterRewriteThreshold"

  override protected def sparkConf: SparkConf = {
    super.sparkConf
      .set("spark.plugins", "org.apache.gluten.GlutenPlugin")
      .set("spark.default.parallelism", "1")
      .set("spark.memory.offHeap.enabled", "true")
      .set("spark.memory.offHeap.size", "1024MB")
      .set("spark.ui.enabled", "false")
      .set(GlutenConfig.GLUTEN_UI_ENABLED.key, "false")
      .set(GlutenConfig.COLUMNAR_FORCE_SHUFFLED_HASH_JOIN_ENABLED.key, "false")
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    // Several partitions per side, so a single task cannot see every streamed row.
    Seq[(java.lang.Integer, String)]((1, "a"), (2, "b"), (3, null), (null, "d"), (3, null))
      .toDF("k", "v")
      .repartition(3)
      .createOrReplaceTempView("bnlj_l")
    Seq[(java.lang.Integer, java.lang.Double)]((1, 1.5), (2, 7.0), (4, null), (null, 9.0))
      .toDF("k2", "w")
      .repartition(4)
      .createOrReplaceTempView("bnlj_r")
    spark.table("bnlj_r").where("w > 1000").createOrReplaceTempView("bnlj_r_empty")
  }

  override def afterAll(): Unit = {
    try {
      Seq("bnlj_l", "bnlj_r", "bnlj_r_empty").foreach(spark.catalog.dropTempView)
    } finally {
      super.afterAll()
    }
  }

  private def assumeVeloxBackend(): Unit = assume(BackendTestUtils.isVeloxBackendLoaded())

  private def finalPlan(df: DataFrame): SparkPlan = {
    val executedPlan = df.queryExecution.executedPlan
    stripAQEPlan(executedPlan match {
      case adaptivePlan: AdaptiveSparkPlanExec => adaptivePlan.executedPlan
      case otherPlan => otherPlan
    })
  }

  private def vanillaBnljs(plan: SparkPlan): Seq[BroadcastNestedLoopJoinExec] =
    collect(plan) { case bnlj: BroadcastNestedLoopJoinExec => bnlj }

  private def nativeJoinTypes(plan: SparkPlan): Seq[JoinType] =
    collect(plan) { case bnlj: BroadcastNestedLoopJoinExecTransformer => bnlj.joinType }

  /**
   * Runs the query with Gluten disabled and enabled, compares the answers, and returns the final
   * Gluten plan after checking that Spark planned the expected join shape.
   */
  private def checkAgainstVanilla(sqlText: String, expectedVanillaJoin: String): SparkPlan = {
    val expected = withSQLConf(GlutenConfig.GLUTEN_ENABLED.key -> "false") {
      val df = sql(sqlText)
      val rows = df.collect().toSeq
      val joins = vanillaBnljs(finalPlan(df)).map(j => s"${j.joinType}/${j.buildSide}")
      assert(joins.contains(expectedVanillaJoin), s"Spark planned $joins for $sqlText")
      rows
    }
    val df = sql(sqlText)
    checkAnswer(df, expected)
    finalPlan(df)
  }

  private def assertRewritten(plan: SparkPlan): Unit = {
    assert(vanillaBnljs(plan).isEmpty, s"Expected no vanilla BNLJ:\n${plan.treeString}")
    assert(
      nativeJoinTypes(plan).exists(_.isInstanceOf[ExistenceJoin]),
      s"Expected a native existence join:\n${plan.treeString}")
  }

  private val buildLeftOuterJoins = Seq(
    "SELECT /*+ BROADCAST(l) */ * FROM bnlj_l l LEFT JOIN bnlj_r r ON true",
    "SELECT /*+ BROADCAST(l) */ * FROM bnlj_l l LEFT JOIN bnlj_r r ON r.w > 5",
    "SELECT /*+ BROADCAST(l) */ * FROM bnlj_l l LEFT JOIN bnlj_r r ON l.k > 1",
    "SELECT /*+ BROADCAST(l) */ * FROM bnlj_l l LEFT JOIN bnlj_r r " +
      "ON l.k > 1 AND r.w > 5 AND l.v IS NULL",
    "SELECT /*+ BROADCAST(l) */ * FROM bnlj_l l LEFT JOIN bnlj_r r ON l.k > 2 OR l.v = 'a'",
    "SELECT /*+ BROADCAST(l) */ * FROM bnlj_l l LEFT JOIN bnlj_r r " +
      "ON l.k = (SELECT max(k2) - 2 FROM bnlj_r)",
    // The broadcast side contains a broadcast hash join with its own broadcast build side.
    "SELECT /*+ BROADCAST(x) */ * FROM (SELECT /*+ BROADCAST(s) */ l.* FROM bnlj_l l " +
      "JOIN bnlj_r s ON l.k = s.k2) x LEFT JOIN bnlj_r r ON r.w > 5"
  )

  // With AQE, Spark replaces a join with an empty side, so these queries run without it.
  private val emptyStreamedSideJoins = Seq(
    "SELECT /*+ BROADCAST(l) */ * FROM bnlj_l l LEFT JOIN bnlj_r_empty r ON l.k > 1" ->
      "LeftOuter/BuildLeft",
    "SELECT /*+ BROADCAST(r) */ * FROM (SELECT * FROM bnlj_l WHERE k > 100) l " +
      "RIGHT JOIN bnlj_r r ON true" -> "RightOuter/BuildRight",
    "SELECT /*+ BROADCAST(l) */ * FROM bnlj_l l FULL JOIN bnlj_r_empty r ON l.k > 2" ->
      "FullOuter/BuildLeft",
    "SELECT /*+ BROADCAST(r) */ * FROM bnlj_l l LEFT ANTI JOIN bnlj_r_empty r ON true" ->
      "LeftAnti/BuildRight"
  )

  test("Left outer BNLJ building the left side is rewritten when uncorrelated") {
    assumeVeloxBackend()
    Seq(true, false).foreach {
      aqeEnabled =>
        withSQLConf(
          SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> aqeEnabled.toString,
          SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
          SQLConf.ANSI_ENABLED.key -> "false"
        ) {
          buildLeftOuterJoins.foreach {
            sqlText =>
              val plan = checkAgainstVanilla(sqlText, "LeftOuter/BuildLeft")
              assertRewritten(plan)
              assert(nativeJoinTypes(plan).contains(Inner), plan.treeString)
          }
        }
    }
  }

  test("Right outer BNLJ building the right side is rewritten when uncorrelated") {
    assumeVeloxBackend()
    withSQLConf(
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
      SQLConf.ANSI_ENABLED.key -> "false") {
      assertRewritten(
        checkAgainstVanilla(
          "SELECT /*+ BROADCAST(r) */ * FROM bnlj_l l RIGHT JOIN bnlj_r r ON r.w > 2 AND l.k <> 2",
          "RightOuter/BuildRight"))
    }
  }

  test("Left outer BNLJ building the left side is not rewritten when correlated") {
    assumeVeloxBackend()
    withSQLConf(
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
      SQLConf.ANSI_ENABLED.key -> "false") {
      val plan = checkAgainstVanilla(
        "SELECT /*+ BROADCAST(l) */ * FROM bnlj_l l LEFT JOIN bnlj_r r ON l.k < r.k2",
        "LeftOuter/BuildLeft")
      assert(vanillaBnljs(plan).nonEmpty, plan.treeString)
    }
  }

  test("Left outer BNLJ building the left side is not rewritten when disabled") {
    assumeVeloxBackend()
    withSQLConf(
      joinTypeRewriteKey -> "false",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
      SQLConf.ANSI_ENABLED.key -> "false") {
      val plan = checkAgainstVanilla(buildLeftOuterJoins.head, "LeftOuter/BuildLeft")
      assert(vanillaBnljs(plan).nonEmpty, plan.treeString)
    }
  }

  test("Full outer BNLJ with an uncorrelated condition is rewritten regardless of size") {
    assumeVeloxBackend()
    Seq(true, false).foreach {
      aqeEnabled =>
        withSQLConf(
          // The whole streamed side is never broadcast for an uncorrelated condition.
          fullOuterRewriteThresholdKey -> "0",
          SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> aqeEnabled.toString,
          SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
          SQLConf.ANSI_ENABLED.key -> "false"
        ) {
          Seq(
            "SELECT /*+ BROADCAST(l) */ * FROM bnlj_l l FULL JOIN bnlj_r r ON true",
            "SELECT /*+ BROADCAST(l) */ * FROM bnlj_l l FULL JOIN bnlj_r r ON l.k > 2"
          ).foreach(sqlText => assertRewritten(checkAgainstVanilla(sqlText, "FullOuter/BuildLeft")))
          Seq(
            "SELECT /*+ BROADCAST(r) */ * FROM bnlj_l l FULL JOIN bnlj_r r ON r.w > 2",
            "SELECT /*+ BROADCAST(r) */ * FROM bnlj_l l FULL JOIN bnlj_r r ON r.w > 2 AND l.k > 1"
          ).foreach(
            sqlText => assertRewritten(checkAgainstVanilla(sqlText, "FullOuter/BuildRight")))
        }
    }
  }

  test("Left semi and left anti BNLJ become a native existence join") {
    assumeVeloxBackend()
    Seq(true, false).foreach {
      aqeEnabled =>
        withSQLConf(
          SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> aqeEnabled.toString,
          SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
          SQLConf.ANSI_ENABLED.key -> "false"
        ) {
          assertRewritten(
            checkAgainstVanilla(
              "SELECT * FROM bnlj_l l WHERE EXISTS " +
                "(SELECT 1 FROM bnlj_r r WHERE l.k < r.k2 OR r.w IS NULL)",
              "LeftSemi/BuildRight"))
          assertRewritten(
            checkAgainstVanilla(
              "SELECT * FROM bnlj_l l WHERE NOT EXISTS " +
                "(SELECT 1 FROM bnlj_r r WHERE l.k < r.k2 - 2)",
              "LeftAnti/BuildRight"))
        }
    }
    withSQLConf(
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1") {
      checkAnswer(
        sql("SELECT /*+ BROADCAST(r) */ k FROM bnlj_l l LEFT SEMI JOIN bnlj_r r ON true"),
        Seq(Row(1), Row(2), Row(3), Row(null), Row(3)))
    }
  }

  test("Rewrites keep all build side rows when the streamed side is empty") {
    assumeVeloxBackend()
    withSQLConf(
      fullOuterRewriteThresholdKey -> "0",
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
      SQLConf.ANSI_ENABLED.key -> "false"
    ) {
      emptyStreamedSideJoins.foreach {
        case (sqlText, vanillaJoin) => assertRewritten(checkAgainstVanilla(sqlText, vanillaJoin))
      }
    }
  }
}
