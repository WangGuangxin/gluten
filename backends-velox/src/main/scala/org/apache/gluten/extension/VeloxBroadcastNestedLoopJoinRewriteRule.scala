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

import org.apache.gluten.config.VeloxConfig

import org.apache.spark.sql.catalyst.expressions.{Alias, And, Attribute, AttributeReference, Expression, Literal, Not, PredicateHelper}
import org.apache.spark.sql.catalyst.optimizer.{BuildLeft, BuildRight}
import org.apache.spark.sql.catalyst.plans.{ExistenceJoin, FullOuter, Inner, LeftAnti, LeftOuter, LeftSemi, RightOuter}
import org.apache.spark.sql.catalyst.plans.logical.Join
import org.apache.spark.sql.catalyst.plans.physical.IdentityBroadcastMode
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.{ColumnarInputAdapter, FilterExec, InputIteratorTransformer, LocalLimitExec, ProjectExec, SparkPlan, UnionExec}
import org.apache.spark.sql.execution.adaptive.BroadcastQueryStageExec
import org.apache.spark.sql.execution.exchange.{BroadcastExchangeExec, BroadcastExchangeLike, ReusedExchangeExec}
import org.apache.spark.sql.execution.joins.{BaseJoinExec, BroadcastNestedLoopJoinExec}
import org.apache.spark.sql.types.BooleanType

/**
 * Rewrites `BroadcastNestedLoopJoinExec` join shapes that Velox cannot run into nested loop joins
 * it supports natively. Velox runs inner, left outer and existence joins with the broadcast side as
 * the build side. Every task sees only one partition of the streamed side, so a task cannot tell
 * whether a broadcast row has a match in another partition.
 *
 * A join that must keep unmatched broadcast rows, i.e. a full outer join, a left outer join that
 * builds the left side or a right outer join that builds the right side, becomes a union of
 *   1. a nested loop join with the same build side that produces the matches, plus unmatched
 *      streamed rows for a full outer join
 *   2. an existence join that streams the broadcast side and keeps its unmatched rows.
 *
 * When no conjunct of the join condition references both sides, whether a broadcast row matches
 * depends only on that row and on whether any streamed row passes the streamed side conjuncts. The
 * existence join then probes a broadcast of at most one such row per streamed partition. Otherwise
 * it probes a broadcast of the whole streamed side, which is done only for a full outer join with
 * both sides below `broadcastNLJ.fullOuterRewriteThreshold`.
 *
 * A left semi or left anti join that builds the right side becomes an existence join followed by a
 * filter and a projection.
 */
case class VeloxBroadcastNestedLoopJoinRewriteRule() extends Rule[SparkPlan] with PredicateHelper {
  override def apply(plan: SparkPlan): SparkPlan = {
    val conf = VeloxConfig.get
    val fullOuterThreshold = conf.broadcastNestedLoopJoinFullOuterRewriteThreshold
    val joinTypeRewriteEnabled = conf.broadcastNestedLoopJoinJoinTypeRewriteEnabled
    if (fullOuterThreshold < 0 && !joinTypeRewriteEnabled) {
      plan
    } else {
      plan.transformUp {
        case bnlj: BroadcastNestedLoopJoinExec
            if bnlj.joinType == FullOuter && fullOuterThreshold >= 0 && canDuplicateSides(bnlj) =>
          rewriteFullOuter(bnlj, fullOuterThreshold).getOrElse(bnlj)
        case bnlj: BroadcastNestedLoopJoinExec
            if joinTypeRewriteEnabled && keepsUnmatchedBuildRowsOnly(bnlj) &&
              canDuplicateSides(bnlj) && !isCorrelated(bnlj) =>
          rewriteBuildSideOuter(bnlj)
        case bnlj: BroadcastNestedLoopJoinExec
            if joinTypeRewriteEnabled && bnlj.buildSide == BuildRight &&
              (bnlj.joinType == LeftSemi || bnlj.joinType == LeftAnti) =>
          rewriteSemiOrAnti(bnlj)
      }
    }
  }

  /** A left outer join that builds the left side or a right outer join that builds the right. */
  private def keepsUnmatchedBuildRowsOnly(bnlj: BroadcastNestedLoopJoinExec): Boolean =
    (bnlj.joinType, bnlj.buildSide) match {
      case (LeftOuter, BuildLeft) | (RightOuter, BuildRight) => true
      case _ => false
    }

  /**
   * The rewrites of outer joins evaluate both sides and the join condition twice, so they must
   * produce the same rows each time, and the broadcast side must be usable as a streamed input.
   */
  private def canDuplicateSides(bnlj: BroadcastNestedLoopJoinExec): Boolean =
    childDeterministic(bnlj.left) &&
      childDeterministic(bnlj.right) &&
      bnlj.condition.forall(_.deterministic) &&
      broadcastSideRelocatable(bnlj)

  // Query stages are leaf nodes, so inspect the encapsulated broadcast plan as well.
  private def childDeterministic(plan: SparkPlan): Boolean =
    plan.deterministic && (plan match {
      case stage: BroadcastQueryStageExec => childDeterministic(stage.plan)
      case _ => true
    })

  private def buildPlan(bnlj: BroadcastNestedLoopJoinExec): SparkPlan = bnlj.buildSide match {
    case BuildLeft => bnlj.left
    case BuildRight => bnlj.right
  }

  private def streamedPlan(bnlj: BroadcastNestedLoopJoinExec): SparkPlan = bnlj.buildSide match {
    case BuildLeft => bnlj.right
    case BuildRight => bnlj.left
  }

  /**
   * Whether a conjunct of the join condition references both sides. If none does, the condition is
   * a conjunction of build side, streamed side and constant predicates.
   */
  private def isCorrelated(bnlj: BroadcastNestedLoopJoinExec): Boolean =
    bnlj.condition.exists {
      cond =>
        splitConjunctivePredicates(cond).exists {
          pred =>
            pred.references.exists(bnlj.left.outputSet.contains) &&
            pred.references.exists(bnlj.right.outputSet.contains)
        }
    }

  /**
   * The rewrite reuses the original broadcast side in two roles at once: it stays the build
   * (broadcast) side of the join producing matches, while [[buildUnmatchedBroadcastSide]] calls
   * [[unwrapBroadcast]] on it and consumes the unwrapped subtree as a normal STREAMED input of the
   * existence join. That is only safe when the broadcast side can be cleanly re-materialized as a
   * partitioned plan. Reject the rewrite otherwise, e.g. for the MERGE cardinality-check join (`ON
   * t.pk > s.pk` with an `autoBroadcastJoinThreshold = -1` broadcast of a reused `Union` source):
   * there the broadcast side does not unwrap to a plain partitioned subtree, so after the rewrite a
   * `ColumnarBroadcastExchangeExec` ends up in the existence join's streamed slot and is executed
   * via `ColumnarInputAdapter.doExecuteColumnar -> executeColumnar()`, which the broadcast exchange
   * does not support, crashing with `[INTERNAL_ERROR] ... has column support mismatch`.
   *
   * A broadcast side is considered relocatable only when:
   *   - it is an exclusively-owned broadcast, i.e. NOT a [[ReusedExchangeExec]] (a reused/shared
   *     exchange must not be turned into a streamed input); and
   *   - every broadcast inside its unwrapped payload is the build side of a nested join. Such a
   *     broadcast is consumed through `executeBroadcast` wherever the subtree runs. A broadcast in
   *     any other position, including the payload root, would otherwise leak into the streamed
   *     position of the existence join.
   */
  private def broadcastSideRelocatable(bnlj: BroadcastNestedLoopJoinExec): Boolean =
    isCleanRelocatableBroadcast(buildPlan(bnlj))

  private def isCleanRelocatableBroadcast(plan: SparkPlan): Boolean = plan match {
    case stage: BroadcastQueryStageExec => isCleanRelocatableBroadcast(stage.plan)
    case _: ReusedExchangeExec => false
    case exchange: BroadcastExchangeLike => broadcastsOnlyFeedJoins(exchange.child)
    case _ => false
  }

  private def isBroadcastLike(plan: SparkPlan): Boolean = plan match {
    case _: BroadcastExchangeLike | _: BroadcastQueryStageExec | _: ReusedExchangeExec => true
    case _ => false
  }

  // A columnar join inside a whole stage transformer reads its broadcast build side through these.
  private def joinInput(plan: SparkPlan): SparkPlan = plan match {
    case input: InputIteratorTransformer => joinInput(input.child)
    case adapter: ColumnarInputAdapter => joinInput(adapter.child)
    case other => other
  }

  private def broadcastsOnlyFeedJoins(plan: SparkPlan): Boolean = plan match {
    case join: BaseJoinExec =>
      join.children.forall {
        child => isBroadcastLike(joinInput(child)) || broadcastsOnlyFeedJoins(child)
      }
    case other => !isBroadcastLike(other) && other.children.forall(broadcastsOnlyFeedJoins)
  }

  private def shouldBroadcastStreamedSide(
      bnlj: BroadcastNestedLoopJoinExec,
      threshold: Long): Boolean = {
    bnlj.logicalLink.collect {
      case join: Join =>
        val leftSize = join.left.stats.sizeInBytes
        val rightSize = join.right.stats.sizeInBytes
        leftSize >= 0 && rightSize >= 0 && leftSize <= threshold && rightSize <= threshold
    }.getOrElse(false)
  }

  /**
   * The broadcast relation the existence join probes to find unmatched rows of the original build
   * side, or None if the rewrite does not apply.
   */
  private def unmatchedProbe(
      bnlj: BroadcastNestedLoopJoinExec,
      threshold: Long): Option[SparkPlan] =
    if (!isCorrelated(bnlj)) {
      Some(firstQualifyingStreamedRow(bnlj))
    } else if (shouldBroadcastStreamedSide(bnlj, threshold)) {
      Some(ensureBroadcast(unwrapBroadcast(streamedPlan(bnlj))))
    } else {
      None
    }

  /**
   * Broadcasts at most one row per streamed partition that passes the streamed side conjuncts. With
   * an uncorrelated condition, a build row matches some streamed row exactly when it matches any
   * such row, because the remaining conjuncts do not depend on the streamed row.
   */
  private def firstQualifyingStreamedRow(bnlj: BroadcastNestedLoopJoinExec): SparkPlan = {
    val streamed = unwrapBroadcast(streamedPlan(bnlj))
    val streamedPredicates = bnlj.condition.toSeq.flatMap(splitConjunctivePredicates).filter {
      pred => pred.references.nonEmpty && pred.references.subsetOf(streamed.outputSet)
    }
    val filtered = streamedPredicates.reduceOption(And) match {
      case Some(pred) => FilterExec(pred, streamed)
      case None => streamed
    }
    ensureBroadcast(LocalLimitExec(1, filtered))
  }

  private def rewriteFullOuter(
      bnlj: BroadcastNestedLoopJoinExec,
      threshold: Long): Option[SparkPlan] = {
    unmatchedProbe(bnlj, threshold).map {
      probe =>
        val matchesAndStreamedUnmatched = bnlj.buildSide match {
          case BuildRight =>
            projectToOutput(
              BroadcastNestedLoopJoinExec(
                bnlj.left,
                bnlj.right,
                BuildRight,
                LeftOuter,
                bnlj.condition),
              bnlj.output)
          case BuildLeft =>
            projectToOutput(
              BroadcastNestedLoopJoinExec(
                bnlj.right,
                bnlj.left,
                BuildRight,
                LeftOuter,
                bnlj.condition),
              bnlj.output)
        }
        unionToOutput(matchesAndStreamedUnmatched, unmatchedBuildRows(bnlj, probe), bnlj.output)
    }
  }

  private def rewriteBuildSideOuter(bnlj: BroadcastNestedLoopJoinExec): SparkPlan = {
    val matches = projectToOutput(
      BroadcastNestedLoopJoinExec(bnlj.left, bnlj.right, bnlj.buildSide, Inner, bnlj.condition),
      bnlj.output)
    unionToOutput(matches, unmatchedBuildRows(bnlj, firstQualifyingStreamedRow(bnlj)), bnlj.output)
  }

  private def rewriteSemiOrAnti(bnlj: BroadcastNestedLoopJoinExec): SparkPlan = {
    val existsAttr = AttributeReference("__gluten_bnlj_exists", BooleanType, nullable = false)()
    val existenceJoin = BroadcastNestedLoopJoinExec(
      bnlj.left,
      bnlj.right,
      BuildRight,
      ExistenceJoin(existsAttr),
      bnlj.condition)
    val predicate = if (bnlj.joinType == LeftSemi) existsAttr else Not(existsAttr)
    ProjectExec(bnlj.left.output, FilterExec(predicate, existenceJoin))
  }

  private def unmatchedBuildRows(bnlj: BroadcastNestedLoopJoinExec, probe: SparkPlan): SparkPlan =
    bnlj.buildSide match {
      case BuildRight =>
        buildUnmatchedBroadcastSide(
          unmatchedSide = bnlj.right,
          otherSideOutputSize = bnlj.left.output.size,
          probe = probe,
          unmatchedSideIsLeft = false,
          condition = bnlj.condition,
          output = bnlj.output
        )
      case BuildLeft =>
        buildUnmatchedBroadcastSide(
          unmatchedSide = bnlj.left,
          otherSideOutputSize = bnlj.right.output.size,
          probe = probe,
          unmatchedSideIsLeft = true,
          condition = bnlj.condition,
          output = bnlj.output)
    }

  private def buildUnmatchedBroadcastSide(
      unmatchedSide: SparkPlan,
      otherSideOutputSize: Int,
      probe: SparkPlan,
      unmatchedSideIsLeft: Boolean,
      condition: Option[Expression],
      output: Seq[Attribute]): SparkPlan = {
    val unmatchedSideBase = unwrapBroadcast(unmatchedSide)
    val existsAttr =
      AttributeReference("__gluten_bnlj_exists", BooleanType, nullable = false)()
    val unmatchedJoin = BroadcastNestedLoopJoinExec(
      unmatchedSideBase,
      probe,
      BuildRight,
      ExistenceJoin(existsAttr),
      condition)
    val unmatchedOnly = FilterExec(Not(existsAttr), unmatchedJoin)
    val projected = if (unmatchedSideIsLeft) {
      output.zipWithIndex.map {
        case (targetAttr, idx) if idx < unmatchedSideBase.output.size =>
          aliasTo(unmatchedSideBase.output(idx), targetAttr)
        case (targetAttr, _) =>
          nullAliasFor(targetAttr)
      }
    } else {
      output.zipWithIndex.map {
        case (targetAttr, idx) if idx < otherSideOutputSize =>
          nullAliasFor(targetAttr)
        case (targetAttr, idx) =>
          aliasTo(unmatchedSideBase.output(idx - otherSideOutputSize), targetAttr)
      }
    }
    ProjectExec(projected, unmatchedOnly)
  }

  private def unionToOutput(
      matches: SparkPlan,
      unmatched: SparkPlan,
      output: Seq[Attribute]): SparkPlan = {
    val union = UnionExec(Seq(matches, unmatched))
    ProjectExec(
      union.output.zip(output).map {
        case (childAttr, targetAttr) => aliasTo(childAttr, targetAttr)
      },
      union)
  }

  private def unwrapBroadcast(plan: SparkPlan): SparkPlan = plan match {
    case stage: BroadcastQueryStageExec => unwrapBroadcast(stage.plan)
    case reused: ReusedExchangeExec => unwrapBroadcast(reused.child)
    case exchange: BroadcastExchangeLike => exchange.child
    case other => other
  }

  private def ensureBroadcast(plan: SparkPlan): SparkPlan = plan match {
    case exchange: BroadcastExchangeLike => exchange
    case other => BroadcastExchangeExec(IdentityBroadcastMode, other)
  }

  private def projectToOutput(child: SparkPlan, output: Seq[Attribute]): ProjectExec = {
    val sourceByExprId = child.output.map(attr => attr.exprId -> attr).toMap
    ProjectExec(
      output.map(targetAttr => aliasTo(sourceByExprId(targetAttr.exprId), targetAttr)),
      child)
  }

  private def aliasTo(childAttr: Expression, targetAttr: Attribute): Alias = {
    Alias(childAttr, targetAttr.name)(
      exprId = targetAttr.exprId,
      qualifier = targetAttr.qualifier,
      explicitMetadata = Some(targetAttr.metadata))
  }

  private def nullAliasFor(targetAttr: Attribute): Alias =
    aliasTo(Literal.create(null, targetAttr.dataType), targetAttr)
}
