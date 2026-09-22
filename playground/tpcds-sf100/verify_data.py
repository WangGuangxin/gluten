#!/usr/bin/env python3

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.

import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

from pyspark.sql import SparkSession


TABLES = (
    "call_center",
    "catalog_page",
    "catalog_returns",
    "catalog_sales",
    "customer",
    "customer_address",
    "customer_demographics",
    "date_dim",
    "household_demographics",
    "income_band",
    "inventory",
    "item",
    "promotion",
    "reason",
    "ship_mode",
    "store",
    "store_returns",
    "store_sales",
    "time_dim",
    "warehouse",
    "web_page",
    "web_returns",
    "web_sales",
    "web_site",
)
PARTITIONED_TABLES = {
    "catalog_returns",
    "catalog_sales",
    "inventory",
    "store_returns",
    "store_sales",
    "web_returns",
    "web_sales",
}


def parquet_codec(spark: SparkSession, filename: Path) -> str:
    jvm = spark.sparkContext._jvm
    path = jvm.org.apache.hadoop.fs.Path(str(filename))
    hadoop_input = jvm.org.apache.parquet.hadoop.util.HadoopInputFile.fromPath(
        path, spark.sparkContext._jsc.hadoopConfiguration()
    )
    reader = jvm.org.apache.parquet.hadoop.ParquetFileReader.open(hadoop_input)
    try:
        blocks = reader.getFooter().getBlocks()
        if blocks.isEmpty() or blocks.get(0).getColumns().isEmpty():
            return "UNKNOWN"
        return str(blocks.get(0).getColumns().get(0).getCodec())
    finally:
        reader.close()


def main() -> int:
    data_dir = Path(os.environ["TPCDS_CONTAINER_DATA_DIR"])
    result_file = Path(
        os.environ.get(
            "TPCDS_VERIFY_RESULT", "/benchmark/results/data-verification.json"
        )
    )
    spark = SparkSession.builder.appName("TPCDS-SF100-Verify").getOrCreate()
    failures = []
    table_results = []

    try:
        for table in TABLES:
            table_dir = data_dir / table
            files = sorted(table_dir.rglob("*.parquet")) if table_dir.is_dir() else []
            partitioned = any(
                "=" in part
                for filename in files
                for part in filename.relative_to(table_dir).parts[:-1]
            )
            complete = (table_dir / "_SUCCESS").is_file()
            size_bytes = sum(filename.stat().st_size for filename in files)
            codec = parquet_codec(spark, files[0]) if files else "MISSING"
            readable = False
            if files:
                readable = spark.read.parquet(str(table_dir)).limit(1).count() == 1

            if not complete:
                failures.append(f"{table}: missing _SUCCESS")
            if not files:
                failures.append(f"{table}: no Parquet files")
            if codec != "ZSTD":
                failures.append(f"{table}: expected ZSTD, found {codec}")
            if table in PARTITIONED_TABLES and not partitioned:
                failures.append(f"{table}: no Hive-style partition directories")
            if files and not readable:
                failures.append(f"{table}: data is not readable")

            table_results.append(
                {
                    "table": table,
                    "complete": complete,
                    "parquet_files": len(files),
                    "size_bytes": size_bytes,
                    "codec": codec,
                    "hive_partitioned": partitioned,
                    "readable": readable,
                }
            )

        result = {
            "checked_at": datetime.now(timezone.utc).isoformat(),
            "data_dir": str(data_dir),
            "table_count": len(table_results),
            "total_bytes": sum(row["size_bytes"] for row in table_results),
            "failures": failures,
            "tables": table_results,
        }
        result_file.parent.mkdir(parents=True, exist_ok=True)
        result_file.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(result, indent=2))
        return 1 if failures else 0
    finally:
        spark.stop()


if __name__ == "__main__":
    sys.exit(main())
