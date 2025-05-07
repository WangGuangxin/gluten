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

cmake_minimum_required(VERSION 3.25)

project(gluten)

# include(ExternalProject)
include(FindPkgConfig)
include(GNUInstallDirs)
include(CheckCXXCompilerFlag)

set(CMAKE_CXX_STANDARD 17)

set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=native")

find_package(Boost CONFIG REQUIRED)

set(source_root_directory ${CMAKE_CURRENT_SOURCE_DIR})

if (CMAKE_VERSION VERSION_GREATER_EQUAL "3.24.0")
  cmake_policy(SET CMP0135 NEW)
endif()

set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/CMake" ${CMAKE_MODULE_PATH})

set(SUBSTRAIT_PROTO_SRC_DIR ${GLUTEN_HOME}/gluten-core/src/main/resources/substrait/proto)
message(STATUS "Set Substrait Proto Directory in ${SUBSTRAIT_PROTO_SRC_DIR}")

set(GLUTEN_PROTO_SRC_DIR ${GLUTEN_HOME}/gluten-core/src/main/resources/org/apache/gluten/proto)
message(STATUS "Set Gluten Proto Directory in ${GLUTEN_PROTO_SRC_DIR}")

if(NOT CMAKE_C_COMPILER_LAUNCHER AND NOT CMAKE_CXX_COMPILER_LAUNCHER)

  find_program(CCACHE_FOUND ccache)

  if(CCACHE_FOUND)
    message(STATUS "Using ccache: ${CCACHE_FOUND}")
    set(CMAKE_C_COMPILER_LAUNCHER ${CCACHE_FOUND})
    set(CMAKE_CXX_COMPILER_LAUNCHER ${CCACHE_FOUND})
    # keep comments as they might matter to the compiler
    set(ENV{CCACHE_COMMENTS} "1")
  endif()
endif()

if(USE_AVX512)
  # Only enable additional instruction sets if they are supported
  message(STATUS "System processor: ${CMAKE_SYSTEM_PROCESSOR}")
  if (CMAKE_SYSTEM_PROCESSOR MATCHES "(x86)|(X86)|(amd64)|(AMD64)")
    set(AVX512_FLAG "-march=skylake-avx512")
    check_cxx_compiler_flag(${AVX512_FLAG} CXX_SUPPORTS_AVX512)
    if(NOT CXX_SUPPORTS_AVX512)
      message(FATAL_ERROR "AVX512 required but compiler doesn't support it.")
    endif()
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${AVX512_FLAG}")
    add_definitions(-DCOLUMNAR_PLUGIN_USE_AVX512)
  endif ()
endif()


message(STATUS "Set Proto Directory in ${proto_directory}")
find_package(Protobuf CONFIG REQUIRED)
set(proto_directory ${CMAKE_CURRENT_SOURCE_DIR}/../../gluten-core/src/main/resources/substrait/proto)
set(substrait_proto_directory ${proto_directory}/substrait)

file(GLOB SUBSTRAIT_PROTO_FILES ${proto_directory}/*.proto
                      ${substrait_proto_directory}/*.proto
                      ${substrait_proto_directory}/extensions/*.proto)
set(SUBSTRAIT_PROTO_SRCS)
set(SUBSTRAIT_PROTO_HDRS)
set(PROTOBUF_GENERATE_CPP_APPEND_PATH OFF CACHE BOOL "disable APPEND_PATH")
set(Protobuf_IMPORT_DIRS ${proto_directory})

protobuf_generate_cpp(SUBSTRAIT_PROTO_SRCS SUBSTRAIT_PROTO_HDRS ${SUBSTRAIT_PROTO_FILES})
# set(_outvar)
# protobuf_generate(APPEND_PATH OFF
#   LANGUAGE cpp
#   OUT_VAR _outvar
#   IMPORT_DIRS  ${proto_directory}
#   #PROTOC_OUT_DIR ${CMAKE_CURRENT_BINARY_DIR}/substrait
#   PROTOS ${PROTO_FILES}
# )

set(GLUTEN_IO_PROTO_SRCS)
set(GLUTEN_IO_PROTO_HDRS)
set(GLUTEN_IO_PROTO_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../gluten-core/src/main/resources/org/apache/gluten/proto)
set(Protobuf_IMPORT_DIRS ${GLUTEN_IO_PROTO_DIR})
file(GLOB GLUTEN_IO_PROTO_FILES ${GLUTEN_IO_PROTO_DIR}/*.proto)
protobuf_generate_cpp(GLUTEN_IO_PROTO_SRCS GLUTEN_IO_PROTO_HDRS ${GLUTEN_IO_PROTO_FILES})


# ? to be removed
add_definitions(-DNATIVESQL_SRC_PATH="${root_directory}/releases")

set(CODEGEN_HEADERS third_party/)
set(THIRD_PARTY_INCLUDE ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
include_directories(${CMAKE_CURRENT_SOURCE_DIR} ${THIRD_PARTY_INCLUDE})
file(MAKE_DIRECTORY ${root_directory}/releases/include)
file(MAKE_DIRECTORY ${root_directory}/releases/include/codegen/common/)
file(MAKE_DIRECTORY ${root_directory}/releases/include/codegen/third_party/)
file(MAKE_DIRECTORY ${root_directory}/releases/include/codegen/precompile/)
file(MAKE_DIRECTORY ${root_directory}/releases/include/codegen/utils/)
file(MAKE_DIRECTORY ${root_directory}/releases/include/codegen/arrow_compute/ext/)
file(COPY utils/metrics.h DESTINATION ${root_directory}/releases/include/common/)


set(SPARK_COLUMNAR_PLUGIN_SRCS
        ${SUBSTRAIT_PROTO_SRCS}
        ${SUBSTRAIT_PROTO_HDRS}
        ${GLUTEN_IO_PROTO_SRCS}
        ${GLUTEN_IO_PROTO_HDRS}
        compute/Runtime.cc
        compute/ProtobufUtils.cc
        compute/ResultIterator.cc
        config/GlutenConfig.cc
        jni/JniWrapper.cc
        memory/AllocationListener.cc
        memory/MemoryAllocator.cc
        memory/ArrowMemoryPool.cc
        memory/ColumnarBatch.cc
        operators/writer/ArrowWriter.cc
        shuffle/FallbackRangePartitioner.cc
        shuffle/HashPartitioner.cc
        shuffle/LocalPartitionWriter.cc
        shuffle/Options.cc
        shuffle/Partitioner.cc
        shuffle/Partitioning.cc
        shuffle/Payload.cc
        shuffle/rss/RssPartitionWriter.cc
        shuffle/RoundRobinPartitioner.cc
        shuffle/ShuffleMemoryPool.cc
        shuffle/ShuffleReader.cc
        shuffle/SinglePartitioner.cc
        shuffle/Spill.cc
        shuffle/Utils.cc
        utils/Compression.cc
        utils/StringUtil.cc
        utils/ObjectStore.cc
        jni/JniError.cc
        jni/JniCommon.cc)

file(MAKE_DIRECTORY ${root_directory}/releases)

add_library(gluten STATIC ${SPARK_COLUMNAR_PLUGIN_SRCS})

if(ENABLE_GLUTEN_VCPKG)
  # Hide symbols of some static dependencies. Otherwise, if such dependencies are already
  # statically linked to libvelox.so, a runtime error will be reported: xxx is being linked
  # both statically and dynamically.
  target_link_options(gluten PRIVATE -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/symbols.map)
endif()

if(CMAKE_CXX_COMPILER_VERSION VERSION_LESS 9.0)
  execute_process(COMMAND ${CMAKE_C_COMPILER} -print-file-name=libstdc++fs.a
    RESULT_VARIABLE LIBSTDCXXFS_STATIC_RESULT
    OUTPUT_VARIABLE LIBSTDCXXFS_STATIC_PATH
    OUTPUT_STRIP_TRAILING_WHITESPACE)
  if (LIBSTDCXXFS_STATIC_RESULT EQUAL 0 AND EXISTS "${LIBSTDCXXFS_STATIC_PATH}")
    message(STATUS "libstdc++fs.a found at: ${LIBSTDCXXFS_STATIC_PATH}")
    target_link_libraries(gluten PRIVATE ${LIBSTDCXXFS_STATIC_PATH})
  else()
    find_library(LIBSTDCXXFS stdc++fs REQUIRED)
    target_link_libraries(gluten PUBLIC ${LIBSTDCXXFS})
  endif()
endif()

find_package(velox CONFIG)

# to include generated *.pb.h, and expose these as PUBLIC
target_include_directories(gluten PUBLIC ${CMAKE_CURRENT_BINARY_DIR} ${velox_INCLUDE_DIRS})

target_link_libraries(gluten PUBLIC protobuf::libprotobuf)

if(ENABLE_HBM)
  include(BuildMemkind)
  target_sources(gluten PRIVATE memory/HbwAllocator.cc)
  target_link_libraries(gluten PRIVATE memkind::memkind)
  add_definitions(-DGLUTEN_ENABLE_HBM)
endif()

if(ENABLE_QAT)
  include(BuildQATzip)
  include(BuildQATZstd)
  target_sources(gluten PRIVATE utils/qat/QatCodec.cc)
  target_include_directories(gluten PUBLIC ${QATZIP_INCLUDE_DIR} ${QATZSTD_INCLUDE_DIR})
  target_link_libraries(gluten PUBLIC qatzip::qatzip qatzstd::qatzstd)
endif()

if(ENABLE_IAA)
  include(BuildQpl)
  target_include_directories(gluten PUBLIC ${QPL_INCLUDE_DIR})
  target_sources(gluten PRIVATE utils/qpl/qpl_job_pool.cc utils/qpl/qpl_codec.cc)
  target_link_libraries(gluten PUBLIC qpl::qpl)
endif()

target_include_directories(gluten PUBLIC ${CMAKE_SYSTEM_INCLUDE_PATH} ${JNI_INCLUDE_DIRS} ${CMAKE_CURRENT_SOURCE_DIR} ${PROTO_OUTPUT_DIR} ${PROTOBUF_INCLUDE})
set_target_properties(gluten PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY ${root_directory}/releases)


if(BUILD_TESTS)
  add_subdirectory(tests)
endif()

if(BUILD_BENCHMARKS)
  add_subdirectory(benchmarks)
endif()

add_subdirectory(nativeLoader)

find_package(Arrow CONFIG REQUIRED)

# operators is using folly..
find_package(folly CONFIG REQUIRED)

target_link_libraries(gluten
    PUBLIC
    Folly::folly
    arrow::arrow arrow::libparquet)
