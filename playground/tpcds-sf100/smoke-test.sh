#!/usr/bin/env bash

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
run_id="smoke-$(date -u +%Y%m%dT%H%M%SZ)"

TPCDS_RUN_ID="${run_id}" "${ROOT_DIR}/run-power-test.sh" velox q1
TPCDS_RUN_ID="${run_id}" "${ROOT_DIR}/run-power-test.sh" cudf q1

echo "Velox and cuDF smoke tests passed. Run ID: ${run_id}"
