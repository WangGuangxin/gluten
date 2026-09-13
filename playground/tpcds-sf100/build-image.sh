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

docker build --progress=plain -t "${TPCDS_IMAGE}" "${ROOT_DIR}"
docker run --rm "${TPCDS_IMAGE}" bash -lc '
  set -e
  test "$(/opt/spark/bin/spark-submit --version 2>&1 | grep -c "version 3.5.5")" -gt 0
  test -x /opt/tpcds-kit/tools/dsdgen
  test -s /opt/spark-sql-perf.jar
  echo "Spark, dsdgen, and spark-sql-perf are ready."
'
