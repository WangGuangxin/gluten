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
import org.apache.spark.sql.catalyst.optimizer.{ColumnPruning, ConstantFolding, InferFiltersFromConstraints, PushDownPredicates}
import org.apache.spark.sql.catalyst.planning.ExtractEquiJoinKeys
import org.apache.spark.sql.catalyst.plans.Inner
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.trees.TreeNodeTag
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{IntegerType, LongType}

object SelectiveKeyRuntimeBloomFilter {
  val key = "spark.gluten.sql.columnar.backend.velox.runtimeBloom.selectiveKeys.enabled"
  // Spark executes these experimental rules after its stock runtime filters.
  // Registration uses session-owned state, preserves user rules, and rebinds
  // inherited instances when newSession clones experimental methods.
  def install(session: SparkSession): Rule[LogicalPlan] = {
    val methods = session.experimental
    methods.synchronized {
      methods.extraOptimizations = methods.extraOptimizations
        .filterNot(_.isInstanceOf[SelectiveKeyRuntimeBloomFilter]) :+
        SelectiveKeyRuntimeBloomFilter(session)
    }
    new Rule[LogicalPlan] { override def apply(plan: LogicalPlan): LogicalPlan = plan }
  }

  val marker = "gluten_selective_key_bloom_filter"
  private[columnar] val injected = TreeNodeTag[Boolean]("gluten.selectiveKeyRuntimeBloom.injected")
}

/**
 * A dimension with multiple literal equality predicates may be cheap after filtering even when its
 * source byte count exceeds Spark's stock Bloom creation threshold. Bound creation by projected key
 * bytes and require two distinct filtered attributes; keep stock application-size/Bloom-memory
 * caps. Infer the predicate through equivalent grouping keys using Spark rules. Default disabled;
 * no result rewriting or data-layout changes.
 */
case class SelectiveKeyRuntimeBloomFilter(session: SparkSession)
  extends Rule[LogicalPlan] with PredicateHelper with Logging {
  import SelectiveKeyRuntimeBloomFilter._

  // Two independent equality predicates are a bounded selectivity heuristic,
  // not an assumption about data values. Bloom false positives remain safe.
  private def selectiveDimension(plan: LogicalPlan, joinKey: Expression): Boolean = {
    val attributes = plan.collect {
      case Filter(condition, _) => splitConjunctivePredicates(condition).collect {
          case EqualTo(a: Attribute, value: Literal)
              if value.value != null && !a.semanticEquals(joinKey) => a
          case EqualTo(value: Literal, a: Attribute)
              if value.value != null && !a.semanticEquals(joinKey) => a
        }
    }.flatten
    attributes.map(_.exprId).distinct.size >= 2
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
      !veloxConf.runtimeBloomSelectiveKeysEnabled ||
      !conf.getConfString("spark.gluten.enabled", "true").toBoolean || plan.isStreaming
    ) return plan
    val maxKeys = veloxConf.runtimeBloomSelectiveKeysMaxCreationKeyBytes
    val maximum = veloxConf.runtimeBloomSelectiveKeysMaxFilters
    require(maxKeys > 0 && maximum >= 0 && maximum <= 10)
    val minimumApplication =
      conf.getConf(SQLConf.RUNTIME_BLOOM_FILTER_APPLICATION_SIDE_SCAN_SIZE_THRESHOLD)
    var total = existingCascades(plan)
    // One wave prevents re-inference from creating repeated dimension jobs.
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
        !simpleScan(creation) || !selectiveDimension(creation, creationKey) || hasBloomForKey(
          application,
          applicationKey) ||
        maxScanBytes(application) < minimumApplication
      ) return None
      // This cost projection is not inserted into the actual aggregate, so its
      // generated attribute cannot accidentally replace the original join key.
      val keys = Project(Seq(Alias(creationKey, "creationKey")()), creation)
      logInfo(
        s"SelectiveKeyRuntimeBloomFilter candidate key=$creationKey projectedKeyBytes=${keys.stats.sizeInBytes}")
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
      logInfo(s"SelectiveKeyRuntimeBloomFilter added $added bounded key filters")
      // Spark's own equijoin-constraint inference carries the same key-domain
      // predicate into equivalent aggregate grouping keys. Predicate pushdown
      // only crosses aggregates for their complete grouping keys, preserving
      // every contributing row of each retained group.
      var result = PushDownPredicates(changed)
      for (_ <- 0 until 2) {
        result = PushDownPredicates(InferFiltersFromConstraints(result))
      }
      result
    }
  }
}
