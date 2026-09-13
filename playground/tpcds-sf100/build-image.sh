#!/usr/bin/env bash

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck disable=SC1091
source "${ROOT_DIR}/config.env"

if ! command -v docker >/dev/null; then
  echo "docker is required to build ${TPCDS_IMAGE}" >&2
  exit 1
fi

for attempt in 1 2 3 4 5; do
  if docker build -t "${TPCDS_IMAGE}" "${ROOT_DIR}"; then
    break
  fi
  if ((attempt == 5)); then
    echo "Image build failed after ${attempt} attempts." >&2
    exit 1
  fi
  delay=$((4 << (attempt - 1)))
  echo "Image build attempt ${attempt} failed; retrying in ${delay}s." >&2
  sleep "${delay}"
done

docker run --rm "${TPCDS_IMAGE}" bash -lc '
  set -e
  test "$(/opt/spark/bin/spark-submit --version 2>&1 | grep -c "version 3.5.5")" -gt 0
  test -x /opt/tpcds-kit/tools/dsdgen
  test -s /opt/spark-sql-perf.jar
  echo "Spark, dsdgen, and spark-sql-perf are ready."
'
