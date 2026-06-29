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
package org.apache.gluten.config

import org.apache.spark.sql.internal.SQLConf

/**
 * Bolt-specific configuration. Bolt is a thin Velox-derivative backend, so it reuses Velox/Gluten
 * config and only declares the handful of entries that differ for Bolt.
 *
 * Note: read-sites for the shuffle entries below live in shared backends-velox shuffle writers
 * (`ColumnarShuffleWriter.scala`, `BoltCelebornColumnarShuffleWriter.scala`) and the
 * `shuffle_writer_info.proto`. To keep the thin backend DRY and avoid forking shared velox shuffle
 * code (which would risk changing velox's default behavior), the read-sites are intentionally not
 * wired here. See TODO(bolt) below.
 */
class BoltConfig(conf: SQLConf) extends GlutenConfig(conf) {
  import BoltConfig._

  // TODO(bolt): wire read-site in the bolt shuffle writer and shuffle_writer_info.proto once a
  // dedicated (non-shared) bolt shuffle path exists.
  def shuffleCheckRatio: Double = getConf(COLUMNAR_BOLT_SHUFFLE_CHECK_RATIO)
}

object BoltConfig extends ConfigRegistry {
  override def get: BoltConfig = {
    new BoltConfig(SQLConf.get)
  }

  // Bolt overwrites the Gluten default batch size (4096) with a larger value.
  val COLUMNAR_MAX_BATCH_SIZE =
    buildOrReplaceConf("spark.gluten.sql.columnar.maxBatchSize").intConf
      .checkValue(_ > 0, s"must be positive.")
      .createWithDefault(32768)

  val COLUMNAR_BOLT_SHUFFLE_CHECK_RATIO =
    buildConf("spark.gluten.sql.columnar.backend.bolt.shuffle.check.ratio")
      .internal()
      .doc("The ratio in [0, 1] for enabling bolt shuffle debug check.")
      .doubleConf
      .checkValue(v => v >= 0.0 && v <= 1.0, "must be in [0, 1]")
      .createWithDefault(0)
}
