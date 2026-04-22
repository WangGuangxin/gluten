package org.apache.gluten.execution

import org.apache.paimon.data.InternalRow
import org.apache.paimon.io.DataFileMeta
import org.apache.paimon.spark.PaimonScan
import org.apache.paimon.table.FileStoreTable
import org.apache.paimon.table.source.{ChainDataSplit, DataSplit}
import org.apache.paimon.types.{DateType, RowType}
import org.apache.paimon.utils.InternalRowPartitionComputer
import org.apache.spark.sql.catalyst.util.DateFormatter

import java.util
import scala.collection.JavaConverters.asScalaBufferConverter

class PaimonSparkShimImpl extends PaimonSparkShim {

  override def isChainSplit(split: DataSplit): Boolean = {
    split.isInstanceOf[ChainDataSplit]
  }

  override def getSplitPartition(split: DataSplit): InternalRow = {
    split.partition()
  }

  override def getBucketPath(split: DataSplit, file: DataFileMeta): String = {
    val isChainDataSplit: Boolean = isChainSplit(split)
    val bucketPath = if (isChainDataSplit) {
      split
        .asInstanceOf[ChainDataSplit]
        .fileBucketPathMapping()
        .get(file.fileName())
    } else {
      split.bucketPath()
    }
    if (isChainDataSplit && bucketPath == null) {
      throw new RuntimeException(s"Bucket path is null for file ${file.fileName()}")
    }
    bucketPath
  }

  override def getInternalPartitionComputer(paimonScan: PaimonScan): InternalRowPartitionComputer = {
    val table = paimonScan.table.asInstanceOf[FileStoreTable]
    PaimonPartitionComputer(
      paimonScan,
      table.schema().logicalPartitionType(),
      table.partitionKeys.asScala.toArray
    )
  }
}

case class PaimonPartitionComputer(paimonScan: PaimonScan, paimonRowType: RowType, paimonPartitionKeys: Array[String])
  extends InternalRowPartitionComputer(
    paimonScan.coreOptions.partitionDefaultName(),
    paimonRowType,
    paimonPartitionKeys,
    false) {

  override def generatePartValues(
                                   in: org.apache.paimon.data.InternalRow): util.LinkedHashMap[String, String] = {
    val result = super.generatePartValues(in)
    val getters = rowType.fieldGetters()
    for (i <- 0 until getters.length) {
      rowType.getTypeAt(i) match {
        case _: DateType =>
          result.put(paimonPartitionKeys(i), DateFormatter().format(in.getInt(i)))
        case _ =>
      }
    }
    result
  }
}
