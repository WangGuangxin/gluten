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
import org.apache.spark.sql.catalyst.plans.Inner
import org.apache.spark.sql.catalyst.plans.logical.{Join, LogicalPlan}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.test.SharedSparkSession

import org.apache.commons.io.FileUtils

import java.nio.file.Files

class GlutenDimensionBridgeJoinReorderSuite extends GlutenQueryTest with SharedSparkSession {
  private val key = "spark.gluten.sql.columnar.backend.velox.joinReorder.dimensionBridge.enabled"
  private val prefix = key.stripSuffix("enabled")
  private val className = "org.apache.gluten.extension.columnar.DimensionBridgeJoinReorder"
  private val query =
    """
      |SELECT s.sid, i.warehouse, d1.week, s.qty - i.qoh AS gap
      |FROM bridge_sales s JOIN bridge_inv i ON s.item = i.item
      |JOIN bridge_item it ON s.item = it.item
      |JOIN bridge_dates d1 ON s.sold_date = d1.date_sk
      |JOIN bridge_dates d2 ON i.inv_date = d2.date_sk
      |WHERE d1.week = d2.week AND i.qoh < s.qty AND d1.year = 2001 AND it.color <> 'red'
      |""".stripMargin

  private lazy val directory = Files.createTempDirectory("dimension-bridge-ut")
  private var written = false

  override protected def afterAll(): Unit = {
    try super.afterAll()
    finally FileUtils.deleteDirectory(directory.toFile)
  }

  // NULL keys, duplicated weeks per date and several warehouses per item
  // make both the large partial-key join and the bridge equality nontrivial.
  private def ensureTables(): Unit = {
    val tables = Seq(
      "bridge_sales" -> ("SELECT id AS sid, CASE WHEN id % 23 = 0 THEN NULL ELSE id % 61 END " +
        "AS item, id % 730 AS sold_date, id % 40 AS qty FROM range(0, 30000, 1, 4)"),
      "bridge_inv" -> ("SELECT id % 61 AS item, (id div 61) % 105 * 7 AS inv_date, id % 5 AS " +
        "warehouse, CASE WHEN id % 17 = 0 THEN NULL ELSE id % 37 END AS qoh " +
        "FROM range(0, 60000, 1, 4)"),
      "bridge_item" -> ("SELECT id AS item, CASE WHEN id % 3 = 0 THEN 'red' ELSE 'blue' END " +
        "AS color FROM range(0, 61, 1, 1)"),
      "bridge_dates" -> ("SELECT id AS date_sk, id div 7 AS week, 2000 + id div 365 AS year " +
        "FROM range(0, 735, 1, 1)")
    )
    tables.foreach {
      case (name, select) =>
        val path = directory.resolve(name).toString
        if (!written) {
          withSQLConf("spark.gluten.enabled" -> "false") {
            sql(select).write.mode("overwrite").parquet(path)
          }
        }
        spark.read.parquet(path).createOrReplaceTempView(name)
    }
    written = true
  }

  private def rule: Rule[LogicalPlan] = {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("Dimension bridge reordering is registered by the Velox backend")
    }
    Class.forName(className).getConstructor(classOf[SparkSession])
      .newInstance(spark).asInstanceOf[Rule[LogicalPlan]]
  }

  private def sizes: Seq[(String, String)] = {
    val bytes = directory.resolve("bridge_dates").toFile.listFiles()
      .filter(_.getName.endsWith(".parquet")).map(_.length).sum
    Seq(prefix + "factMinBytes" -> (bytes * 4).toString, prefix + "dimensionMaxBytes" ->
      (bytes * 2).toString)
  }

  private def optimized(text: String): LogicalPlan = withSQLConf(key -> "false") {
    ensureTables()
    sql(text).queryExecution.optimizedPlan
  }

  // The large join has both the partial item key and the bridged week key.
  private def bridged(plan: LogicalPlan): Boolean = plan.exists {
    case Join(left, right, Inner, Some(condition), _) =>
      val names = condition.references.toSeq.map(_.name)
      left.output.exists(_.name == "qty") && right.output.exists(_.name == "qoh") &&
        names.contains("item") && names.count(_ == "week") >= 1 &&
        condition.collect { case e: EqualTo => e }.size >= 2
    case _ => false
  }

  test("default disabled keeps the heuristic join order") {
    val input = optimized(query)
    withSQLConf(key -> "false")(assert(rule.apply(input) eq input))
  }

  test("bridging dimensions join first and the rewrite is idempotent") {
    val input = optimized(query)
    assert(!bridged(input))
    withSQLConf(sizes :+ (key -> "true"): _*) {
      val changed = rule.apply(input)
      assert(bridged(changed))
      assert(changed.output == input.output)
      assert(rule.apply(changed) eq changed)
    }
  }

  test("size limits keep large or unknown inputs out of the dimension role") {
    val input = optimized(query)
    withSQLConf(Seq(key -> "true", prefix + "dimensionMaxBytes" -> "0"): _*) {
      assert(rule.apply(input) eq input)
    }
    withSQLConf(Seq(key -> "true", prefix + "factMinBytes" -> Long.MaxValue.toString): _*) {
      assert(rule.apply(input) eq input)
    }
  }

  test("results match vanilla Spark with AQE on and off") {
    ensureTables()
    Class.forName(className).getMethod("install", classOf[SparkSession]).invoke(null, spark)
    val expected = withSQLConf("spark.gluten.enabled" -> "false", key -> "false") {
      sql(query).collect().toSeq
    }
    assert(expected.nonEmpty)
    Seq("false", "true").foreach {
      aqe =>
        withSQLConf(sizes ++ Seq("spark.sql.adaptive.enabled" -> aqe, key -> "true"): _*) {
          val df = sql(query)
          assert(bridged(df.queryExecution.optimizedPlan))
          checkAnswer(df, expected)
        }
    }
  }
}
