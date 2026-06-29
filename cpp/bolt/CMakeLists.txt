# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

cmake_minimum_required(VERSION 3.16)

project(bolt)

# Bolt is a Velox-derivative engine that reuses the same JNI / Runtime / Substrait
# contract as the Velox backend on the JVM side. This CMakeLists builds the bare
# native registration scaffold (libbolt.so) so that:
#
#   * `System.loadLibrary("bolt")` from VeloxListenerApi succeeds, and
#   * `NativeBackendInitializer.forBackend("bolt")` finds a matching
#     `Java_org_apache_gluten_init_NativeBackendInitializer_initialize` symbol.
#
# The proprietary Bolt engine source is intentionally NOT bundled in this
# repository. The scaffold registers stub Runtime / MemoryManager / ThreadManager
# factories under the kind "bolt"; production builds must replace the stubs
# with the real Bolt factories (see TODO markers in compute/BoltBackend.cc).

set(BOLT_SRCS compute/BoltBackend.cc)

add_library(bolt SHARED ${BOLT_SRCS})

# Bolt links against gluten core only. We intentionally do NOT link the velox
# target here: libbolt.so is meant to live as an alternative to libvelox.so at
# runtime (a JVM only ever loads one of them, picked by BoltBackend.name()).
# Once the proprietary Bolt engine is integrated, add its libraries here.
target_link_libraries(bolt PUBLIC gluten)

target_include_directories(
  bolt PUBLIC ${CMAKE_SYSTEM_INCLUDE_PATH} ${JNI_INCLUDE_DIRS}
              ${CMAKE_SOURCE_DIR}/core)

set_target_properties(bolt PROPERTIES LIBRARY_OUTPUT_DIRECTORY
                                      ${root_directory}/releases)
