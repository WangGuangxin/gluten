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
import org.apache.spark.sql.catalyst.plans.logical.{Aggregate, Expand, LogicalPlan}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.StringType

import org.apache.commons.io.FileUtils

import java.nio.file.Files

class GlutenDimensionStringGroupingIdsSuite extends GlutenQueryTest with SharedSparkSession {
  private val key = "spark.gluten.sql.columnar.backend.velox.aggregation.dimensionStringIds.enabled"
  private val prefix = key.stripSuffix("enabled")
  private val className = "org.apache.gluten.extension.columnar.DimensionStringGroupingIds"
  private val rollup =
    """
      |SELECT * FROM (
      |  SELECT category, klass, brand, d_year, s_id, total,
      |         rank() OVER (PARTITION BY category ORDER BY total DESC, s_id, brand) rk
      |  FROM (SELECT i.category, i.klass, i.brand, d.d_year, s.s_id, sum(f.amount) total
      |        FROM ids_fact f JOIN ids_item i ON f.item = i.item
      |        JOIN ids_date d ON f.dt = d.dt JOIN ids_store s ON f.store = s.store
      |        WHERE d.d_year >= 2001
      |        GROUP BY ROLLUP(i.category, i.klass, i.brand, d.d_year, s.s_id)) a) b
      |WHERE rk <= 7
      |""".stripMargin
  private val plain =
    """
      |SELECT substr(i.descr, 1, 3) d3, i.item, f.dt, count(*) cnt
      |FROM ids_fact f JOIN ids_item i ON f.item = i.item
      |GROUP BY substr(i.descr, 1, 3), i.item, f.dt HAVING count(*) > 2
      |""".stripMargin
  private val unused =
    s"SELECT item FROM ($plain) x WHERE dt % 3 = 0"
  private val insideFunction =
    """
      |SELECT i.category, count(DISTINCT i.category) c, max(i.brand) b, sum(f.amount) s
      |FROM ids_fact f JOIN ids_item i ON f.item = i.item GROUP BY i.category
      |""".stripMargin

  private lazy val directory = Files.createTempDirectory("dimension-string-ids-ut")
  private var written = false

  override protected def afterAll(): Unit = {
    try super.afterAll()
    finally FileUtils.deleteDirectory(directory.toFile)
  }

  // NULL and empty strings, shared values across items and NULL foreign keys
  // check injectivity, NULL grouping and restoration.
  private def ensureTables(): Unit = {
    val tables = Seq(
      "ids_fact" -> ("SELECT CASE WHEN id % 41 = 0 THEN NULL ELSE id % 97 END AS item, " +
        "id % 50 AS dt, id % 7 AS store, CAST(id % 89 - 20 AS DECIMAL(7,2)) AS amount " +
        "FROM range(0, 40000, 1, 4)"),
      "ids_item" -> ("SELECT id AS item, CASE WHEN id % 11 = 0 THEN NULL WHEN id % 13 = 0 " +
        "THEN '' ELSE concat('c', id % 5) END AS category, concat('k', id % 9) AS klass, " +
        "CASE WHEN id % 17 = 0 THEN NULL ELSE concat('brand-', id % 23) END AS brand, " +
        "concat('desc-', id % 6, '-', id) AS descr FROM range(0, 97, 1, 1)"),
      "ids_date" -> "SELECT id AS dt, 2000 + id div 12 AS d_year FROM range(0, 50, 1, 1)",
      "ids_store" -> ("SELECT id AS store, CASE WHEN id = 3 THEN NULL ELSE concat('s', id) END " +
        "AS s_id FROM range(0, 7, 1, 1)")
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
      cancel("Dimension string identifiers are registered by the Velox backend")
    }
    Class.forName(className).getConstructor(classOf[SparkSession])
      .newInstance(spark).asInstanceOf[Rule[LogicalPlan]]
  }

  private def limits: Seq[(String, String)] = {
    val bytes = directory.resolve("ids_item").toFile.listFiles()
      .filter(_.getName.endsWith(".parquet")).map(_.length).sum
    Seq(prefix + "factMinBytes" -> (bytes * 3).toString,
      prefix + "dimensionMaxBytes" -> (bytes * 2).toString)
  }

  private def optimized(text: String): LogicalPlan = withSQLConf(key -> "false") {
    ensureTables()
    sql(text).queryExecution.optimizedPlan
  }

  private def stringGroupingOverFact(plan: LogicalPlan): Boolean = plan.exists {
    case a: Aggregate =>
      a.groupingExpressions.exists(_.dataType == StringType) && a.child.exists {
        case e: Expand => true
        case other => other.output.exists(_.name == "amount") || other.output.exists(_.name == "dt")
      }
    case _ => false
  }

  test("default disabled keeps string grouping keys") {
    val input = optimized(rollup)
    withSQLConf(key -> "false")(assert(rule.apply(input) eq input))
  }

  test("grouping-set and unreturned plain keys group by identifiers") {
    Seq(rollup, unused).foreach {
      text =>
        val input = optimized(text)
        assert(stringGroupingOverFact(input))
        withSQLConf(limits :+ (key -> "true"): _*) {
          val changed = rule.apply(input)
          assert(!stringGroupingOverFact(changed), text)
          assert(changed.output.map(_.exprId) == input.output.map(_.exprId))
          assert(changed.output.map(_.dataType) == input.output.map(_.dataType))
          assert(rule.apply(changed).fastEquals(changed))
        }
    }
  }

  test("returned or plain-column keys of plain aggregations are not encoded") {
    val columns = "SELECT x.item FROM (SELECT i.category, i.item, count(*) c FROM ids_fact f " +
      "JOIN ids_item i ON f.item = i.item GROUP BY i.category, i.item) x"
    Seq(plain, columns).foreach {
      text =>
        val input = optimized(text)
        withSQLConf(limits :+ (key -> "true"): _*)(assert(rule.apply(input) eq input, text))
    }
  }

  test("size limits and non pass-through uses keep string keys") {
    val input = optimized(rollup)
    withSQLConf(Seq(key -> "true", prefix + "factMinBytes" -> Long.MaxValue.toString): _*) {
      assert(rule.apply(input) eq input)
    }
    withSQLConf(Seq(key -> "true", prefix + "dimensionMaxBytes" -> "0"): _*) {
      assert(rule.apply(input) eq input)
    }
    // A large table reached only through an existence join adds no rows.
    val existence = optimized(
      "SELECT i.category, count(*) c FROM ids_item i WHERE EXISTS " +
        "(SELECT 1 FROM ids_fact f WHERE f.item = i.item) GROUP BY i.category")
    withSQLConf(limits :+ (key -> "true"): _*) {
      assert(rule.apply(existence) eq existence)
    }
  }

  Seq("rollup" -> rollup, "plain" -> plain, "unused" -> unused, "function" -> insideFunction)
    .foreach {
      case (name, text) =>
        test(s"$name results match vanilla Spark with AQE on and off") {
          ensureTables()
          Class.forName(className).getMethod("install", classOf[SparkSession])
            .invoke(null, spark)
          val expected = withSQLConf("spark.gluten.enabled" -> "false", key -> "false") {
            sql(text).collect().toSeq
          }
          assert(expected.nonEmpty)
          Seq("false", "true").foreach {
            aqe =>
              withSQLConf(limits ++ Seq("spark.sql.adaptive.enabled" -> aqe, key -> "true"): _*) {
                checkAnswer(sql(text), expected)
              }
          }
        }
    }
}
