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
package org.apache.gluten.datasource.v2

import org.apache.gluten.datasource.ArrowCSVFileFormat

import org.apache.spark.broadcast.Broadcast
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.csv.CSVOptions
import org.apache.spark.sql.connector.read.{InputPartition, PartitionReader}
import org.apache.spark.sql.execution.datasources.PartitionedFile
import org.apache.spark.sql.execution.datasources.v2.FilePartitionReaderFactory
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.vectorized.ColumnarBatch
import org.apache.spark.util.SerializableConfiguration

case class ArrowCSVPartitionReaderFactory(
    sqlConf: SQLConf,
    broadcastedConf: Broadcast[SerializableConfiguration],
    dataSchema: StructType,
    readDataSchema: StructType,
    readPartitionSchema: StructType,
    options: CSVOptions,
    filters: Seq[Filter])
  extends FilePartitionReaderFactory
  with Logging {

  private val batchSize = sqlConf.parquetVectorizedReaderBatchSize
  private val csvColumnPruning: Boolean = sqlConf.csvColumnPruning

  override def supportColumnarReads(partition: InputPartition): Boolean = true

  override def buildReader(partitionedFile: PartitionedFile): PartitionReader[InternalRow] = {
    // disable row based read
    throw new UnsupportedOperationException
  }

  override def buildColumnarReader(
      partitionedFile: PartitionedFile): PartitionReader[ColumnarBatch] = {
    val actualDataSchema = StructType(
      dataSchema.filterNot(_.name == options.columnNameOfCorruptRecord))
    val actualRequiredSchema = StructType(
      readDataSchema.filterNot(_.name == options.columnNameOfCorruptRecord))
    ArrowCSVFileFormat.checkHeader(
      partitionedFile,
      actualDataSchema,
      actualRequiredSchema,
      options,
      filters,
      broadcastedConf.value.value)
    val iter = {
      val rowIter = ArrowCSVFileFormat.fallbackReadVanilla(
        dataSchema,
        readDataSchema,
        broadcastedConf.value.value,
        options,
        partitionedFile,
        filters,
        csvColumnPruning)
      val (schema, rows) = ArrowCSVFileFormat.withPartitionValue(
        readDataSchema,
        readPartitionSchema,
        rowIter,
        partitionedFile)
      ArrowCSVFileFormat.rowToColumn(schema, batchSize, rows)
    }

    new PartitionReader[ColumnarBatch] {

      override def next(): Boolean = {
        iter.hasNext
      }

      override def get(): ColumnarBatch = {
        iter.next()
      }

      override def close(): Unit = {}
    }
  }

}
