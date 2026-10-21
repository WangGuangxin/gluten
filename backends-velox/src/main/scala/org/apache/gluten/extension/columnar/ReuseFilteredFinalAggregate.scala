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
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, AttributeSet}
import org.apache.spark.sql.catalyst.expressions.aggregate.{Count, Final, Sum}
import org.apache.spark.sql.catalyst.plans.physical.HashPartitioning
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.{ExecSubqueryExpression, FilterExec, ProjectExec, SparkPlan}
import org.apache.spark.sql.execution.aggregate.HashAggregateExec
import org.apache.spark.sql.execution.exchange.{ENSURE_REQUIREMENTS, ShuffleExchangeExec, ShuffleExchangeLike}
import org.apache.spark.sql.types.DecimalType

import scala.collection.mutable

/**
 * Allow normal Spark exchange/stage reuse to cover a repeated filtered final aggregation, rather
 * than reusing only its partial-input shuffle and recomputing the final aggregation. This opt-in
 * rule materializes only grouping keys after the HAVING filter. It preserves the existing hash
 * partitioning and uses Spark's ordinary shuffle lifecycle and memory accounting. A weak filter can
 * add substantial shuffle traffic, so this is deliberately default disabled.
 */
case class ReuseFilteredFinalAggregate(session: SparkSession) extends Rule[SparkPlan] with Logging {
  // A bare grouping key and an identity Alias of it have identical values,
  // but different Project canonical forms. Give every projected attribute an
  // Alias with a uniform qualifier inside the exchange. The projection above
  // it restores the caller's exact attributes, IDs, names, qualifiers and metadata.
  private def materializedProject(plan: SparkPlan): SparkPlan = plan match {
    case project: ProjectExec =>
      val expressions = project.projectList.zip(project.output).map {
        case (alias: Alias, output) =>
          Alias(alias.child, output.name)(
            exprId = output.exprId,
            qualifier = Seq.empty,
            explicitMetadata = Some(output.metadata))
        case (attribute: Attribute, output) =>
          Alias(attribute, output.name)(
            exprId = output.exprId,
            qualifier = Seq.empty,
            explicitMetadata = Some(output.metadata))
        case (other, _) => other
      }
      val normalized = project.copy(projectList = expressions)
      normalized.copyTagsFrom(project)
      normalized
    case _ => plan
  }

  private def eligible(plan: SparkPlan): Option[HashPartitioning] = plan match {
    case p @ ProjectExec(projects, FilterExec(condition, agg: HashAggregateExec))
        if !agg.isStreaming && agg.groupingExpressions.nonEmpty &&
          agg.aggregateExpressions.nonEmpty && p.deterministic =>
      val groupReferences = AttributeSet(agg.groupingExpressions.flatMap(_.references))
      val measures = agg.outputSet -- groupReferences
      val exactFinal = agg.aggregateExpressions.forall {
        expression =>
          expression.mode == Final && (expression.aggregateFunction match {
            case _: Count => true
            case sum: Sum => sum.dataType.isInstanceOf[DecimalType]
            case _ => false
          })
      }
      val hasSubquery = p.find {
        node => node.expressions.exists(_.exists(_.isInstanceOf[ExecSubqueryExpression]))
      }.nonEmpty
      if (
        !exactFinal || hasSubquery || p.output.isEmpty ||
        !projects.forall(_.references.subsetOf(groupReferences)) ||
        (condition.references.intersect(measures)).isEmpty
      ) {
        None
      } else {
        p.outputPartitioning match {
          case hash: HashPartitioning
              if hash.numPartitions > 0 && hash.expressions.forall(
                _.references.subsetOf(p.outputSet)) =>
            Some(hash)
          case _ => None
        }
      }
    case _ => None
  }

  override def apply(plan: SparkPlan): SparkPlan = {
    if (
      !new VeloxConfig(session.sessionState.conf).reuseFilteredFinalAggregateEnabled ||
      !session.sessionState.conf.exchangeReuseEnabled
    ) {
      return plan
    }
    val counts = mutable.HashMap.empty[SparkPlan, Int]
    def count(node: SparkPlan, parentIsShuffle: Boolean): Unit = {
      if (!parentIsShuffle && eligible(node).nonEmpty) {
        val key = materializedProject(node).canonicalized
        counts.update(key, counts.getOrElse(key, 0) + 1)
      }
      node.children.foreach(count(_, node.isInstanceOf[ShuffleExchangeLike]))
    }
    count(plan, false)
    val repeated = counts.collect { case (key, n) if n >= 2 => key }.toSet
    if (repeated.isEmpty) {
      return plan
    }
    var inserted = 0
    def rewrite(node: SparkPlan, parentIsShuffle: Boolean): SparkPlan = {
      val partitioning = if (parentIsShuffle) None else eligible(node)
      val materialize =
        partitioning.nonEmpty && repeated.contains(materializedProject(node).canonicalized)
      val changed = node.withNewChildren(
        node.children.map(rewrite(_, node.isInstanceOf[ShuffleExchangeLike])))
      if (materialize) {
        // This origin is not an explicit redundant repartition which
        // EnsureRequirements may eliminate on the next AQE replan.
        val normalized = materializedProject(changed)
        val exchange = ShuffleExchangeExec(partitioning.get, normalized, ENSURE_REQUIREMENTS)
        node.logicalLink.foreach(exchange.setLogicalLink)
        val restore = ProjectExec(
          node.output.zip(exchange.output).map {
            case (wanted, actual) =>
              Alias(actual, wanted.name)(
                exprId = wanted.exprId,
                qualifier = wanted.qualifier,
                explicitMetadata = Some(wanted.metadata))
          },
          exchange
        )
        node.logicalLink.foreach(restore.setLogicalLink)
        require(restore.output == node.output, "Shared aggregate changed output attributes")
        inserted += 1
        restore
      } else {
        changed
      }
    }
    val result = rewrite(plan, false)
    logInfo(s"ReuseFilteredFinalAggregate inserted $inserted exchanges over repeated key-only HAVING results")
    result
  }
}
