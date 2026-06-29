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

import org.apache.gluten.backendsapi.velox.VeloxLikeBackend

import org.scalatest.funsuite.AnyFunSuite

/**
 * Bolt is wired as an SPI [[org.apache.gluten.component.Component]] via the resource file
 * `META-INF/gluten-components/org.apache.gluten.backendsapi.bolt.BoltBackend`. We don't go through
 * `BackendsApiManager` here because backends-velox is also on the test classpath (Bolt extends
 * `VeloxLikeBackend`), which would trip the manager's "exactly one Substrait backend" assertion;
 * the assertions below verify SPI discoverability directly.
 */
class BoltBackendSuite extends AnyFunSuite {

  test("BoltBackend reports name 'bolt'") {
    assert(new BoltBackend().name() == "bolt")
  }

  test("BoltBackend reuses VeloxLikeBackend") {
    assert(classOf[VeloxLikeBackend].isAssignableFrom(classOf[BoltBackend]))
  }

  test("BoltBackend is discoverable through META-INF/gluten-components") {
    val resourcePath = "META-INF/gluten-components/" + classOf[BoltBackend].getName
    val url = getClass.getClassLoader.getResource(resourcePath)
    assert(url != null, s"SPI marker resource $resourcePath not found on classpath")
  }
}
