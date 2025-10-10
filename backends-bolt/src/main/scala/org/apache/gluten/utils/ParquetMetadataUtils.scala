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

import org.apache.gluten.config.GlutenConfig
import org.apache.gluten.sql.shims.SparkShimLoader

import org.apache.spark.sql.execution.datasources.DataSourceUtils
import org.apache.spark.sql.execution.datasources.parquet.{ParquetFooterReaderShim, ParquetOptions}

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, LocatedFileStatus, Path}
import org.apache.parquet.crypto.ParquetCryptoRuntimeException
import org.apache.parquet.format.converter.ParquetMetadataConverter
import org.apache.parquet.hadoop.metadata.ParquetMetadata

object ParquetMetadataUtils {

  /**
   * Validates whether Parquet metadata is unsupported for the given paths.
   */
  def validateMetadata(
      rootPaths: Seq[String],
      hadoopConf: Configuration,
      parquetOptions: ParquetOptions,
      fileLimit: Int
  ): Option[String] = {
    if (!GlutenConfig.get.parquetMetadataValidationEnabled) {
      return None
    }

    val sampledPaths = sampleRootPaths(
      rootPaths,
      GlutenConfig.get.parquetMetadataFallbackSamplePercentage)
    sampledPaths.foreach {
      rootPath =>
        val fs = new Path(rootPath).getFileSystem(hadoopConf)
        try {
          val maybeReason =
            checkForUnexpectedMetadataWithLimit(
              fs,
              new Path(rootPath),
              hadoopConf,
              parquetOptions,
              fileLimit = fileLimit)
          if (maybeReason.isDefined) {
            return maybeReason
          }
        } catch {
          case _: Exception =>
        }
    }
    None
  }

  private def sampleRootPaths(rootPaths: Seq[String], samplePercentage: Double): Seq[String] = {
    if (samplePercentage >= 1.0 || rootPaths.size <= 1) {
      return rootPaths
    }
    val sampleCount = math.max(1, math.ceil(rootPaths.size * samplePercentage).toInt)
    if (sampleCount >= rootPaths.size) {
      return rootPaths
    }
    val step = rootPaths.size.toDouble / sampleCount
    (0 until sampleCount).map(i => rootPaths((i * step).toInt))
  }

  private def checkForUnexpectedMetadataWithLimit(
      fs: FileSystem,
      path: Path,
      conf: Configuration,
      parquetOptions: ParquetOptions,
      fileLimit: Int
  ): Option[String] = {
    val filesIterator = fs.listFiles(path, true)
    var checkedFileCount = 0
    while (filesIterator.hasNext && checkedFileCount < fileLimit) {
      val fileStatus = filesIterator.next()
      checkedFileCount += 1
      val metadataUnsupported = isUnsupportedMetadata(fileStatus, conf, parquetOptions)
      if (metadataUnsupported.isDefined) {
        return metadataUnsupported
      }
    }
    None
  }

  private def isUnsupportedMetadata(
      fileStatus: LocatedFileStatus,
      conf: Configuration,
      parquetOptions: ParquetOptions): Option[String] = {
    val footer =
      try {
        ParquetFooterReaderShim.readFooter(conf, fileStatus, ParquetMetadataConverter.NO_FILTER)
      } catch {
        case e: Exception if ExceptionUtils.hasCause(e, classOf[ParquetCryptoRuntimeException]) =>
          return Some("Encrypted Parquet footer detected.")
        case _: RuntimeException =>
          return None
      }
    val validationChecks = Seq(
      isTimezoneFoundInMetadata(footer, parquetOptions)
    )

    for (check <- validationChecks) {
      if (check.isDefined) {
        return check
      }
    }

    if (SparkShimLoader.getSparkShims.isParquetFileEncrypted(footer)) {
      return Some("Encrypted Parquet file detected.")
    }
    None
  }

  private def isTimezoneFoundInMetadata(
      footer: ParquetMetadata,
      parquetOptions: ParquetOptions): Option[String] = {
    val footerFileMetaData = footer.getFileMetaData
    val datetimeRebaseSpec = DataSourceUtils.datetimeRebaseSpec(
      footerFileMetaData.getKeyValueMetaData.get,
      parquetOptions.datetimeRebaseModeInRead)
    val int96RebaseSpec = DataSourceUtils.int96RebaseSpec(
      footerFileMetaData.getKeyValueMetaData.get,
      parquetOptions.int96RebaseModeInRead)
    if (datetimeRebaseSpec.originTimeZone.nonEmpty || int96RebaseSpec.originTimeZone.nonEmpty) {
      return Some("Legacy timezone found.")
    }
    None
  }
}
