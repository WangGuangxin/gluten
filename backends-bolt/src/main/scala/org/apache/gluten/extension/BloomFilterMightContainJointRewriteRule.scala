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

import org.apache.gluten.config.GlutenConfig
import org.apache.gluten.expression.BoltBloomFilterMightContain
import org.apache.gluten.expression.aggregate.BoltBloomFilterAggregate

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.{BinaryExpression, BloomFilterMightContain, Expression}
import org.apache.spark.sql.catalyst.expressions.aggregate.{BloomFilterAggregate, TypedImperativeAggregate}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.util.sketch.BloomFilter

case class BloomFilterMightContainJointRewriteRule(spark: SparkSession) extends Rule[SparkPlan] {
  override def apply(plan: SparkPlan): SparkPlan = {
    if (!GlutenConfig.get.enableNativeBloomFilter) {
      return plan
    }
    val out = plan.transformWithSubqueries {
      case p =>
        applyForNode(p)
    }
    out
  }

  private def applyForNode(p: SparkPlan) = {
    p.transformExpressions {
      case e =>
        replaceMightContain(
          replaceBloomFilterAggregate(e, BoltBloomFilterAggregate.apply),
          BoltBloomFilterMightContain.apply)
    }
  }

  private def replaceBloomFilterAggregate(
      expr: Expression,
      bloomFilterAggReplacer: (
          Expression,
          Expression,
          Expression,
          Int,
          Int) => TypedImperativeAggregate[BloomFilter]): Expression = expr match {
    case BloomFilterAggregate(
          child,
          estimatedNumItemsExpression,
          numBitsExpression,
          mutableAggBufferOffset,
          inputAggBufferOffset) =>
      bloomFilterAggReplacer(
        child,
        estimatedNumItemsExpression,
        numBitsExpression,
        mutableAggBufferOffset,
        inputAggBufferOffset)
    case other => other
  }

  private def replaceMightContain(
      expr: Expression,
      mightContainReplacer: (Expression, Expression) => BinaryExpression): Expression = expr match {
    case BloomFilterMightContain(bloomFilterExpression, valueExpression) =>
      mightContainReplacer(bloomFilterExpression, valueExpression)
    case other => other
  }
}
