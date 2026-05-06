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
package org.apache.gluten.execution

import org.apache.paimon.data.InternalRow
import org.apache.paimon.io.DataFileMeta
import org.apache.paimon.spark.PaimonScan
import org.apache.paimon.table.FileStoreTable
import org.apache.paimon.table.source.DataSplit
import org.apache.paimon.types.RowType
import org.apache.paimon.utils.InternalRowPartitionComputer

import scala.collection.JavaConverters.asScalaBufferConverter

class PaimonSparkShimImpl extends PaimonSparkShim {

  override def isChainSplit(split: DataSplit): Boolean = {
    false
  }

  override def getSplitPartition(split: DataSplit): InternalRow = {
    split.partition()
  }

  override def getBucketPath(split: DataSplit, file: DataFileMeta): String = {
      split.bucketPath()
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
}
