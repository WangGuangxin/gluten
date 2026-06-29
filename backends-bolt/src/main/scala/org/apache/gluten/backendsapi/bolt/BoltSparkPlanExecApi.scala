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
package org.apache.gluten.backendsapi.bolt

import org.apache.gluten.backendsapi.velox.VeloxSparkPlanExecApi
import org.apache.gluten.exception.{GlutenExceptionUtil, GlutenNotSupportException}
import org.apache.gluten.expression.{ExpressionNames, ExpressionTransformer, GenericExpressionTransformer, MapFromArraysRestrictions}

import org.apache.spark.sql.catalyst.expressions.{Attribute, Expression, MapFromArrays, Sequence}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.TimestampType

/**
 * Bolt is a thin, Velox-compatible backend. [[BoltSparkPlanExecApi]] reuses everything from
 * [[VeloxSparkPlanExecApi]] and only overrides the handful of expression transformers whose Bolt
 * native semantics genuinely differ from Velox.
 *
 * NOTE: `genMapFromEntriesTransformer` is intentionally NOT overridden here. Velox already maps it
 * to a plain [[GenericExpressionTransformer]], which is exactly the Bolt behavior from PR #11261,
 * so the inherited implementation is reused verbatim.
 */
class BoltSparkPlanExecApi extends VeloxSparkPlanExecApi {

  /**
   * Transform sequence to Substrait. Bolt's native `sequence` does not support timestamp inputs, so
   * fall back to vanilla Spark in that case; everything else maps directly.
   */
  override def genSequenceTransformer(
      substraitExprName: String,
      children: Seq[ExpressionTransformer],
      expr: Sequence): ExpressionTransformer = {
    if (expr.children.exists(_.dataType.isInstanceOf[TimestampType])) {
      throw new GlutenNotSupportException(
        "sequence with timestamp input is not supported in bolt backend")
    }
    GenericExpressionTransformer(substraitExprName, children, expr)
  }

  /**
   * Define backend-specific expression converter. Bolt's native MapFromArrays implementation only
   * matches Spark's `LAST_WIN` map-key dedup policy. When `spark.sql.mapKeyDedupPolicy` is set to a
   * non `LAST_WIN` value (e.g. `FIRST_WIN`), fall back to vanilla Spark to keep semantics
   * consistent.
   */
  override def extraExpressionConverter(
      substraitExprName: String,
      expr: Expression,
      attributeSeq: Seq[Attribute]): Option[ExpressionTransformer] = {
    expr match {
      case _: MapFromArrays =>
        val policy = SQLConf.get.getConf(SQLConf.MAP_KEY_DEDUP_POLICY)
        if (policy == MapFromArraysRestrictions.FIRST_WIN_POLICY_NAME) {
          GlutenExceptionUtil.throwsNotFullySupported(
            ExpressionNames.MAP_FROM_ARRAYS,
            MapFromArraysRestrictions.NOT_SUPPORT_FIRST_WIN_DEDUP_POLICY)
        }
        None
      case _ => None
    }
  }
}
