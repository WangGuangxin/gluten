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
