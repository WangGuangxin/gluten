#!/usr/bin/env bash

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
source "${ROOT_DIR}/config.env"

command -v docker >/dev/null || {
  echo "docker is required" >&2
  exit 1
}
docker image inspect "${TPCDS_IMAGE}" >/dev/null 2>&1 || {
  echo "Image ${TPCDS_IMAGE} is missing; run ./build-image.sh first." >&2
  exit 1
}

DATA_DIR="${ROOT_DIR}/${TPCDS_DATA_DIR}"
LOCAL_DIR="${ROOT_DIR}/${SPARK_LOCAL_DIR}"
LOG_DIR="${ROOT_DIR}/logs"
mkdir -p "${DATA_DIR}" "${LOCAL_DIR}" "${LOG_DIR}"

available_kib=$(df -Pk "${DATA_DIR}" | awk 'NR == 2 {print $4}')
required_kib=$((TPCDS_MIN_FREE_GIB * 1024 * 1024))
if ((available_kib < required_kib)); then
  echo "Need at least ${TPCDS_MIN_FREE_GIB} GiB free; only $((available_kib / 1024 / 1024)) GiB is available." >&2
  exit 1
fi

timestamp=$(date -u +%Y%m%dT%H%M%SZ)
log_file="${LOG_DIR}/generate-sf${TPCDS_SCALE_FACTOR}-${timestamp}.log"

echo "Generating TPC-DS SF${TPCDS_SCALE_FACTOR} in ${DATA_DIR}"
echo "Log: ${log_file}"

docker run --rm \
  --user "$(id -u):$(id -g)" \
  -e HOME=/tmp \
  -e TPCDS_SCALE_FACTOR="${TPCDS_SCALE_FACTOR}" \
  -e TPCDS_CONTAINER_DATA_DIR="/benchmark/${TPCDS_DATA_DIR}" \
  -e TPCDS_GENERATION_PARTITIONS="${TPCDS_GENERATION_PARTITIONS}" \
  -e TPCDS_RESUME="${TPCDS_RESUME:-true}" \
  -v "${ROOT_DIR}:/benchmark" \
  -w /benchmark \
  "${TPCDS_IMAGE}" \
  bash -c '
    set -euo pipefail
    spark-shell \
      --master "'"${SPARK_MASTER}"'" \
      --driver-memory "'"${SPARK_DRIVER_MEMORY}"'" \
      --jars "${SPARK_SQL_PERF_JAR}" \
      --conf spark.sql.parquet.compression.codec=zstd \
      --conf spark.sql.shuffle.partitions="'"${TPCDS_SHUFFLE_PARTITIONS}"'" \
      --conf spark.sql.files.maxRecordsPerFile=5000000 \
      --conf spark.driver.maxResultSize=2g \
      --conf spark.local.dir="/benchmark/'"${SPARK_LOCAL_DIR}"'" \
      < /benchmark/generate_tpcds.scala
  ' 2>&1 | tee "${log_file}"

"${ROOT_DIR}/verify-data.sh"
