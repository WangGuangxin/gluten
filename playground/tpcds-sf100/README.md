<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements. See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0.
-->

# TPC-DS SF100 Parquet/ZSTD power test

This directory generates TPC-DS SF100 data and runs one sequential TPC-DS
query stream with Gluten's Velox CPU path or Velox+cuDF GPU path.

## Layout

- `data/sf100-parquet-zstd/`: generated data (ignored by Git)
- `results/<engine>/<run-id>/`: per-query plans, JSONL, CSV, and run summary
- `logs/`: generation and power-test logs
- `runtime/`: Spark shuffle and temporary files
- `config.env`: paths and resource settings

The seven fact tables (`inventory`, `catalog_returns`, `catalog_sales`,
`store_returns`, `store_sales`, `web_returns`, and `web_sales`) are written
using their standard TPC-DS partition columns. Their paths use Hive-style
`column=value` directories. All Parquet columns use ZSTD compression.

## Prerequisites

- Docker with the NVIDIA container runtime
- An NVIDIA driver compatible with the CUDA 13.1 runtime image
- At least 80 GiB free disk space; more space is recommended for shuffle
- The GPU-enabled Spark 3.5/Scala 2.12 Gluten bundle configured by
  `GLUTEN_JAR` in `config.env`

The runtime image is based on
`apache/gluten:centos-9-jdk17-cuda13.1-cudf` and contains Spark 3.5.5,
Databricks `tpcds-kit`, and `spark-sql-perf`.

## Build the runtime

```bash
cd /workspace/playground/tpcds-sf100
./build-image.sh
```

## Generate and verify SF100

```bash
./generate-data.sh
```

Generation is table-by-table. A rerun skips tables with a `_SUCCESS` marker.
To overwrite every table:

```bash
TPCDS_RESUME=false ./generate-data.sh
```

Run the metadata/readability check independently with:

```bash
./verify-data.sh
```

The verification report is written to `results/data-verification.json`.

## Run power tests

Run all 103 query variants once, sequentially:

```bash
./run-power-test.sh velox
./run-power-test.sh cudf
```

Run both modes sequentially:

```bash
./run-power-test.sh both
```

Run selected queries:

```bash
./run-power-test.sh velox q1,q6,q14a
./run-power-test.sh cudf q1,q6,q14a
```

Resume a named run:

```bash
TPCDS_RUN_ID=my-run ./run-power-test.sh velox --resume
TPCDS_RUN_ID=my-run ./run-power-test.sh cudf --resume
```

Each query executes its full physical plan and counts output rows without
collecting the result set into driver memory. A failure is recorded and the
remaining queries continue. The process exits nonzero if any query failed.

Both modes use the same GPU-enabled bundle:

- `velox`: `spark.gluten.sql.columnar.cudf=false`
- `cudf`: `spark.gluten.sql.columnar.cudf=true`, CPU fallback enabled, and
  GPU concurrency initially set to one

Resource settings target a four-core, 16 GiB host and a 23 GiB NVIDIA L4.
Adjust `config.env` for a different machine.

## Smoke test

After generation, validate one query in both modes:

```bash
./smoke-test.sh
```

Monitor GPU activity during a cuDF run from another terminal:

```bash
watch -n 1 nvidia-smi
```
