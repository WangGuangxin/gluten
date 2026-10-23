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
import org.apache.spark.sql.catalyst.plans.{LeftAnti, LeftSemi}
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.trees.TreeNodeTag
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{IntegerType, LongType}

object SemiJoinRuntimeBloomFilter {
  val key = "spark.gluten.sql.columnar.backend.velox.runtimeBloom.semiJoin.enabled"
  // Spark executes these experimental rules after its stock runtime filters.
  // Registration uses session-owned state, preserves user rules, and rebinds
  // inherited instances when newSession clones experimental methods.
  def install(session: SparkSession): Rule[LogicalPlan] = {
    val methods = session.experimental
    methods.synchronized {
      methods.extraOptimizations = methods.extraOptimizations
        .filterNot(_.isInstanceOf[SemiJoinRuntimeBloomFilter]) :+
        SemiJoinRuntimeBloomFilter(session)
    }
    new Rule[LogicalPlan] { override def apply(plan: LogicalPlan): LogicalPlan = plan }
  }

  val marker = "gluten_semi_join_bloom_filter"
  private[columnar] val injected = TreeNodeTag[Boolean]("gluten.semiJoinRuntimeBloom.injected")
}

/**
 * Spark's stock runtime filters only prune the retained (left) side of a left semi join. When that
 * side has already been made selective by a stock runtime Bloom filter, while the existence side is
 * an expensive subtree over a large scan, rows of the existence side whose key is absent from the
 * retained side cannot change the result: a left semi join only tests key existence, and a left
 * anti join only drops retained rows whose key exists. Build an ordinary Bloom filter from the
 * retained keys and apply it on top of the existence side; Spark's own constraint inference and
 * predicate pushdown then carry it through inner equijoins and complete aggregate grouping keys.
 * Bloom false positives only keep extra rows. Null-aware anti joins are not equijoins here and are
 * left unchanged. Only existence sides containing a join or aggregation are filtered. Default
 * disabled; bounded to a few filters per query.
 */
case class SemiJoinRuntimeBloomFilter(session: SparkSession)
  extends Rule[LogicalPlan] with PredicateHelper with Logging {
  import SemiJoinRuntimeBloomFilter._

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

  // A left semi/anti join only removes rows of its retained child, so that
  // child's key domain is a superset of the join output key domain.
  @scala.annotation.tailrec
  private def retainedBase(plan: LogicalPlan): LogicalPlan = plan match {
    case Join(left, _, LeftSemi | LeftAnti, _, _) => retainedBase(left)
    case _ => plan
  }

  private def ours(e: Expression): Boolean = e match {
    case BloomFilterMightContain(query: ScalarSubquery, _) => query.plan.exists {
        case aggregate: Aggregate => aggregate.aggregateExpressions.exists(_.name == marker)
        case _ => false
      }
    case _ => false
  }

  @scala.annotation.tailrec
  private def base(p: LogicalPlan): LogicalPlan = p match {
    case Project(_, child) => base(child)
    case other => other
  }

  private def hasBloomForKey(plan: LogicalPlan, wanted: Expression): Boolean = plan.exists {
    case Filter(condition, _) => splitConjunctivePredicates(condition).exists {
        case BloomFilterMightContain(_, XxHash64(Seq(value), _)) => value.semanticEquals(wanted)
        case _ => false
      }
    case _ => false
  }

  private def existingFilters(plan: LogicalPlan): Int = plan.collect {
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
      !veloxConf.runtimeBloomSemiJoinEnabled ||
      !conf.getConfString("spark.gluten.enabled", "true").toBoolean || plan.isStreaming
    ) return plan
    val maximum = veloxConf.runtimeBloomSemiJoinMaxFilters
    require(maximum >= 0 && maximum <= 10)
    val minimumApplication =
      conf.getConf(SQLConf.RUNTIME_BLOOM_FILTER_APPLICATION_SIDE_SCAN_SIZE_THRESHOLD)
    val numItems = conf.getConf(SQLConf.RUNTIME_BLOOM_FILTER_MAX_NUM_ITEMS)
    val numBits = conf.getConf(SQLConf.RUNTIME_BLOOM_FILTER_MAX_NUM_BITS)
    var total = existingFilters(plan)
    // One wave: after pushdown the filter sits below the semi join under a
    // different attribute and must not be mistaken for a new opportunity.
    if (total > 0) return plan
    // Identical existence subtrees under different retained sides are
    // normally shared by exchange reuse; different filters would split them.
    val retainedByExistence = plan.collect {
      case ExtractEquiJoinKeys(LeftSemi | LeftAnti, _, _, _, _, left, right, _) =>
        right.canonicalized -> retainedBase(left).canonicalized
    }.groupBy(_._1).map { case (k, v) => k -> v.map(_._2).distinct.size }
    var added = 0
    val sources = scala.collection.mutable.ArrayBuffer.empty[LogicalPlan]
    def create(
        application: LogicalPlan,
        applicationKey: Expression,
        retained: LogicalPlan,
        retainedKey: Expression): Option[LogicalPlan] = {
      val creation = retainedBase(retained)
      if (
        total >= maximum || !applicationKey.isInstanceOf[Attribute] ||
        !retainedKey.isInstanceOf[Attribute] || applicationKey.dataType != retainedKey.dataType ||
        !(applicationKey.dataType == IntegerType || applicationKey.dataType == LongType) ||
        !creation.outputSet.contains(retainedKey.asInstanceOf[Attribute]) ||
        !simpleScan(creation) || !hasBloom(creation) ||
        // A plain existence scan is already cheap to probe; the serial filter
        // creation would only add latency. Target joins and aggregations.
        !application.exists(p => p.isInstanceOf[Join] || p.isInstanceOf[Aggregate]) ||
        hasBloomForKey(application, applicationKey) ||
        retainedByExistence.getOrElse(application.canonicalized, 0) > 1 ||
        maxScanBytes(application) < minimumApplication
      ) return None
      // Size the filter for the full stock limits: the retained side is only
      // known to be selective at runtime, so its estimated row count is unusable.
      val aggregate = new BloomFilterAggregate(
        new XxHash64(Seq(retainedKey)),
        Literal(numItems),
        Literal(numBits))
      val result = Alias(aggregate.toAggregateExpression(), marker)()
      val creationPlan = ConstantFolding(ColumnPruning(Aggregate(Nil, Seq(result), creation)))
      val predicate = BloomFilterMightContain(
        ScalarSubquery(creationPlan, Nil),
        new XxHash64(Seq(applicationKey)))
      logInfo(s"SemiJoinRuntimeBloomFilter key=$retainedKey existence=$applicationKey")
      total += 1
      added += 1
      sources += base(creation)
      Some(Filter(predicate, application))
    }
    val changed = plan.transformUp {
      case join @ ExtractEquiJoinKeys(
            joinType @ (LeftSemi | LeftAnti),
            leftKeys,
            rightKeys,
            _,
            _,
            left,
            right,
            _)
          if !join.getTagValue(injected).contains(true) && total < maximum =>
        var replacement: Option[Join] = None
        leftKeys.zip(rightKeys).iterator.takeWhile(_ => replacement.isEmpty).foreach {
          case (leftKey, rightKey) =>
            replacement = create(right, rightKey, left, leftKey)
              .map(filtered => join.copy(right = filtered))
        }
        replacement.map {
          result =>
            result.copyTagsFrom(join)
            result.setTagValue(injected, true)
            logInfo(s"SemiJoinRuntimeBloomFilter pruned $joinType existence side")
            result
        }.getOrElse(join)
    }
    if (added == 0) plan
    else {
      logInfo(s"SemiJoinRuntimeBloomFilter added $added bounded key filters")
      var result = PushDownPredicates(changed)
      for (_ <- 0 until 2) {
        result = PushDownPredicates(InferFiltersFromConstraints(result))
      }
      // Constraint inference also copies the filter back onto the retained
      // scan it was built from, where it passes every row but makes that scan
      // wait for its own filter job. Remove those copies.
      // Compare scans by their non-null-check predicates: inference may add
      // IS NOT NULL on the join key, which never admits extra rows here.
      def signature(p: LogicalPlan): (Set[Expression], LogicalPlan) = base(p) match {
        case Filter(c, child) =>
          (
            splitConjunctivePredicates(c).filterNot(e => ours(e) || e.isInstanceOf[IsNotNull])
              .map(_.canonicalized).toSet,
            child.canonicalized)
        case other => (Set.empty, other.canonicalized)
      }
      val bases = sources.map(signature).toSet
      result.transformUp {
        case f @ Filter(condition, child)
            if splitConjunctivePredicates(condition).exists(ours) &&
              bases.contains(signature(f)) =>
          splitConjunctivePredicates(condition).filterNot(ours).reduceOption(And)
            .map(Filter(_, child)).getOrElse(child)
      }
    }
  }
}
