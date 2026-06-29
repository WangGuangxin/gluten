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
package org.apache.gluten.backendsapi.bolt

import org.apache.gluten.backendsapi.SparkPlanExecApi
import org.apache.gluten.backendsapi.velox.VeloxLikeBackend
import org.apache.gluten.config.GlutenConfig

/**
 * Bolt is ByteDance's internal Velox-derivative engine. At the JVM level it is Velox-compatible: it
 * reuses the same JNI wrappers, Runtime and Substrait contract, so it inherits all the logic from
 * [[VeloxLikeBackend]] and only supplies its own backend identity.
 *
 * The inherited `listenerApi()` returns `new VeloxListenerApi(name())`, so Bolt automatically loads
 * `libbolt.so` and initializes the "bolt" native backend. No separate `BoltListenerApi` is needed
 * unless a behavioral difference shows up later.
 */
class BoltBackend extends VeloxLikeBackend {
  override def name(): String = BoltBackend.BACKEND_NAME

  // Bolt customizes a few expression transformers (sequence / map_from_arrays dedup policy)
  // on top of the inherited Velox implementation; everything else stays inherited.
  override def sparkPlanExecApi(): SparkPlanExecApi = new BoltSparkPlanExecApi
}

object BoltBackend {
  val BACKEND_NAME: String = "bolt"
  val CONF_PREFIX: String = GlutenConfig.prefixOf(BACKEND_NAME)
}
