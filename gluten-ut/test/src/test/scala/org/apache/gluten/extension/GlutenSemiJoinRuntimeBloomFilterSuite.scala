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
import org.apache.spark.sql.catalyst.plans.{Inner, LeftOuter}
import org.apache.spark.sql.catalyst.plans.logical.{Aggregate, Filter, Join, LogicalPlan}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.test.SharedSparkSession

import org.apache.commons.io.FileUtils

import java.nio.file.Files

class GlutenSemiJoinRuntimeBloomFilterSuite extends GlutenQueryTest with SharedSparkSession {
  private val key = "spark.gluten.sql.columnar.backend.velox.runtimeBloom.semiJoin.enabled"
  private val className = "org.apache.gluten.extension.columnar.SemiJoinRuntimeBloomFilter"
  private val pairs =
    "SELECT b1.order_no FROM semi_big b1 JOIN semi_big b2 ON b1.order_no = b2.order_no " +
      "WHERE b1.wh <> b2.wh"
  private val query =
    s"""
       |SELECT f.fid, f.order_no, f.amount FROM semi_fact f JOIN semi_dim d ON f.dkey = d.dkey
       |WHERE d.flag = 'x'
       |  AND f.order_no IN ($pairs)
       |  AND f.order_no IN (SELECT r.order_no FROM semi_ret r JOIN ($pairs) w
       |                     ON r.order_no = w.order_no)
       |""".stripMargin
  private val anti =
    """
      |SELECT f.fid, f.order_no FROM semi_fact f JOIN semi_dim d ON f.dkey = d.dkey
      |WHERE d.flag = 'x' AND NOT EXISTS
      |  (SELECT 1 FROM semi_big b WHERE b.order_no = f.order_no AND b.wh = 1)
      |""".stripMargin
  private val grouped =
    """
      |SELECT f.fid, f.order_no FROM semi_fact f JOIN semi_dim d ON f.dkey = d.dkey
      |WHERE d.flag = 'x' AND f.order_no IN
      |  (SELECT order_no FROM semi_big GROUP BY order_no HAVING count(DISTINCT wh) > 1)
      |""".stripMargin

  private lazy val directory = Files.createTempDirectory("semi-join-bloom-ut")
  private var written = false

  override protected def afterAll(): Unit = {
    try super.afterAll()
    finally FileUtils.deleteDirectory(directory.toFile)
  }

  // Negative, NULL and duplicated keys exercise Bloom hashing and existence
  // semantics; flag='x' keeps a small selective dimension.
  private def ensureTables(): Unit = {
    val tables = Seq(
      "semi_fact" -> ("SELECT id AS fid, id % 97 AS dkey, CASE WHEN id % 13 = 0 THEN NULL " +
        "ELSE CAST(id % 6007 - 3000 AS INT) END AS order_no, CAST(id % 89 AS DECIMAL(7,2)) " +
        "AS amount FROM range(0, 20000, 1, 4)"),
      "semi_dim" -> ("SELECT id AS dkey, CASE WHEN id % 17 = 0 THEN 'x' ELSE 'y' END AS flag " +
        "FROM range(0, 97, 1, 1)"),
      "semi_big" -> ("SELECT CASE WHEN id % 29 = 0 THEN NULL ELSE CAST(id % 9001 - 4500 AS INT) " +
        "END AS order_no, CASE WHEN id % 31 = 0 THEN NULL ELSE CAST((id / 9001) % 3 AS INT) " +
        "END AS wh FROM range(0, 60000, 1, 4)"),
      "semi_ret" -> ("SELECT CAST(id % 5003 - 2500 AS INT) AS order_no FROM range(0, 8000, 1, 2)")
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
      cancel("Semi-join runtime Bloom filters are specific to Velox")
    }
    Class.forName(className).getConstructor(classOf[SparkSession])
      .newInstance(spark).asInstanceOf[Rule[LogicalPlan]]
  }

  private val stock = Seq(
    "spark.gluten.enabled" -> "true",
    "spark.sql.autoBroadcastJoinThreshold" -> "-1",
    "spark.sql.optimizer.runtime.bloomFilter.applicationSideScanSizeThreshold" -> "0")

  private def optimized(text: String): LogicalPlan =
    withSQLConf(stock :+ (key -> "false"): _*) {
      ensureTables()
      sql(text).queryExecution.optimizedPlan
    }

  private def ours(condition: Expression): Boolean = condition.exists {
    case BloomFilterMightContain(subquery: ScalarSubquery, _) => subquery.plan.exists {
        case aggregate: Aggregate =>
          aggregate.aggregateExpressions.exists(_.name == "gluten_semi_join_bloom_filter")
        case _ => false
      }
    case _ => false
  }

  private def filters(plan: LogicalPlan): Int = plan.collect {
    case Filter(condition, _) => conjuncts(condition).count(ours)
  }.sum

  private def conjuncts(condition: Expression): Seq[Expression] =
    condition match {
      case And(left, right) => conjuncts(left) ++ conjuncts(right)
      case other => Seq(other)
    }

  test("default disabled preserves the existing runtime-filter plan") {
    val input = optimized(query)
    withSQLConf(key -> "false")(assert(rule.apply(input) eq input))
  }

  test("existence side is pruned through the inner self join and stays idempotent") {
    val input = optimized(query)
    withSQLConf(stock :+ (key -> "true"): _*) {
      val changed = rule.apply(input)
      assert(filters(changed) >= 2)
      // Constraint inference reaches both inputs of the inner self join.
      assert(changed.exists {
        case join @ Join(left, right, Inner, _, _) =>
          join.left.exists {
            case Filter(c, _) => ours(c)
            case _ => false
          } && join.right.exists {
            case Filter(c, _) => ours(c)
            case _ => false
          } && left.output.exists(_.name == "wh") && right.output.exists(_.name == "wh")
        case _ => false
      })
      assert(changed.output == input.output)
      assert(rule.apply(changed).fastEquals(changed))
      // The retained fact scan does not wait for a filter built from itself.
      assert(!changed.exists {
        case Filter(c, child) => ours(c) && child.output.exists(_.name == "fid")
        case _ => false
      })
    }
  }

  test("filter budget and missing stock runtime filter prevent extra subqueries") {
    val input = optimized(query)
    withSQLConf(stock ++ Seq(key -> "true", key.stripSuffix("enabled") + "maxFilters" -> "0"): _*) {
      assert(rule.apply(input) eq input)
    }
    val plain = withSQLConf(
      stock ++ Seq(
        key -> "false",
        "spark.sql.optimizer.runtime.bloomFilter.enabled" -> "false"): _*) {
      ensureTables()
      sql(query).queryExecution.optimizedPlan
    }
    withSQLConf(stock :+ (key -> "true"): _*)(assert(rule.apply(plain) eq plain))
  }

  test("shared existence subtrees under different retained sides stay unfiltered") {
    val branch = (flag: String) =>
      s"SELECT f.fid FROM semi_fact f JOIN semi_dim d ON f.dkey = d.dkey " +
        s"WHERE d.flag = '$flag' AND f.order_no IN ($pairs)"
    val input = optimized(branch("x") + " UNION ALL " + branch("y"))
    withSQLConf(stock :+ (key -> "true"): _*) {
      assert(filters(rule.apply(input)) == 0)
    }
  }

  test("outer joins are not treated as existence joins") {
    val input = optimized(query).collectFirst {
      case join: Join if join.joinType.toString.contains("Semi") => join
    }.get
    val other = input.copy(joinType = LeftOuter)
    withSQLConf(stock :+ (key -> "true"): _*) {
      // Only the nested semi join creates a filter. Spark's own constraint
      // inference may then legally copy it to the outer join's right side.
      val changed = rule.apply(other).asInstanceOf[Join]
      val created = changed.collect {
        case Filter(c, _) => c.collect {
            case e @ BloomFilterMightContain(s: ScalarSubquery, _) if ours(e) => s.plan.canonicalized
          }
      }.flatten.distinct
      assert(changed.joinType == LeftOuter && created.size == 1)
    }
  }

  Seq("query" -> query, "anti" -> anti, "grouped" -> grouped).foreach {
    case (name, text) =>
      test(s"$name results match vanilla Spark with AQE on and off") {
        ensureTables()
        // The shared test session has no Gluten extensions; register the
        // late optimizer rule exactly as the Velox backend does.
        Class.forName(className).getMethod("install", classOf[SparkSession]).invoke(null, spark)
        val expected = withSQLConf("spark.gluten.enabled" -> "false", key -> "false") {
          sql(text).collect().toSeq
        }
        assert(expected.nonEmpty)
        Seq("false", "true").foreach {
          aqe =>
            withSQLConf(stock ++ Seq("spark.sql.adaptive.enabled" -> aqe, key -> "true"): _*) {
              val df = sql(text)
              // The anti probe scans a single table: no extra serial filter job.
              val count = filters(df.queryExecution.optimizedPlan)
              assert(if (name == "anti") count == 0 else count >= 1, name)
              checkAnswer(df, expected)
            }
        }
      }
  }
}
