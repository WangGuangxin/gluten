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

set(CMAKE_POLICY_WARNING_CMP0177 OFF)
set(CMAKE_SUPPRESS_DEVELOPER_WARNINGS ON CACHE BOOL "Suppress developer warnings")

project(velox_backend)

include(ExternalProject)
include(FindPkgConfig)
include(GNUInstallDirs)
include(CheckCXXCompilerFlag)
include(FindPackageHandleStandardArgs)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if (CMAKE_SYSTEM_PROCESSOR MATCHES "(x86)|(X86)|(amd64)|(AMD64)")
  set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mavx -mavx2")
endif()
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wno-deprecated-declarations -Wno-attributes")
if (NOT ${CMAKE_SYSTEM_NAME} STREQUAL "Darwin")
  set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wno-class-memaccess")
endif()

macro(find_awssdk)
  set (CMAKE_FIND_LIBRARY_SUFFIXES ".a")
  find_package(AWSSDK REQUIRED COMPONENTS s3;identity-management)
endmacro()

macro(find_gcssdk)
  set (CMAKE_FIND_LIBRARY_SUFFIXES ".so")
  find_package(google_cloud_cpp_storage REQUIRED)
endmacro()

macro(find_azure)
  find_package(CURL REQUIRED)
  find_package(LibXml2 REQUIRED)
  set (CMAKE_FIND_LIBRARY_SUFFIXES ".a")
  find_package(azure-storage-blobs-cpp CONFIG REQUIRED)
  find_package(azure-storage-files-datalake-cpp CONFIG REQUIRED)
endmacro()
# Build Velox backend.
add_subdirectory(version)

set(VELOX_SRCS
    compute/VeloxBackend.cc
    compute/VeloxRuntime.cc
    compute/WholeStageResultIterator.cc
    compute/VeloxPlanConverter.cc
    # compute/iceberg/IcebergPlanConverter.cc
    jni/VeloxJniWrapper.cc
    # jni/JniFileSystem.cc
    jni/JniUdf.cc
    memory/BufferOutputStream.cc
    memory/VeloxColumnarBatch.cc
    memory/VeloxMemoryManager.cc
    operators/functions/RegistrationAllFunctions.cc
    operators/functions/RowConstructorWithNull.cc
    operators/functions/SparkTokenizer.cc
    operators/serializer/VeloxColumnarToRowConverter.cc
    operators/serializer/VeloxColumnarBatchSerializer.cc
    operators/serializer/VeloxRowToColumnarConverter.cc
    operators/writer/VeloxParquetDatasource.cc
    shuffle/VeloxShuffleReader.cc
    shuffle/VeloxHashBasedShuffleWriter.cc
    shuffle/VeloxSortBasedShuffleWriter.cc
    substrait/SubstraitParser.cc
    substrait/SubstraitToVeloxExpr.cc
    substrait/SubstraitToVeloxPlan.cc
    substrait/SubstraitToVeloxPlanValidator.cc
    substrait/VariantToVectorConverter.cc
    substrait/SubstraitExtensionCollector.cc
    substrait/VeloxSubstraitSignature.cc
    substrait/VeloxToSubstraitExpr.cc
    substrait/VeloxToSubstraitPlan.cc
    substrait/VeloxToSubstraitType.cc
    udf/UdfLoader.cc
    utils/VeloxArrowUtils.cc
    utils/ConfigExtractor.cc
    utils/Common.cc
    )

if (BUILD_TESTS)
  list(APPEND VELOX_SRCS utils/tests/MemoryPoolUtils.cc)
endif()

add_library(velox_backend ${VELOX_SRCS})

target_compile_options(velox_backend PUBLIC -fPIC)

# # so that jit code can call c++ function directly
# target_link_options(velox_backend PUBLIC -rdynamic)

if (BUILD_STATIC)
  add_library(velox_backend_static STATIC ${VELOX_SRCS})
endif()

if(ENABLE_GLUTEN_VCPKG)
  # Hide symbols of static dependencies
  target_link_options(velox_backend PRIVATE -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/symbols.map)
  if (BUILD_STATIC)
    target_link_options(velox_backend_static PRIVATE -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/symbols.map)
  endif()
endif()

target_include_directories(velox_backend PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_include_directories(velox_backend PUBLIC ${JNI_INCLUDE_DIRS})

if (BUILD_STATIC)
  target_include_directories(velox_backend_static PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
  target_include_directories(velox_backend_static PUBLIC ${JNI_INCLUDE_DIRS})
endif()

## If folly is not installed in system lib paths, please add
## `-DCMAKE_PREFIX_PATH="${folly lib path}" to cmake arguments.
## It is also applicable to other dependencies.
find_package(Folly REQUIRED CONFIG)
find_package(gfx-timsort REQUIRED CONFIG)

if(ENABLE_GLUTEN_VCPKG)
  find_package(gflags REQUIRED COMPONENTS static CONFIG)
else()
  find_package(gflags REQUIRED COMPONENTS shared CONFIG)
endif()

find_package(velox CONFIG REQUIRED)
find_package(xxHash CONFIG REQUIRED)

target_link_libraries(velox_backend PUBLIC gluten velox::velox xxHash::xxhash gfx::timsort)
target_include_directories(velox_backend PUBLIC gfx::timsort)

execute_process(
    COMMAND bash ${CMAKE_CURRENT_SOURCE_DIR}/velox-build-info.sh ${velox_INCLUDE_DIRS}
    OUTPUT_VARIABLE velox_build_info
)
message(STATUS "Bolt Build Info:\n${velox_build_info}")

if (BUILD_STATIC)
  target_link_libraries(velox_backend_static PUBLIC gluten velox::velox xxHash::xxhash gfx::timsort)
endif()

if(BUILD_TESTS)
  add_subdirectory(tests)
endif()

if(BUILD_BENCHMARKS)
  add_subdirectory(benchmarks)
endif()

if(ENABLE_S3)
  add_definitions(-DENABLE_S3)
  find_awssdk()
  target_link_libraries(velox_backend PUBLIC ${AWSSDK_LIBRARIES})
  if (BUILD_STATIC)
    target_link_libraries(velox_backend_static PUBLIC ${AWSSDK_LIBRARIES})
  endif()
endif()

if(ENABLE_GCS)
  add_definitions(-DENABLE_GCS)
  find_gcssdk()
  target_link_libraries(velox PUBLIC google-cloud-cpp::storage)
endif()

if(BUILD_EXAMPLES)
  # add_subdirectory(udf/examples)
endif()

if(ENABLE_ABFS)
  add_definitions(-DENABLE_ABFS)
  find_azure()
  target_link_libraries(velox PUBLIC Azure::azure-storage-blobs)
  target_link_libraries(velox PUBLIC Azure::azure-storage-files-datalake)
endif()

# add arch suffix (.amd64 or .aarch64)
# Aligned with java ``` System.getProperty("os.arch") ```
IF((${CMAKE_SYSTEM_PROCESSOR} MATCHES "x86_64") OR (${CMAKE_SYSTEM_PROCESSOR} MATCHES "AMD64"))
  set(ARCH "amd64")
ELSEIF(${CMAKE_SYSTEM_PROCESSOR} MATCHES "aarch64")
  set(ARCH "aarch64")
ELSE()
  message(FATAL_ERROR "Unsupported System Architecture: ${CMAKE_SYSTEM_PROCESSOR}")
ENDIF()

get_target_property(target_type velox_backend TYPE)

if (${target_type} STREQUAL "SHARED_LIBRARY")
  set_target_properties(velox_backend PROPERTIES SUFFIX ".${ARCH}${CMAKE_SHARED_LIBRARY_SUFFIX}")
  install(TARGETS velox_backend
        DESTINATION ${CMAKE_CURRENT_SOURCE_DIR}/../build/releases)
endif()

# For conan exporting
# string(FIND "${CMAKE_INSTALL_PREFIX}" "conan" IS_CONAN_EXPORT)
# ${IS_CONAN_EXPORT} GREATER_EQUAL 0
if ( ${target_type} STREQUAL "STATIC_LIBRARY" )

  # the user can work only with bolt's library/header files
  # Anyway, export gluten's Udf.h here.
  install(
    DIRECTORY "${CMAKE_SOURCE_DIR}/velox/udf/" # source directory
    DESTINATION "include/gluten/udf" # target directory
    FILES_MATCHING # install only matched files
    # PATTERN "test" EXCLUDE
    PATTERN "BoltUdf.h" # select header files
    PATTERN "boltExamples" EXCLUDE
    PATTERN "examples" EXCLUDE
  )

  install(TARGETS velox_backend
    ARCHIVE DESTINATION ${CMAKE_INSTALL_LIBDIR}
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR})

  install(TARGETS gluten
        DESTINATION ${CMAKE_INSTALL_LIBDIR})
endif()

