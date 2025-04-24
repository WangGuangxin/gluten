package org.apache.gluten.execution

import org.apache.spark.sql.catalyst.expressions.{Attribute, NamedExpression, SortOrder}
import org.apache.spark.sql.catalyst.plans.physical.{Partitioning, SinglePartition}
import org.apache.spark.sql.catalyst.util.truncatedString
import org.apache.spark.sql.execution.{SparkPlan, UnaryExecNode}

abstract class TakeOrderedAndProjectExecTransformerBase(
    limit: Long,
    sortOrder: Seq[SortOrder],
    projectList: Seq[NamedExpression],
    child: SparkPlan,
    offset: Int = 0)
  extends UnaryExecNode
  with UnaryTransformSupport {

  override def output: Seq[Attribute] = {
    projectList.map(_.toAttribute)
  }
  override def outputPartitioning: Partitioning = SinglePartition
  override def outputOrdering: Seq[SortOrder] = sortOrder

  override def simpleString(maxFields: Int): String = {
    val orderByString = truncatedString(sortOrder, "[", ",", "]", maxFields)
    val outputString = truncatedString(output, "[", ",", "]", maxFields)

    s"TakeOrderedAndProjectExecTransformer (limit=$limit, " +
      s"orderBy=$orderByString, output=$outputString)"
  }
}

