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

import org.apache.gluten.backendsapi.BackendsApiManager

import org.apache.spark.sql.GlutenQueryTest
import org.apache.spark.sql.test.SharedSparkSession

class GlutenArmTuningConfigSuite extends GlutenQueryTest with SharedSparkSession {
  // Register static options before testing forwarding in an isolated suite.
  private val sveGatherConfig = VeloxConfig.ARM_SVE_GATHER_ENABLED

  test("batch hash-build store preserves duplicate joins nulls strings and decimals") {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("Batch hash-build store is specific to the Velox backend")
    }
    val query = """
                  |SELECT /*+ SHUFFLE_HASH(r) */ l.id, r.s, r.v
                  |FROM range(72) l LEFT JOIN (
                  |  SELECT id % 19 AS k,
                  |         CASE WHEN id % 7 = 0 THEN NULL
                  |              ELSE concat('long-build-value-', CAST(id AS STRING)) END AS s,
                  |         CAST(id / 3.0 AS DECIMAL(28, 4)) AS v
                  |  FROM range(129)
                  |) r ON l.id % 23 = r.k
                  |""".stripMargin
    val expected = withSQLConf("spark.gluten.enabled" -> "false") {
      sql(query).collect().toSeq
    }
    Seq("false", "true").foreach {
      enabled =>
        withSQLConf(
          "spark.gluten.enabled" -> "true",
          "spark.sql.adaptive.enabled" -> "false",
          "spark.gluten.sql.columnar.backend.velox.hashBuild.batchStore.enabled" -> enabled
        ) {
          checkAnswer(sql(query), expected)
        }
    }
  }

  test("grouping-set batching preserves nulls decimal sums and duplicate sets") {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("Grouping-set batching is specific to the Velox backend")
    }
    val query = """
                  |SELECT k, s, grouping_id(), sum(v), count(*)
                  |FROM VALUES
                  |  (1, 'long-string-key', CAST(1.23 AS DECIMAL(12,2))),
                  |  (1, 'long-string-key', CAST(NULL AS DECIMAL(12,2))),
                  |  (2, CAST(NULL AS STRING), CAST(2.34 AS DECIMAL(12,2))),
                  |  (CAST(NULL AS INT), 'another-long-key', CAST(-0.45 AS DECIMAL(12,2)))
                  |AS t(k, s, v)
                  |GROUP BY GROUPING SETS ((k, s), (k), (k), ())
                  |""".stripMargin
    val expected = withSQLConf("spark.gluten.enabled" -> "false") {
      sql(query).collect().toSeq
    }
    withSQLConf(
      "spark.gluten.enabled" -> "true",
      "spark.gluten.sql.columnar.backend.velox.aggregation.groupingSetsBatching.enabled" -> "true") {
      checkAnswer(sql(query), expected)
    }
  }

  test("hash-build value-ID reuse preserves semi-join multiplicity and nulls") {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("Hash-build value-ID reuse is specific to the Velox backend")
    }
    val query = """
                  |SELECT /*+ SHUFFLE_HASH(r) */ l.k
                  |FROM VALUES (1L), (1L), (2L), (3L), (CAST(NULL AS BIGINT)) AS l(k)
                  |LEFT SEMI JOIN
                  |  (SELECT id DIV 12 AS k FROM range(48)) r
                  |ON l.k = r.k
                  |""".stripMargin
    val expected = withSQLConf("spark.gluten.enabled" -> "false") {
      sql(query).collect().toSeq
    }
    withSQLConf(
      "spark.gluten.enabled" -> "true",
      "spark.gluten.sql.columnar.backend.velox.hashJoin.batchValueIds.enabled" -> "true") {
      checkAnswer(sql(query), expected)
    }
  }

  test("batch string extraction reaches native backend initialization") {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("Batch string extraction is specific to the Velox backend")
    }
    val key =
      "spark.gluten.sql.columnar.backend.velox.rowContainer.batchStringExtraction.enabled"
    Seq("false", "true").foreach {
      enabled =>
        val native = GlutenConfig.getNativeBackendConf("velox", Map(key -> enabled))
        assert(native.get(key) == enabled)
        assert(!GlutenConfig.getNativeSessionConf("velox", Map(key -> enabled)).contains(key))
    }
  }

  test("string equality reaches application-static native initialization") {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("String equality is specific to the Velox backend")
    }
    val key = "spark.gluten.sql.columnar.backend.velox.rowContainer.stringEquality.enabled"
    Seq("false", "true").foreach {
      enabled =>
        val native = GlutenConfig.getNativeBackendConf("velox", Map(key -> enabled))
        assert(native.get(key) == enabled)
        assert(!GlutenConfig.getNativeSessionConf("velox", Map(key -> enabled)).contains(key))
    }
  }

  test("ARM SVE gather is opt-in and reaches only application-static native initialization") {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("ARM SVE gather is specific to the Velox backend")
    }
    val key = "spark.gluten.sql.columnar.backend.velox.arm.sveGather.enabled"
    assert(!sveGatherConfig.defaultValue.get)
    Seq("false", "true").foreach {
      enabled =>
        assert(GlutenConfig.getNativeBackendConf("velox", Map(key -> enabled)).get(key) == enabled)
        assert(!GlutenConfig.getNativeSessionConf("velox", Map(key -> enabled)).contains(key))
    }
  }

  test("shuffle byte LZ4 is opt-in and application-static") {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("shuffle byte LZ4 is specific to the Velox backend")
    }
    val config = VeloxConfig.SHUFFLE_BYTE_LZ4_ENABLED
    val key = "spark.gluten.sql.columnar.backend.velox.shuffle.byteShuffleLz4.enabled"
    assert(!config.defaultValue.get)
    Seq("false", "true").foreach {
      enabled =>
        assert(GlutenConfig.getNativeBackendConf("velox", Map(key -> enabled)).get(key) == enabled)
        assert(!GlutenConfig.getNativeSessionConf("velox", Map(key -> enabled)).contains(key))
    }
  }

  test("ARM NEON byte shuffle is opt-in and application-static") {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("ARM NEON byte shuffle is specific to the Velox backend")
    }
    val config = VeloxConfig.ARM_NEON_BYTE_SHUFFLE_ENABLED
    val key = "spark.gluten.sql.columnar.backend.velox.arm.neonByteShuffle.enabled"
    assert(!config.defaultValue.get)
    Seq("false", "true").foreach {
      enabled =>
        assert(GlutenConfig.getNativeBackendConf("velox", Map(key -> enabled)).get(key) == enabled)
        assert(!GlutenConfig.getNativeSessionConf("velox", Map(key -> enabled)).contains(key))
    }
  }

  test("shuffle byte LZ4 usage logging is opt-in and application-static") {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("shuffle byte LZ4 usage logging is specific to the Velox backend")
    }
    val config = VeloxConfig.SHUFFLE_BYTE_LZ4_LOG_USAGE
    val key = "spark.gluten.sql.columnar.backend.velox.shuffle.byteShuffleLz4.logUsage"
    assert(!config.defaultValue.get)
    Seq("false", "true").foreach {
      enabled =>
        assert(GlutenConfig.getNativeBackendConf("velox", Map(key -> enabled)).get(key) == enabled)
        assert(!GlutenConfig.getNativeSessionConf("velox", Map(key -> enabled)).contains(key))
    }
  }

  test("ARM SVE hash tail is opt-in and application-static") {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("ARM SVE hash tail is specific to the Velox backend")
    }
    val config = VeloxConfig.ARM_SVE_HASH_TAIL_ENABLED
    val key = "spark.gluten.sql.columnar.backend.velox.arm.sveHashTail.enabled"
    assert(!config.defaultValue.get)
    Seq("false", "true").foreach {
      enabled =>
        assert(GlutenConfig.getNativeBackendConf("velox", Map(key -> enabled)).get(key) == enabled)
        assert(!GlutenConfig.getNativeSessionConf("velox", Map(key -> enabled)).contains(key))
    }
  }

  test("ARM SVE bit deposit is opt-in and application-static") {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("ARM SVE bit deposit is specific to the Velox backend")
    }
    val config = VeloxConfig.ARM_SVE_BIT_DEPOSIT_ENABLED
    val key = "spark.gluten.sql.columnar.backend.velox.arm.sveBitDeposit.enabled"
    assert(!config.defaultValue.get)
    Seq("false", "true").foreach {
      enabled =>
        assert(GlutenConfig.getNativeBackendConf("velox", Map(key -> enabled)).get(key) == enabled)
        assert(!GlutenConfig.getNativeSessionConf("velox", Map(key -> enabled)).contains(key))
    }
  }

  test("ARM SVE bit unpack is opt-in and application-static") {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("ARM SVE bit unpack is specific to the Velox backend")
    }
    val config = VeloxConfig.ARM_SVE_BIT_UNPACK_ENABLED
    val key = "spark.gluten.sql.columnar.backend.velox.arm.sveBitUnpack.enabled"
    assert(!config.defaultValue.get)
    Seq("false", "true").foreach {
      enabled =>
        assert(GlutenConfig.getNativeBackendConf("velox", Map(key -> enabled)).get(key) == enabled)
        assert(!GlutenConfig.getNativeSessionConf("velox", Map(key -> enabled)).contains(key))
    }
  }

  test("head-chain drain reaches application-static native initialization") {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("Head-chain drain is specific to Velox")
    }
    val key = "spark.gluten.sql.columnar.backend.velox.hashJoin.headChainDrain.enabled"
    Seq("false", "true").foreach {
      enabled =>
        assert(GlutenConfig.getNativeBackendConf("velox", Map(key -> enabled)).get(key) == enabled)
        assert(!GlutenConfig.getNativeSessionConf("velox", Map(key -> enabled)).contains(key))
    }
  }

  test("fixed-width join output reaches application-static native initialization") {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("Fixed-width join output is specific to the Velox backend")
    }
    val key = "spark.gluten.sql.columnar.backend.velox.hashJoin.fixedWidthOutput.enabled"
    Seq("false", "true").foreach {
      enabled =>
        assert(GlutenConfig.getNativeBackendConf("velox", Map(key -> enabled)).get(key) == enabled)
        assert(!GlutenConfig.getNativeSessionConf("velox", Map(key -> enabled)).contains(key))
    }
  }

  test("shuffle reuse switches reach application-static native initialization") {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("Shuffle reuse is specific to the Velox backend")
    }
    Seq("arrowMemoryManagerReuse", "lz4FrameContextReuse").foreach {
      feature =>
        val key = s"spark.gluten.sql.columnar.backend.velox.shuffle.$feature.enabled"
        Seq("false", "true").foreach {
          enabled =>
            assert(GlutenConfig.getNativeBackendConf(
              "velox",
              Map(key -> enabled)).get(key) == enabled)
            assert(!GlutenConfig.getNativeSessionConf("velox", Map(key -> enabled)).contains(key))
        }
    }
  }

  test("Expand value ID tuning reaches per-query native configuration") {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("Grouping value IDs are specific to Velox")
    }
    val prefix = "spark.gluten.sql.columnar.backend.velox.aggregation.groupingSetsValueIds."
    val values = Map(
      (prefix + "enabled") -> "true",
      (prefix + "reuse.enabled") -> "true",
      (prefix + "maxDistinctStringBytes") -> "33554432",
      (prefix + "maxDistinctStringValues") -> "500000"
    )
    val native = GlutenConfig.getNativeSessionConf("velox", values)
    values.foreach { case (key, value) => assert(native.get(key).contains(value)) }
  }

  test("ARM tuning values reach native session configuration") {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("ARM tuning is specific to the Velox backend")
    }
    // Keep this shared test source independent of backend-specific classes.
    val prefix = "spark.gluten.sql.columnar.backend.velox."
    val overrides = Map(
      (prefix + "hashShuffle.sveGather.enabled") -> "false",
      (prefix + "hashJoin.numParallelChains") -> "16",
      (prefix + "hashJoin.aheadWindow") -> "4",
      (prefix + "hashJoin.prefetch.enabled") -> "false",
      (prefix + "hashJoin.adaptiveValueIds.enabled") -> "true",
      (prefix + "hashJoin.adaptiveValueIds.minRows") -> "16384",
      (prefix + "hashJoin.adaptiveValueIds.minDistinctPct") -> "90",
      (prefix + "hashJoin.batchValueIds.enabled") -> "true",
      (prefix + "hashBuild.batchStore.enabled") -> "true",
      (prefix + "aggregation.groupingSetsBatching.enabled") -> "true"
    )
    val native = GlutenConfig.getNativeSessionConf("velox", overrides)
    overrides.foreach { case (key, value) => assert(native(key) == value) }
  }
  test("compact join settings reach application-static native initialization") {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("Compact join chains are specific to Velox")
    }
    val prefix = "spark.gluten.sql.columnar.backend.velox.hashJoin."
    val settings = Map(
      (prefix + "compactChains.enabled") -> "true",
      (prefix + "compactChains.maxBytes") -> "0",
      (prefix + "estimatedWidthOutput.enabled") -> "true")
    val backend = GlutenConfig.getNativeBackendConf("velox", settings)
    val session = GlutenConfig.getNativeSessionConf("velox", settings)
    settings.foreach {
      case (key, value) =>
        assert(backend.get(key) == value)
        assert(!session.contains(key))
    }
  }

  test("adaptive aggregation and string prefetch preserve native configuration scope") {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("These options are specific to Velox")
    }
    val prefix = "spark.gluten.sql.columnar.backend.velox."
    val dynamic = Map(
      (prefix + "aggregation.adaptivePartialMemory.enabled") -> "true",
      (prefix + "aggregation.adaptivePartialMemory.maxBytes") -> "67108864")
    val native = GlutenConfig.getNativeSessionConf("velox", dynamic)
    dynamic.foreach { case (key, value) => assert(native.get(key).contains(value)) }
    val key = prefix + "rowContainer.stringExtractionPrefetch.enabled"
    Seq("false", "true").foreach {
      value =>
        assert(GlutenConfig.getNativeBackendConf("velox", Map(key -> value)).get(key) == value)
        assert(!GlutenConfig.getNativeSessionConf("velox", Map(key -> value)).contains(key))
    }
  }

  test("LZ4 fast compression level reaches application-static backend initialization") {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("Native LZ4F tuning is specific to Velox")
    }
    val key = "spark.gluten.sql.columnar.backend.velox.shuffle.lz4Frame.fastCompressionLevel"
    Seq("0", "4", "8", "32").foreach {
      value =>
        assert(GlutenConfig.getNativeBackendConf("velox", Map(key -> value)).get(key) == value)
        assert(!GlutenConfig.getNativeSessionConf("velox", Map(key -> value)).contains(key))
    }
  }

}
