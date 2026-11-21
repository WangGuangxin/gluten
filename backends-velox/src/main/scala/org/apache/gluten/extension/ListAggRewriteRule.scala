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
import org.apache.gluten.expression.ExpressionMappings
import org.apache.gluten.expression.aggregate.VeloxCollectList

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.{ArrayJoin, EqualTo, Expression, If, Literal, Size}
import org.apache.spark.sql.catalyst.expressions.aggregate.{AggregateExpression, CollectList}
import org.apache.spark.sql.catalyst.plans.logical.{Aggregate, LogicalPlan}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.trees.TreePattern.{AGGREGATE, AGGREGATE_EXPRESSION}
import org.apache.spark.sql.types.StringType
import org.apache.spark.unsafe.types.UTF8String

/**
 * Rewrites Spark's `listagg` / `string_agg` aggregate (`ListAgg`, Spark 4.0+) without a `WITHIN
 * GROUP` ordering into `collect_list` followed by `array_join`, so the aggregation can be offloaded
 * to Velox:
 *
 * {{{
 *   string_agg([DISTINCT] x, d) [FILTER (WHERE f)]
 *     => IF(size(c) = 0, NULL, array_join(c, coalesce(d, '')))
 *        where c = collect_list([DISTINCT] x) [FILTER (WHERE f)]
 * }}}
 *
 * This keeps ListAgg's semantics: null inputs are skipped, a group without non-null input yields
 * NULL, and a NULL delimiter means an empty delimiter. Like ListAgg without ordering, the order of
 * the concatenated values is not deterministic.
 *
 * ListAgg with ordering, on binary input, or on non-default collations is left unchanged.
 */
case class ListAggRewriteRule(spark: SparkSession) extends Rule[LogicalPlan] {
  import ListAggRewriteRule._

  override def apply(plan: LogicalPlan): LogicalPlan = {
    if (!VeloxConfig.get.enableRewriteListAgg || !hasVeloxCollectList) {
      return plan
    }
    plan.transformUpWithPruning(_.containsPattern(AGGREGATE)) {
      case agg: Aggregate =>
        agg.transformExpressionsWithPruning(_.containsPattern(AGGREGATE_EXPRESSION)) {
          case RewritableListAgg(rewritten) => rewritten
        }
    }
  }
}

object ListAggRewriteRule {
  private val ListAggClassName = "org.apache.spark.sql.catalyst.expressions.aggregate.ListAgg"

  private def hasVeloxCollectList: Boolean =
    ExpressionMappings.expressionsMap.contains(classOf[VeloxCollectList])

  private object RewritableListAgg {
    def unapply(expr: Expression): Option[Expression] = expr match {
      case aggExpr: AggregateExpression
          if aggExpr.aggregateFunction.getClass.getName == ListAggClassName =>
        rewrite(aggExpr)
      case _ => None
    }
  }

  private def rewrite(aggExpr: AggregateExpression): Option[Expression] = {
    val listAgg = aggExpr.aggregateFunction
    for {
      child <- invoke[Expression](listAgg, "child")
      delimiter <- invoke[Expression](listAgg, "delimiter")
      orderExpressions <- invoke[Seq[_]](listAgg, "orderExpressions")
      if orderExpressions.isEmpty && child.dataType == StringType && delimiter.foldable
      delimiterValue <- delimiter.eval() match {
        case null => Some(UTF8String.EMPTY_UTF8)
        case s: UTF8String => Some(s)
        case _ => None
      }
    } yield {
      // The same AggregateExpression instance is referenced twice so that Spark plans a single
      // collect_list aggregate for it.
      val collected = aggExpr.copy(aggregateFunction = new CollectList(child))
      If(
        EqualTo(Size(collected), Literal(0)),
        Literal.create(null, StringType),
        ArrayJoin(collected, Literal.create(delimiterValue, StringType), None))
    }
  }

  /** Reads a field of ListAgg, or None if this Spark version's ListAgg doesn't have it. */
  private def invoke[T](obj: AnyRef, method: String): Option[T] =
    try {
      Some(obj.getClass.getMethod(method).invoke(obj).asInstanceOf[T])
    } catch {
      case _: NoSuchMethodException => None
    }
}
