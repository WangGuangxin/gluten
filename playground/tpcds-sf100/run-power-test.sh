#!/usr/bin/env bash

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
source "${ROOT_DIR}/config.env"

usage() {
  echo "Usage: $0 <velox|cudf|both> [q1,q14a,...] [--resume]" >&2
}

engine=${1:-}
case "${engine}" in
  velox|cudf|both) ;;
  *) usage; exit 2 ;;
esac
query_filter=${2:-}
resume_arg=${3:-}
if [[ "${query_filter}" == "--resume" ]]; then
  resume_arg=${query_filter}
  query_filter=""
fi
if [[ -n "${resume_arg}" && "${resume_arg}" != "--resume" ]]; then
  usage
  exit 2
fi

DATA_DIR="${ROOT_DIR}/${TPCDS_DATA_DIR}"
QUERY_DIR="${ROOT_DIR}/${TPCDS_QUERY_DIR}"
JAR_PATH="${ROOT_DIR}/${GLUTEN_JAR}"
test -d "${DATA_DIR}" || { echo "Missing data: ${DATA_DIR}" >&2; exit 1; }
test -d "${QUERY_DIR}" || { echo "Missing queries: ${QUERY_DIR}" >&2; exit 1; }
test -s "${JAR_PATH}" || {
  echo "Missing Gluten Spark 3.5 bundle: ${JAR_PATH}" >&2
  echo "Set GLUTEN_JAR in config.env to the GPU-enabled bundle." >&2
  exit 1
}
command -v docker >/dev/null || { echo "docker is required" >&2; exit 1; }
docker image inspect "${TPCDS_IMAGE}" >/dev/null 2>&1 || {
  echo "Image ${TPCDS_IMAGE} is missing; run ./build-image.sh first." >&2
  exit 1
}

run_one() {
  local selected_engine=$1
  local cudf_enabled=false
  local gpu_args=()
  if [[ "${selected_engine}" == "cudf" ]]; then
    cudf_enabled=true
    gpu_args=(--gpus all)
    docker run --rm --gpus all "${TPCDS_IMAGE}" nvidia-smi -L >/dev/null || {
      echo "The cuDF run requires a working NVIDIA Docker runtime and GPU." >&2
      return 1
    }
  fi

  local run_id=${TPCDS_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}
  local result_rel="results/${selected_engine}/${run_id}"
  local result_dir="${ROOT_DIR}/${result_rel}"
  local local_rel="${SPARK_LOCAL_DIR}/${selected_engine}"
  mkdir -p "${result_dir}" "${ROOT_DIR}/${local_rel}" "${ROOT_DIR}/logs"

  local runner_args=()
  [[ -n "${query_filter}" ]] && runner_args+=(--queries "${query_filter}")
  [[ "${resume_arg}" == "--resume" ]] && runner_args+=(--resume)

  echo "Starting ${selected_engine} power test; results: ${result_dir}"
  docker run --rm \
    "${gpu_args[@]}" \
    --user "$(id -u):$(id -g)" \
    -e HOME=/tmp \
    -e TPCDS_ENGINE="${selected_engine}" \
    -e TPCDS_CONTAINER_DATA_DIR="/benchmark/${TPCDS_DATA_DIR}" \
    -e TPCDS_CONTAINER_QUERY_DIR=/queries \
    -e TPCDS_CONTAINER_RESULT_DIR="/benchmark/${result_rel}" \
    -v "${ROOT_DIR}:/benchmark" \
    -v "${QUERY_DIR}:/queries:ro" \
    -v "${JAR_PATH}:/opt/gluten/gluten.jar:ro" \
    -w /benchmark \
    "${TPCDS_IMAGE}" \
    spark-submit \
      --master "${SPARK_MASTER}" \
      --driver-memory "${SPARK_DRIVER_MEMORY}" \
      --conf spark.plugins=org.apache.gluten.GlutenPlugin \
      --conf spark.driver.extraClassPath=/opt/gluten/gluten.jar \
      --conf spark.executor.extraClassPath=/opt/gluten/gluten.jar \
      --conf spark.memory.offHeap.enabled=true \
      --conf "spark.memory.offHeap.size=${SPARK_OFFHEAP_SIZE}" \
      --conf spark.shuffle.manager=org.apache.spark.shuffle.sort.ColumnarShuffleManager \
      --conf "spark.sql.shuffle.partitions=${TPCDS_SHUFFLE_PARTITIONS}" \
      --conf "spark.local.dir=/benchmark/${local_rel}" \
      --conf spark.sql.adaptive.enabled=true \
      --conf "spark.gluten.sql.columnar.cudf=${cudf_enabled}" \
      --conf spark.gluten.sql.columnar.backend.velox.cudf.allowCpuFallback=true \
      --conf "spark.gluten.sql.columnar.backend.velox.cudf.concurrentGpuTasks=${CUDF_CONCURRENT_GPU_TASKS}" \
      --conf "spark.gluten.sql.columnar.backend.velox.cudf.memoryPercent=${CUDF_MEMORY_PERCENT}" \
      --conf spark.gluten.sql.columnar.backend.velox.gpuAsyncShuffleReader.enabled="${cudf_enabled}" \
      --conf spark.gluten.debug.enabled.cudf="${cudf_enabled}" \
      /benchmark/power_test.py \
      "${runner_args[@]}" \
    2>&1 | tee "${ROOT_DIR}/logs/power-${selected_engine}-${run_id}.log"
}

if [[ "${engine}" == "both" ]]; then
  TPCDS_RUN_ID=${TPCDS_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}
  export TPCDS_RUN_ID
  run_one velox
  run_one cudf
else
  run_one "${engine}"
fi
