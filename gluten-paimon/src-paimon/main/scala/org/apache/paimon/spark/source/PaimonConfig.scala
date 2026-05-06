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
package org.apache.paimon.spark.source

import org.apache.spark.sql.internal.SQLConf.buildConf

object PaimonConfig {

  val PAIMON_NATIVE_SOURCE_ENABLED =
    buildConf("spark.gluten.paimon.native.source.enabled")
      .doc("When true, enable the paimon native source.")
      .internal()
      .booleanConf
      .createWithDefault(true)

  val PAIMON_NATIVE_MOR_ENABLED =
    buildConf("spark.gluten.paimon.native.mor.source.enabled")
      .doc("When true, enables the paimon mor source.")
      .internal()
      .booleanConf
      .createWithDefault(false)

  val PAIMON_NATIVE_MOR_AGGREGATE_ENGINE_ENABLED =
    buildConf("spark.gluten.paimon.native.mor.aggregate.engine.enabled")
      .doc("When true, enables the paimon mor aggregate engine.")
      .internal()
      .booleanConf
      .createWithDefault(false)

  val PAIMON_NATIVE_MOR_PARTIAL_UPDATE_ENGINE_ENABLED =
    buildConf("spark.gluten.paimon.native.mor.partial.update.engine.enabled")
      .doc("When true, enables the paimon mor partial update engine.")
      .internal()
      .booleanConf
      .createWithDefault(false)

  val PAIMON_NATIVE_SPLIT_ENABLED =
    buildConf("spark.gluten.paimon.native.split.enabled")
      .doc("When true, enables the paimon split creation.")
      .internal()
      .booleanConf
      .createWithDefault(true)

  val PAIMON_NATIVE_CONNECTOR =
    buildConf("spark.gluten.paimon.native.connector")
      .doc(
        "Which connector to use for Paimon scans in Bolt. " +
          "'auto' (default) automatically selects between native Paimon C++ connector " +
          "and Hive-fallback based on schema compatibility. " +
          "'paimon' forces the native Paimon C++ connector (serialized splits). " +
          "'hive' forces the Hive connector fallback (per-file hive-style metadata).")
      .stringConf
      .checkValues(Set("auto", "hive", "paimon"))
      .createWithDefault("auto")

  /** Valid values for PAIMON_NATIVE_CONNECTOR config. */
  object NativeConnectorChoice extends Enumeration {
    val Auto, Paimon, Hive = Value

    def fromString(value: String): Value = {
      values
        .find(_.toString.equalsIgnoreCase(value))
        .getOrElse(
          throw new IllegalArgumentException(
            s"Invalid value '$value' for ${PaimonConfig.PAIMON_NATIVE_CONNECTOR.key}. " +
              s"Valid values: ${values.mkString(", ")}"))
    }
  }

  // ---- Paimon C++ connector performance tuning (passed to native connector) ----

  val PAIMON_READ_BATCH_SIZE =
    buildConf("spark.gluten.paimon.read.batch-size")
      .doc("Batch size (in rows) for reading data from Paimon files.")
      .intConf
      .createWithDefault(4096)

  val PAIMON_READ_MULTI_THREAD_ROW_TO_BATCH =
    buildConf("spark.gluten.paimon.read.multi-thread-row-to-batch")
      .doc("Whether to enable multi-threaded row-to-batch conversion.")
      .booleanConf
      .createWithDefault(false)

  val PAIMON_READ_ROW_TO_BATCH_THREAD_NUM =
    buildConf("spark.gluten.paimon.read.row-to-batch-thread-num")
      .doc("Number of threads for multi-threaded row-to-batch conversion.")
      .intConf
      .createWithDefault(4)

  val PAIMON_READ_PREFETCH_ENABLED =
    buildConf("spark.gluten.paimon.read.prefetch-enabled")
      .doc("Whether to enable prefetching when reading Paimon files.")
      .booleanConf
      .createWithDefault(true)

  val PAIMON_READ_PREFETCH_BATCH_COUNT =
    buildConf("spark.gluten.paimon.read.prefetch-batch-count")
      .doc("Number of batches to prefetch ahead during Paimon file reads.")
      .intConf
      .createWithDefault(2)

  val PAIMON_READ_PREFETCH_MAX_PARALLEL =
    buildConf("spark.gluten.paimon.read.prefetch-max-parallel")
      .doc("Maximum parallelism for Paimon file read prefetch operations.")
      .intConf
      .createWithDefault(4)

  val PAIMON_READ_PREDICATE_FILTER_ENABLED =
    buildConf("spark.gluten.paimon.read.predicate-filter-enabled")
      .doc("Whether to enable predicate filter pushdown into Paimon Parquet reader.")
      .booleanConf
      .createWithDefault(true)

  val PAIMON_NATURAL_READ_SIZE =
    buildConf("spark.gluten.paimon.io.natural-read-size")
      .doc("Natural read size (in bytes) for Paimon file reader coalescing.")
      .longConf
      .createWithDefault(33554432L) // 32MB

  val PAIMON_COALESCE_READS =
    buildConf("spark.gluten.paimon.io.coalesce-reads")
      .doc("Whether to coalesce adjacent reads in Paimon file reader.")
      .booleanConf
      .createWithDefault(true)

}
