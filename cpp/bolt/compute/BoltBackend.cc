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

#include <jni.h>

#include "compute/Runtime.h"
#include "jni/JniError.h"
#include "memory/AllocationListener.h"
#include "memory/MemoryManager.h"
#include "threads/ThreadManager.h"
#include "utils/Exception.h"

// =============================================================================
// Bolt native registration scaffold
//
// At the JVM layer Bolt extends VeloxLikeBackend and only overrides
// `name() == "bolt"`. VeloxListenerApi(backendName="bolt") therefore:
//   1. Loads `libbolt.so` via System.mapLibraryName("bolt"), and
//   2. Calls `NativeBackendInitializer.forBackend("bolt").initialize(...)`,
//      which invokes the JNI symbol
//      `Java_org_apache_gluten_init_NativeBackendInitializer_initialize`
//      exported by this library.
//
// This file is the *minimum* native contract the JVM side expects. It:
//   * Reserves `kBoltBackendKind = "bolt"` so it lines up with BoltBackend.name()
//     and the Java VeloxLikeBackend wiring.
//   * Registers Runtime / MemoryManager / ThreadManager factories under "bolt"
//     using the SAME `registerFactory` contract that
//     `cpp/velox/compute/VeloxBackend.cc` uses for "velox".
//   * Provides the JNI initialize / shutdown entry points so that loading
//     libbolt.so produces no UnsatisfiedLinkError at startup.
//
// The factory bodies intentionally throw `GlutenException("not implemented")`
// to make it impossible to silently run queries against an empty engine. The
// proprietary Bolt engine source must replace these stubs (see TODO markers).
//
// TODO(bolt native): the JVM-side function offload added alongside this scaffold
// (sequence, map_from_arrays, map_from_entries, format_number) requires matching
// native support in the Bolt plan validator. In the proprietary engine's
// `cpp/bolt/substrait/SubstraitToBoltPlanValidator.cc` these functions must be
// registered / allow-listed (e.g. removed from the validator `kBlackList`) so the
// substrait plans produced by BoltSparkPlanExecApi pass validation:
//   * sequence          -- only non-timestamp inputs (JVM falls back otherwise)
//   * map_from_arrays   -- LAST_WIN dedup only (JVM falls back on FIRST_WIN)
//   * map_from_entries  -- null/duplicate-key semantics per Spark
//   * format_number
// =============================================================================

namespace gluten {

// This kind string must match `BoltBackend#name()` on the Java side.
inline static const std::string kBoltBackendKind{"bolt"};

namespace {

// TODO: plug in proprietary Bolt engine. The scaffold throws so that running
// against an un-integrated build fails fast instead of silently misbehaving.
[[noreturn]] void throwNotIntegrated(const char* component) {
  throw GlutenException(
      std::string("Bolt native backend scaffold loaded but '") + component +
      "' factory is not integrated yet. "
      "Replace cpp/bolt/compute/BoltBackend.cc stubs with the proprietary Bolt engine.");
}

Runtime* boltRuntimeFactory(
    const std::string& /*kind*/,
    MemoryManager* /*memoryManager*/,
    ThreadManager* /*threadManager*/,
    const std::unordered_map<std::string, std::string>& /*sessionConf*/) {
  // TODO: return a new BoltRuntime instance from the proprietary engine.
  throwNotIntegrated("Runtime");
}

void boltRuntimeReleaser(Runtime* runtime) {
  delete runtime;
}

MemoryManager* boltMemoryManagerFactory(
    const std::string& /*kind*/, std::unique_ptr<AllocationListener> /*listener*/) {
  // TODO: return a new BoltMemoryManager instance from the proprietary engine.
  throwNotIntegrated("MemoryManager");
}

void boltMemoryManagerReleaser(MemoryManager* mm) {
  delete mm;
}

ThreadManager* boltThreadManagerFactory(
    const std::string& /*kind*/, std::unique_ptr<ThreadInitializer> /*initializer*/) {
  // TODO: return a new BoltThreadManager instance from the proprietary engine.
  throwNotIntegrated("ThreadManager");
}

void boltThreadManagerReleaser(ThreadManager* tm) {
  delete tm;
}

void registerBoltFactories() {
  // Same `Runtime::registerFactory(kind, factory, releaser)` contract that the
  // Velox backend uses; see cpp/velox/compute/VeloxBackend.cc line ~149.
  MemoryManager::registerFactory(kBoltBackendKind, boltMemoryManagerFactory, boltMemoryManagerReleaser);
  ThreadManager::registerFactory(kBoltBackendKind, boltThreadManagerFactory, boltThreadManagerReleaser);
  Runtime::registerFactory(kBoltBackendKind, boltRuntimeFactory, boltRuntimeReleaser);
}

} // namespace
} // namespace gluten

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT void JNICALL Java_org_apache_gluten_init_NativeBackendInitializer_initialize( // NOLINT
    JNIEnv* /*env*/,
    jclass,
    jobject /*jListener*/,
    jbyteArray /*conf*/) {
  JNI_METHOD_START
  // TODO: consume jListener / conf the same way VeloxBackend::create does once
  // the proprietary Bolt engine is wired in.
  gluten::registerBoltFactories();
  JNI_METHOD_END()
}

JNIEXPORT void JNICALL Java_org_apache_gluten_init_NativeBackendInitializer_shutdown( // NOLINT
    JNIEnv* /*env*/,
    jclass) {
  JNI_METHOD_START
  // TODO: call into proprietary Bolt engine tearDown when integrated.
  JNI_METHOD_END()
}

#ifdef __cplusplus
}
#endif
