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
import org.apache.gluten.expression.{GenericExpressionTransformer, VeloxBloomFilterMightContain}

import org.apache.spark.sql.GlutenQueryTest
import org.apache.spark.sql.catalyst.expressions.{AttributeReference, Literal}
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.{BinaryType, LongType}

class GlutenArmTuningConfigSuite extends GlutenQueryTest with SharedSparkSession {
  // Register static options before testing forwarding in an isolated suite.
  private val sveGatherConfig = VeloxConfig.ARM_SVE_GATHER_ENABLED

  test("batched might_contain is opt-in per session and selects the SVE probe separately") {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("Batched might_contain is specific to the Velox backend")
    }
    val batch = "spark.gluten.sql.columnar.backend.velox.bloomFilter.batchProbe.enabled"
    val sve = "spark.gluten.sql.columnar.backend.velox.arm.sveBloomProbe.enabled"
    assert(!VeloxConfig.BLOOM_FILTER_BATCH_PROBE_ENABLED.defaultValue.get)
    assert(!VeloxConfig.ARM_SVE_BLOOM_PROBE_ENABLED.defaultValue.get)
    val value = AttributeReference("v", LongType)()
    val expr = VeloxBloomFilterMightContain(Literal(Array[Byte](1, 0, 0, 0, 1), BinaryType), value)
    val api = BackendsApiManager.getSparkPlanExecApiInstance
    def converted: Option[String] = api.extraExpressionConverter("might_contain", expr, Seq(value))
      .map(_.asInstanceOf[GenericExpressionTransformer].substraitExprName)
    Seq(
      ("false", "false", None),
      ("false", "true", None),
      ("true", "false", Some("might_contain_batch")),
      ("true", "true", Some("might_contain_batch_sve"))
    ).foreach {
      case (batchValue, sveValue, expected) =>
        withSQLConf(batch -> batchValue, sve -> sveValue)(assert(converted == expected))
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

  test("integer row extraction switches are independently opt-in and application-static") {
    if (BackendsApiManager.getBackendName != "velox") {
      cancel("Integer row extraction is specific to the Velox backend")
    }
    Seq(
      VeloxConfig.ROW_CONTAINER_BATCH_FIXED_EXTRACTION_ENABLED ->
        "spark.gluten.sql.columnar.backend.velox.rowContainer.batchFixedExtraction.enabled",
      VeloxConfig.ARM_SVE_ROW_EXTRACTION_ENABLED ->
        "spark.gluten.sql.columnar.backend.velox.arm.sveRowExtraction.enabled"
    ).foreach {
      case (config, key) =>
        assert(!config.defaultValue.get)
        Seq("false", "true").foreach {
          enabled =>
            assert(GlutenConfig.getNativeBackendConf(
              "velox",
              Map(key -> enabled)).get(key) == enabled)
            assert(!GlutenConfig.getNativeSessionConf("velox", Map(key -> enabled)).contains(key))
        }
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
      (prefix + "hashJoin.prefetch.enabled") -> "false"
    )
    val native = GlutenConfig.getNativeSessionConf("velox", overrides)
    overrides.foreach { case (key, value) => assert(native(key) == value) }
  }
}
