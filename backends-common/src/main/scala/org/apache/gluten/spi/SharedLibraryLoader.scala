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
package org.apache.gluten.spi

/**
 * :: DeveloperApi ::
 *
 * A common interface for loading native shared libraries based on the operating system name and
 * version.
 *
 * Implementations are discovered via [[java.util.ServiceLoader]] using the
 * `META-INF/services/org.apache.gluten.spi.SharedLibraryLoader` resource.
 */
trait SharedLibraryLoader {

  /**
   * Check if this loader can load libraries for the given OS name and version.
   *
   * @param osName
   *   OS name
   * @param osVersion
   *   OS version
   * @return
   *   true if this loader can load libraries for the given OS name and version, false otherwise
   */
  def accepts(osName: String, osVersion: String): Boolean

  /**
   * Load the required shared libraries using the given native library loader instance.
   *
   * The concrete type of `loader` is backend-specific, e.g. Velox uses
   * `org.apache.gluten.jni.JniLibLoader` while Bolt uses `org.apache.gluten.jni.BoltJniLibLoader`.
   */
  def loadLib(loader: AnyRef): Unit
}
