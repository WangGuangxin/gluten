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
package org.apache.gluten.utils

import org.apache.spark.sql.catalyst.expressions.aggregate.{AggregateFunction, First, Last}

/**
 * Bolt-specific intermediate-data helpers. Bolt reuses [[VeloxIntermediateData]] for everything
 * except the row-constructor function name for first/last.
 *
 * For first/last the intermediate is (value, valueSet). Bolt guarantees the struct is always
 * non-null and valueSet is always non-null (false or true), so `row_constructor` is used instead of
 * `row_constructor_with_null`. This prevents a single empty partition's partial from overwriting a
 * real value with NULL via the updateNull path.
 *
 * TODO(bolt): wire this into the aggregate transformer. Today
 * `backends-velox/.../execution/HashAggregateExecTransformer.scala` calls
 * `VeloxIntermediateData.getRowConstructFuncName(aggFunc)` directly (hard-coded), which is shared
 * Velox code reused by Bolt. To take effect at runtime the call site must dispatch to
 * [[BoltIntermediateData.getRowConstructFuncName]] for the Bolt backend (e.g. via a
 * SparkPlanExecApi hook), without changing Velox behavior. Left as a TODO because that wiring
 * touches shared aggregate code and is verified separately against the native engine.
 */
object BoltIntermediateData {

  /**
   * Same contract as [[VeloxIntermediateData.getRowConstructFuncName]], but uses `row_constructor`
   * (instead of `row_constructor_with_null`) for first/last. Delegates to Velox for every other
   * aggregate function so the two stay in sync.
   */
  def getRowConstructFuncName(aggFunc: AggregateFunction): String = aggFunc match {
    case _: First | _: Last =>
      "row_constructor"
    case _ =>
      VeloxIntermediateData.getRowConstructFuncName(aggFunc)
  }
}
