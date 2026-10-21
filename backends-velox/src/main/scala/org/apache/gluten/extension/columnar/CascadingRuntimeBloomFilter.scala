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
package org.apache.gluten.extension.columnar

import org.apache.gluten.config.VeloxConfig

import org.apache.spark.internal.Logging
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate.BloomFilterAggregate
import org.apache.spark.sql.catalyst.optimizer.{ColumnPruning, ConstantFolding, PushDownPredicates}
import org.apache.spark.sql.catalyst.planning.ExtractEquiJoinKeys
import org.apache.spark.sql.catalyst.plans.Inner
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.trees.TreeNodeTag
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{IntegerType, LongType}

object CascadingRuntimeBloomFilter {
  val key = "spark.gluten.sql.columnar.backend.velox.runtimeBloom.cascade.enabled"
  // Spark executes these experimental rules after its stock runtime filters.
  // Registration uses session-owned state, preserves user rules, and rebinds
  // inherited instances when newSession clones experimental methods.
  def install(session: SparkSession): Rule[LogicalPlan] = {
    val methods = session.experimental
    methods.synchronized {
      methods.extraOptimizations = methods.extraOptimizations
        .filterNot(_.isInstanceOf[CascadingRuntimeBloomFilter]) :+
        CascadingRuntimeBloomFilter(session)
    }
    new Rule[LogicalPlan] { override def apply(plan: LogicalPlan): LogicalPlan = plan }
  }

  val marker = "gluten_cascading_bloom_filter"
  private[columnar] val injected = TreeNodeTag[Boolean]("gluten.cascadingRuntimeBloom.injected")
}

/**
 * A dimension scan can become selective through an earlier runtime Bloom filter, even though
 * Spark's original selective-predicate test does not recognize that expression. Build a second,
 * ordinary bounded Bloom filter from the selected dimension key. Evaluate creation cost on
 * projected keys and push the resulting predicate through existing semi/anti probe paths using
 * Spark's own safe rules. Default disabled. Existing Spark thresholds/configs and resource budgets
 * stay unchanged; this separate heuristic is bounded to two extra filters per query.
 */
case class CascadingRuntimeBloomFilter(session: SparkSession)
  extends Rule[LogicalPlan] with PredicateHelper with Logging {
  import CascadingRuntimeBloomFilter._

  private def hasBloom(plan: LogicalPlan): Boolean = plan.exists {
    case Filter(condition, _) => condition.exists(_.isInstanceOf[BloomFilterMightContain])
    case _ => false
  }

  private def simpleScan(plan: LogicalPlan): Boolean = plan match {
    case Project(expressions, child) => expressions.forall(_.deterministic) && simpleScan(child)
    case Filter(condition, child) =>
      condition.deterministic && !condition.exists {
        case _: ScalaUDF | _: PythonUDF | _: OuterReference => true
        case query: SubqueryExpression => query.isCorrelated
        case _ => false
      } && simpleScan(child)
    case _: LeafNode => !plan.isStreaming && plan.deterministic
    case _ => false
  }

  private def hasBloomForKey(plan: LogicalPlan, wanted: Expression): Boolean = plan.exists {
    case Filter(condition, _) => splitConjunctivePredicates(condition).exists {
        case BloomFilterMightContain(_, XxHash64(Seq(value), _)) => value.semanticEquals(wanted)
        case _ => false
      }
    case _ => false
  }

  private def existingCascades(plan: LogicalPlan): Int = plan.collect {
    case Filter(condition, _) => condition.collect {
        case BloomFilterMightContain(query: ScalarSubquery, _) if query.plan.exists {
              case aggregate: Aggregate => aggregate.aggregateExpressions.exists(_.name == marker)
              case _ => false
            } => 1
      }.sum
  }.sum

  private def maxScanBytes(plan: LogicalPlan): BigInt = {
    val unknown = session.sessionState.conf.getConf(SQLConf.DEFAULT_SIZE_IN_BYTES)
    plan.collect {
      case leaf: LeafNode =>
        val bytes = leaf.stats.sizeInBytes
        if (bytes == unknown) BigInt(0) else bytes
    }.foldLeft(BigInt(0))(_ max _)
  }

  override def apply(plan: LogicalPlan): LogicalPlan = {
    val conf = session.sessionState.conf
    val veloxConf = new VeloxConfig(conf)
    if (
      !veloxConf.runtimeBloomCascadeEnabled ||
      !conf.getConfString("spark.gluten.enabled", "true").toBoolean || plan.isStreaming
    ) return plan
    val maxKeys = veloxConf.runtimeBloomCascadeMaxCreationKeyBytes
    val maximum = veloxConf.runtimeBloomCascadeMaxFilters
    require(maxKeys > 0 && maximum >= 0 && maximum <= 10)
    val minimumApplication =
      conf.getConf(SQLConf.RUNTIME_BLOOM_FILTER_APPLICATION_SIDE_SCAN_SIZE_THRESHOLD)
    var total = existingCascades(plan)
    // One cascade wave is enough to use stock filters. Predicate pushdown can
    // replace attribute aliases, so do not mistake our own filter for another
    // opportunity and rebuild the same dimension subquery on the next pass.
    if (total > 0) return plan
    var added = 0
    def create(
        application: LogicalPlan,
        applicationKey: Expression,
        creation: LogicalPlan,
        creationKey: Expression): Option[LogicalPlan] = {
      if (
        total >= maximum || !applicationKey.isInstanceOf[Attribute] ||
        !creationKey.isInstanceOf[Attribute] || applicationKey.dataType != creationKey.dataType ||
        !(applicationKey.dataType == IntegerType || applicationKey.dataType == LongType) ||
        !simpleScan(creation) || !hasBloom(creation) || hasBloomForKey(
          application,
          applicationKey) ||
        maxScanBytes(application) < minimumApplication
      ) return None
      // This cost projection is not inserted into the actual aggregate, so its
      // generated attribute cannot accidentally replace the original join key.
      val keys = Project(Seq(Alias(creationKey, "creationKey")()), creation)
      logInfo(
        s"CascadingRuntimeBloomFilter candidate key=$creationKey projectedKeyBytes=${keys.stats.sizeInBytes}")
      if (keys.stats.sizeInBytes > maxKeys) return None
      val aggregate = new BloomFilterAggregate(new XxHash64(Seq(creationKey)))
      val result = Alias(aggregate.toAggregateExpression(), marker)()
      val creationPlan = ConstantFolding(ColumnPruning(Aggregate(Nil, Seq(result), creation)))
      val predicate = BloomFilterMightContain(
        ScalarSubquery(creationPlan, Nil),
        new XxHash64(Seq(applicationKey)))
      total += 1
      added += 1
      Some(Filter(predicate, application))
    }
    val changed = plan.transformUp {
      case join @ ExtractEquiJoinKeys(Inner, leftKeys, rightKeys, _, _, left, right, _)
          if !join.getTagValue(injected).contains(true) && total < maximum =>
        var replacement: Option[Join] = None
        leftKeys.zip(rightKeys).iterator.takeWhile(_ => replacement.isEmpty).foreach {
          case (leftKey, rightKey) =>
            replacement = create(right, rightKey, left, leftKey)
              .map(filtered => join.copy(right = filtered))
              .orElse(create(left, leftKey, right, rightKey)
                .map(filtered => join.copy(left = filtered)))
        }
        replacement.map {
          result =>
            result.copyTagsFrom(join)
            result.setTagValue(injected, true)
            result
        }.getOrElse(join)
    }
    if (added == 0) plan
    else {
      logInfo(s"CascadingRuntimeBloomFilter added $added bounded key filters")
      PushDownPredicates(changed)
    }
  }
}
