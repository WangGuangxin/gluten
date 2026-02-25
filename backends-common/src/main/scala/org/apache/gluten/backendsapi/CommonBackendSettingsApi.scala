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
package org.apache.gluten.backendsapi

import org.apache.gluten.config.GlutenConfig
import org.apache.gluten.exception.GlutenNotSupportException
import org.apache.gluten.expression.WindowFunctionsBuilder
import org.apache.gluten.extension.columnar.transition.Convention
import org.apache.gluten.sql.shims.SparkShimLoader

import org.apache.spark.sql.catalyst.expressions.{Alias, CumeDist, DenseRank, Descending, Expression, Lag, Lead, NamedExpression, NthValue, NTile, PercentRank, RangeFrame, Rank, RowNumber, SortOrder, SpecialFrameBoundary, SpecifiedWindowFrame}
import org.apache.spark.sql.catalyst.expressions.aggregate.{AggregateExpression, ApproximatePercentile, HyperLogLogPlusPlus, Percentile}
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.execution.command.CreateDataSourceTableAsSelectCommand
import org.apache.spark.sql.execution.datasources.InsertIntoHadoopFsRelationCommand
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._

import scala.util.control.Breaks.breakable

/**
 * Common backend settings shared by multiple native backends.
 *
 * Backend specific modules (e.g. Velox, Bolt) should extend this trait and only override the truly
 * backend-specific parts such as configuration switches or JNI integrations.
 */
trait CommonBackendSettingsApi extends BackendSettingsApi {

  override def primaryBatchType: Convention.BatchType

  override def supportNativeWrite(fields: Array[StructField]): Boolean = {
    def isNotSupported(dataType: DataType): Boolean = dataType match {
      case _: StructType | _: ArrayType | _: MapType => true
      case _ => false
    }
    !fields.exists(field => isNotSupported(field.dataType))
  }

  override def supportExpandExec(): Boolean = true

  override def supportSortExec(): Boolean = true

  override def supportSortMergeJoinExec(): Boolean = {
    GlutenConfig.get.enableColumnarSortMergeJoin
  }

  override def supportWindowGroupLimitExec(rankLikeFunction: Expression): Boolean = {
    rankLikeFunction match {
      case _: RowNumber => true
      case _ => false
    }
  }

  override def supportWindowExec(windowFunctions: Seq[NamedExpression]): Boolean = {
    var allSupported = true
    breakable {
      windowFunctions.foreach {
        func =>
          val windowExpression = func match {
            case alias: Alias =>
              val we = WindowFunctionsBuilder.extractWindowExpression(alias.child)
              if (we == null) {
                throw new GlutenNotSupportException(s"$func is not supported.")
              }
              we
            case _ => throw new GlutenNotSupportException(s"$func is not supported.")
          }

          def checkLimitations(swf: SpecifiedWindowFrame, orderSpec: Seq[SortOrder]): Unit = {
            def doCheck(bound: Expression): Unit = {
              bound match {
                case _: SpecialFrameBoundary =>
                case e if e.foldable =>
                  orderSpec.foreach {
                    order =>
                      order.direction match {
                        case Descending =>
                          throw new GlutenNotSupportException(
                            "DESC order is not supported when literal bound type is used!")
                        case _ =>
                      }
                  }
                  orderSpec.foreach {
                    order =>
                      order.dataType match {
                        case ByteType | ShortType | IntegerType | LongType | DateType =>
                        case _ =>
                          throw new GlutenNotSupportException(
                            "Only integral type & date type are supported for sort key when " +
                              "literal bound type is used!")
                      }
                  }
                case _ =>
              }
            }
            doCheck(swf.upper)
            doCheck(swf.lower)
          }

          windowExpression.windowSpec.frameSpecification match {
            case swf: SpecifiedWindowFrame =>
              swf.frameType match {
                case RangeFrame =>
                  checkLimitations(swf, windowExpression.windowSpec.orderSpec)
                case _ =>
              }
            case _ =>
          }
          windowExpression.windowFunction match {
            case _: RowNumber | _: Rank | _: CumeDist | _: DenseRank | _: PercentRank | _: NTile =>
            case nv: NthValue if !nv.input.foldable =>
            case l: Lag if !l.input.foldable =>
            case l: Lead if !l.input.foldable =>
            case aggrExpr: AggregateExpression
                if !aggrExpr.aggregateFunction.isInstanceOf[ApproximatePercentile] &&
                  !aggrExpr.aggregateFunction.isInstanceOf[Percentile] &&
                  !aggrExpr.aggregateFunction.isInstanceOf[HyperLogLogPlusPlus] =>
            case _ =>
              allSupported = false
          }
      }
    }
    allSupported
  }

  override def supportColumnarShuffleExec(): Boolean = {
    val conf = GlutenConfig.get
    conf.enableColumnarShuffle &&
    (conf.isUseGlutenShuffleManager || conf.shuffleManagerSupportsColumnarShuffle)
  }

  override def enableJoinKeysRewrite(): Boolean = false

  override def supportHashBuildJoinTypeOnLeft: JoinType => Boolean = {
    t =>
      if (super.supportHashBuildJoinTypeOnLeft(t)) {
        true
      } else {
        t match {
          case LeftOuter => true
          case _ => false
        }
      }
  }

  override def supportHashBuildJoinTypeOnRight: JoinType => Boolean = {
    t =>
      if (super.supportHashBuildJoinTypeOnRight(t)) {
        true
      } else {
        t match {
          case RightOuter => true
          case _ => false
        }
      }
  }

  override def fallbackAggregateWithEmptyOutputChild(): Boolean = true

  override def recreateJoinExecOnFallback(): Boolean = true

  override def rescaleDecimalArithmetic: Boolean = true

  override def insertPostProjectForGenerate(): Boolean = true

  override def skipNativeCtas(ctas: CreateDataSourceTableAsSelectCommand): Boolean = true

  override def skipNativeInsertInto(insertInto: InsertIntoHadoopFsRelationCommand): Boolean = {
    insertInto.bucketSpec.nonEmpty
  }

  override def alwaysFailOnMapExpression(): Boolean = true

  override def requiredChildOrderingForWindowGroupLimit(): Boolean = false

  override def staticPartitionWriteOnly(): Boolean = true

  override def enableNativeWriteFiles(): Boolean = {
    GlutenConfig.get.enableNativeWriter.getOrElse(
      SparkShimLoader.getSparkShims.enableNativeWriteFilesByDefault())
  }

  override def enableNativeArrowReadFiles(): Boolean = {
    GlutenConfig.get.enableNativeArrowReader
  }

  override def shouldRewriteCount(): Boolean = {
    // This backend does not support count when it has more than one child, so rewrite is required.
    true
  }

  override def supportCartesianProductExec(): Boolean = true

  override def supportSampleExec(): Boolean = true

  override def supportColumnarArrowUdf(): Boolean = true

  override def needPreComputeRangeFrameBoundary(): Boolean = true

  override def broadcastNestedLoopJoinSupportsFullOuterJoin(): Boolean = true

  override def supportIcebergEqualityDeleteRead(): Boolean = false

  override def reorderColumnsForPartitionWrite(): Boolean = true

  override def supportAppendDataExec(): Boolean = enableEnhancedFeatures()

  override def supportReplaceDataExec(): Boolean = enableEnhancedFeatures()

  override def supportOverwriteByExpression(): Boolean = enableEnhancedFeatures()

  override def supportOverwritePartitionsDynamic(): Boolean = enableEnhancedFeatures()
}
