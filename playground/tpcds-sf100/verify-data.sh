#!/usr/bin/env bash

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
source "${ROOT_DIR}/config.env"

DATA_DIR="${ROOT_DIR}/${TPCDS_DATA_DIR}"
test -d "${DATA_DIR}" || {
  echo "TPC-DS data does not exist at ${DATA_DIR}" >&2
  exit 1
}

mkdir -p "${ROOT_DIR}/results" "${ROOT_DIR}/${SPARK_LOCAL_DIR}"

docker run --rm \
  --user "$(id -u):$(id -g)" \
  -e HOME=/tmp \
  -e TPCDS_CONTAINER_DATA_DIR="/benchmark/${TPCDS_DATA_DIR}" \
  -e TPCDS_VERIFY_RESULT=/benchmark/results/data-verification.json \
  -v "${ROOT_DIR}:/benchmark" \
  -w /benchmark \
  "${TPCDS_IMAGE}" \
  spark-submit \
    --master "${SPARK_MASTER}" \
    --driver-memory "${SPARK_DRIVER_MEMORY}" \
    --conf "spark.local.dir=/benchmark/${SPARK_LOCAL_DIR}" \
    /benchmark/verify_data.py
