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
import org.apache.spark.sql.catalyst.optimizer.{CollapseProject, ColumnPruning, RemoveNoopOperators, ReorderJoin}
import org.apache.spark.sql.catalyst.plans.{Inner, InnerLike}
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.internal.SQLConf

object DimensionBridgeJoinReorder {
  val key = "spark.gluten.sql.columnar.backend.velox.joinReorder.dimensionBridge.enabled"
  // Run as a late experimental rule: Spark's heuristic ReorderJoin is part of
  // the fixed-point operator batch and would flatten and undo the grouping.
  def install(session: SparkSession): Rule[LogicalPlan] = {
    val methods = session.experimental
    methods.synchronized {
      methods.extraOptimizations = methods.extraOptimizations
        .filterNot(_.isInstanceOf[DimensionBridgeJoinReorder]) :+
        DimensionBridgeJoinReorder(session)
    }
    new Rule[LogicalPlan] { override def apply(plan: LogicalPlan): LogicalPlan = plan }
  }
}

/**
 * Spark's heuristic join order follows the FROM clause. When two large inputs are joined on a
 * partial key and two small dimensions, each joined to one of them, are related by an additional
 * equality (for example item = item and sold-date week = inventory-date week), that equality is
 * applied only after the large join has multiplied rows. Join each large input with its bridging
 * dimension first so the extra equality becomes part of the large join key. Only inner equijoins
 * with deterministic conditions are regrouped; every original condition is retained, so results are
 * unchanged. Default disabled.
 */
case class DimensionBridgeJoinReorder(session: SparkSession)
  extends Rule[LogicalPlan] with PredicateHelper with Logging {

  private def joinLike(plan: LogicalPlan): Boolean = plan match {
    case Join(_, _, Inner, _, JoinHint.NONE) => true
    case Project(list, child) => list.forall(_.isInstanceOf[Attribute]) && joinLike(child)
    case Filter(condition, child) => condition.deterministic && joinLike(child)
    case _ => false
  }

  // Attribute-only projections between inner joins only prune columns; the
  // rebuilt tree restores the original output with a final projection.
  private def flatten(plan: LogicalPlan): (Seq[LogicalPlan], Seq[Expression]) = plan match {
    case Join(left, right, Inner, condition, JoinHint.NONE)
        if condition.forall(_.deterministic) =>
      val (l, lc) = flatten(left)
      val (r, rc) = flatten(right)
      (l ++ r, lc ++ rc ++ condition.toSeq.flatMap(splitConjunctivePredicates))
    case Project(list, child) if list.forall(_.isInstanceOf[Attribute]) && joinLike(child) =>
      flatten(child)
    case Filter(condition, child) if condition.deterministic && joinLike(child) =>
      val (items, conditions) = flatten(child)
      (items, conditions ++ splitConjunctivePredicates(condition))
    case other => (Seq(other), Nil)
  }

  private def scanBytes(plan: LogicalPlan): BigInt = {
    val unknown = session.sessionState.conf.getConf(SQLConf.DEFAULT_SIZE_IN_BYTES)
    plan.collect {
      case leaf: LeafNode =>
        val bytes = leaf.stats.sizeInBytes
        if (bytes == unknown) BigInt(-1) else bytes
    }.foldLeft(BigInt(0))(_ max _)
  }

  private def owner(items: Seq[LogicalPlan], e: Expression): Option[Int] = {
    val owners = items.indices.filter(i => e.references.subsetOf(items(i).outputSet))
    if (e.references.nonEmpty && owners.size == 1) Some(owners.head) else None
  }

  private case class Edge(left: Int, right: Int, condition: Expression)

  private def edges(items: Seq[LogicalPlan], conditions: Seq[Expression]): Seq[Edge] =
    conditions.flatMap {
      case c @ EqualTo(a, b) =>
        (owner(items, a), owner(items, b)) match {
          case (Some(i), Some(j)) if i != j => Some(Edge(i, j, c))
          case _ => None
        }
      case _ => None
    }

  private def between(all: Seq[Edge], i: Int, j: Int): Seq[Edge] =
    all.filter(e => (e.left == i && e.right == j) || (e.left == j && e.right == i))

  private def contains(plan: LogicalPlan, item: LogicalPlan): Boolean =
    plan.exists(_ eq item)

  // The large join is already safe when one of its sides contains a bridging
  // dimension: this both detects earlier rewrites and avoids repeated work.
  private def firstJoin(plan: LogicalPlan, a: LogicalPlan, b: LogicalPlan): Option[Join] = {
    plan.collectFirst {
      case join: Join
          if (contains(join.left, a) && contains(join.right, b)) ||
            (contains(join.left, b) && contains(join.right, a)) => join
    }.flatMap {
      join =>
        val deeper = Seq(join.left, join.right).flatMap(child => firstJoin(child, a, b))
        deeper.headOption.orElse(Some(join))
    }
  }

  private def rewrite(plan: LogicalPlan): Option[LogicalPlan] = {
    val veloxConf = new VeloxConfig(session.sessionState.conf)
    val factMin = veloxConf.dimensionBridgeFactMinBytes
    val dimensionMax = veloxConf.dimensionBridgeDimensionMaxBytes
    val (items, conditions) = flatten(plan)
    if (items.size < 4 || !conditions.forall(_.deterministic)) return None
    val all = edges(items, conditions)
    val bytes = items.map(scanBytes)
    def fact(i: Int) = bytes(i) >= factMin
    def dimension(i: Int) = bytes(i) >= 0 && bytes(i) <= dimensionMax
    val candidates = for {
      bridge <- all.iterator if dimension(bridge.left) && dimension(bridge.right)
      (d1, d2) <- Iterator((bridge.left, bridge.right))
      f1 <- items.indices.iterator
      if fact(f1) && between(all, f1, d1).nonEmpty
      f2 <- items.indices.iterator
      if f2 != f1 && fact(f2) && between(all, f2, d2).nonEmpty && between(all, f1, f2).nonEmpty &&
        between(all, f1, d2).isEmpty && between(all, f2, d1).isEmpty
      join <- firstJoin(plan, items(f1), items(f2)).iterator
      sides = Seq(join.left, join.right)
      if !sides.exists(side => contains(side, items(d1)) || contains(side, items(d2)))
    } yield (f1, d1, f2, d2)
    if (!candidates.hasNext) return None
    val (f1, d1, f2, d2) = candidates.next()
    val first = between(all, f1, d1).map(_.condition)
    val second = between(all, f2, d2).map(_.condition)
    val left = Join(items(f1), items(d1), Inner, first.reduceOption(And), JoinHint.NONE)
    val right = Join(items(f2), items(d2), Inner, second.reduceOption(And), JoinHint.NONE)
    val used = (first ++ second).toSet
    val rest = items.indices.filterNot(Set(f1, d1, f2, d2)).map(i => (items(i), Inner: InnerLike))
    val ordered = ReorderJoin.createOrderedJoin(
      Seq((left, Inner: InnerLike), (right, Inner: InnerLike)) ++ rest,
      conditions.filterNot(used.contains))
    logInfo(
      s"DimensionBridgeJoinReorder joined bridging dimensions before the large join: " +
        s"${items(f1).output.headOption} ${items(f2).output.headOption}")
    Some(Project(plan.output, ordered))
  }

  override def apply(plan: LogicalPlan): LogicalPlan = {
    val conf = session.sessionState.conf
    if (
      !new VeloxConfig(conf).dimensionBridgeEnabled ||
      !conf.getConfString("spark.gluten.enabled", "true").toBoolean || plan.isStreaming
    ) return plan
    var changed = false
    val result = plan.transformDown {
      case p if joinLike(p) && !p.isInstanceOf[Project] && !p.isInstanceOf[Filter] =>
        rewrite(p).map {
          r =>
            changed = true
            r
        }.getOrElse(p)
    }
    if (changed) RemoveNoopOperators(CollapseProject(ColumnPruning(result))) else plan
  }
}
