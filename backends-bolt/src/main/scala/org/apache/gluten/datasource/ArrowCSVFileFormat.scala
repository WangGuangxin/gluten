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
package org.apache.gluten.datasource

import org.apache.gluten.columnarbatch.ColumnarBatches
import org.apache.gluten.config.BoltConfig
import org.apache.gluten.execution.RowToBoltColumnarExec
import org.apache.gluten.memory.arrow.alloc.ArrowBufferAllocators
import org.apache.gluten.vectorized.ArrowWritableColumnVector

import org.apache.spark.TaskContext
import org.apache.spark.internal.Logging
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.csv.{CSVHeaderChecker, CSVHeaderCheckerHelper, CSVOptions, UnivocityParser}
import org.apache.spark.sql.catalyst.expressions.{AttributeReference, JoinedRow}
import org.apache.spark.sql.catalyst.expressions.codegen.GenerateUnsafeProjection
import org.apache.spark.sql.execution.datasources.{FileFormat, HadoopFileLinesReader, OutputWriterFactory, PartitionedFile}
import org.apache.spark.sql.execution.datasources.csv.{CSVDataSource, CSVFileFormat}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.sources.{DataSourceRegister, Filter}
import org.apache.spark.sql.types.{StructField, StructType}
import org.apache.spark.sql.vectorized.ColumnarBatch
import org.apache.spark.util.SerializableConfiguration

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, Path}

class ArrowCSVFileFormat(parsedOptions: CSVOptions)
  extends FileFormat
  with DataSourceRegister
  with Logging
  with Serializable {

  override def isSplitable(
      sparkSession: SparkSession,
      options: Map[String, String],
      path: Path): Boolean = {
    new CSVFileFormat().isSplitable(sparkSession, options, path)
  }

  override def inferSchema(
      sparkSession: SparkSession,
      options: Map[String, String],
      files: Seq[FileStatus]): Option[StructType] = {
    CSVDataSource(parsedOptions).inferSchema(sparkSession, files, parsedOptions)
  }

  override def supportBatch(sparkSession: SparkSession, dataSchema: StructType): Boolean = true

  override def buildReaderWithPartitionValues(
      sparkSession: SparkSession,
      dataSchema: StructType,
      partitionSchema: StructType,
      requiredSchema: StructType,
      filters: Seq[Filter],
      options: Map[String, String],
      hadoopConf: Configuration): PartitionedFile => Iterator[InternalRow] = {
    val sqlConf = sparkSession.sessionState.conf
    val broadcastedHadoopConf =
      sparkSession.sparkContext.broadcast(new SerializableConfiguration(hadoopConf))
    val batchSize = sqlConf.columnBatchSize
    val columnPruning = sqlConf.csvColumnPruning &&
      !requiredSchema.exists(_.name == sparkSession.sessionState.conf.columnNameOfCorruptRecord)
    val actualFilters =
      filters.filterNot(_.references.contains(parsedOptions.columnNameOfCorruptRecord))
    (file: PartitionedFile) => {
      val iter = ArrowCSVFileFormat.fallbackReadVanilla(
        dataSchema,
        requiredSchema,
        broadcastedHadoopConf.value.value,
        parsedOptions,
        file,
        actualFilters,
        columnPruning)
      val (schema, rows) =
        ArrowCSVFileFormat.withPartitionValue(requiredSchema, partitionSchema, iter, file)
      ArrowCSVFileFormat.rowToColumn(schema, batchSize, rows).asInstanceOf[Iterator[InternalRow]]
    }
  }

  override def vectorTypes(
      requiredSchema: StructType,
      partitionSchema: StructType,
      sqlConf: SQLConf): Option[Seq[String]] = {
    Option(
      Seq.fill(requiredSchema.fields.length + partitionSchema.fields.length)(
        classOf[ArrowWritableColumnVector].getName
      ))
  }

  override def shortName(): String = "arrowcsv"

  override def hashCode(): Int = getClass.hashCode()

  override def equals(other: Any): Boolean = other.isInstanceOf[ArrowCSVFileFormat]

  override def prepareWrite(
      sparkSession: SparkSession,
      job: _root_.org.apache.hadoop.mapreduce.Job,
      options: Map[String, String],
      dataSchema: StructType): OutputWriterFactory = {
    throw new UnsupportedOperationException()
  }
}

object ArrowCSVFileFormat {
  def checkHeader(
      file: PartitionedFile,
      actualDataSchema: StructType,
      actualRequiredSchema: StructType,
      parsedOptions: CSVOptions,
      actualFilters: Seq[Filter],
      conf: Configuration): Unit = {
    val isStartOfFile = file.start == 0
    if (!isStartOfFile) {
      return
    }
    val parser =
      new UnivocityParser(actualDataSchema, actualRequiredSchema, parsedOptions, actualFilters)
    val schema = if (parsedOptions.columnPruning) actualRequiredSchema else actualDataSchema
    val headerChecker = new CSVHeaderChecker(
      schema,
      parsedOptions,
      source = s"CSV file: ${file.filePath}",
      isStartOfFile)

    val lines = {
      val linesReader =
        new HadoopFileLinesReader(file, parser.options.lineSeparatorInRead, conf)
      Option(TaskContext.get())
        .foreach(_.addTaskCompletionListener[Unit](_ => linesReader.close()))
      linesReader.map {
        line => new String(line.getBytes, 0, line.getLength, parser.options.charset)
      }
    }
    CSVHeaderCheckerHelper.checkHeaderColumnNames(headerChecker, lines, parser.tokenizer)
  }

  def rowToColumn(
      schema: StructType,
      batchSize: Int,
      it: Iterator[InternalRow]): Iterator[ColumnarBatch] = {
    val boltBatch = RowToBoltColumnarExec.toColumnarBatchIterator(
      it,
      schema,
      batchSize,
      BoltConfig.get.boltPreferredBatchBytes
    )
    boltBatch
      .map(v => ColumnarBatches.load(ArrowBufferAllocators.contextInstance(), v))
  }

  private def toAttribute(field: StructField): AttributeReference =
    AttributeReference(field.name, field.dataType, field.nullable, field.metadata)()

  private def toAttributes(schema: StructType): Seq[AttributeReference] = {
    schema.map(toAttribute)
  }

  def withPartitionValue(
      requiredSchema: StructType,
      partitionSchema: StructType,
      iter: Iterator[InternalRow],
      file: PartitionedFile): (StructType, Iterator[InternalRow]) = {
    val fullSchema = toAttributes(requiredSchema) ++ toAttributes(partitionSchema)

    // Using lazy val to avoid serialization
    lazy val appendPartitionColumns =
      GenerateUnsafeProjection.generate(fullSchema, fullSchema)
    // Using local val to avoid per-row lazy val check (pre-mature optimization?...)
    val converter = appendPartitionColumns

    // Note that we have to apply the converter even though `file.partitionValues` is empty.
    // This is because the converter is also responsible for converting safe `InternalRow`s into
    // `UnsafeRow`s.
    if (partitionSchema.isEmpty) {
      val rows = iter.map(dataRow => converter(dataRow))
      (StructType(requiredSchema ++ partitionSchema), rows)
    } else {
      val joinedRow = new JoinedRow()
      val rows = iter.map(dataRow => converter(joinedRow(dataRow, file.partitionValues)))
      (StructType(requiredSchema ++ partitionSchema), rows)
    }
  }

  def fallbackReadVanilla(
      dataSchema: StructType,
      requiredSchema: StructType,
      conf: Configuration,
      parsedOptions: CSVOptions,
      file: PartitionedFile,
      actualFilters: Seq[Filter],
      columnPruning: Boolean): Iterator[InternalRow] = {
    val actualDataSchema = StructType(
      dataSchema.filterNot(_.name == parsedOptions.columnNameOfCorruptRecord))
    val actualRequiredSchema = StructType(
      requiredSchema.filterNot(_.name == parsedOptions.columnNameOfCorruptRecord))
    val parser =
      new UnivocityParser(actualDataSchema, actualRequiredSchema, parsedOptions, actualFilters)
    val schema = if (columnPruning) actualRequiredSchema else actualDataSchema
    val isStartOfFile = file.start == 0
    val headerChecker = new CSVHeaderChecker(
      schema,
      parsedOptions,
      source = s"CSV file: ${file.filePath}",
      isStartOfFile)
    CSVDataSource(parsedOptions).readFile(conf, file, parser, headerChecker, requiredSchema)
  }
}
