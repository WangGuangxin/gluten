#!/usr/bin/env python3

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.

import argparse
import csv
import json
import os
import re
import sys
import time
import traceback
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


def query_sort_key(path: Path):
    match = re.fullmatch(r"q(\d+)([ab]?)\.sql", path.name)
    if not match:
        return (sys.maxsize, path.name)
    suffix = {"": 0, "a": 1, "b": 2}[match.group(2)]
    return (int(match.group(1)), suffix)


def read_query(path: Path) -> str:
    lines = [
        line for line in path.read_text(encoding="utf-8").splitlines()
        if not line.lstrip().startswith("--")
    ]
    return "\n".join(lines).strip().rstrip(";")


def write_csv(path: Path, records):
    columns = (
        "query",
        "engine",
        "status",
        "elapsed_seconds",
        "output_rows",
        "started_at",
        "finished_at",
        "error",
    )
    temporary = path.with_suffix(".tmp")
    with temporary.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns)
        writer.writeheader()
        for record in records:
            writer.writerow({column: record.get(column, "") for column in columns})
    temporary.replace(path)


def parse_args():
    parser = argparse.ArgumentParser(description="Run one TPC-DS power-test stream")
    parser.add_argument(
        "--queries",
        help="Comma-separated query names, for example q1,q14a (default: all 103)",
    )
    parser.add_argument(
        "--resume",
        action="store_true",
        help="Skip successful queries already present in results.jsonl",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    engine = os.environ["TPCDS_ENGINE"]
    data_dir = Path(os.environ["TPCDS_CONTAINER_DATA_DIR"])
    query_dir = Path(os.environ["TPCDS_CONTAINER_QUERY_DIR"])
    result_dir = Path(os.environ["TPCDS_CONTAINER_RESULT_DIR"])
    result_dir.mkdir(parents=True, exist_ok=True)
    (result_dir / "plans").mkdir(exist_ok=True)

    query_paths = sorted(query_dir.glob("q*.sql"), key=query_sort_key)
    if args.queries:
        selected = {
            name if name.endswith(".sql") else f"{name}.sql"
            for name in args.queries.split(",")
        }
        query_paths = [path for path in query_paths if path.name in selected]
        missing = selected.difference(path.name for path in query_paths)
        if missing:
            raise ValueError(f"Unknown queries: {sorted(missing)}")
    if not query_paths:
        raise ValueError(f"No TPC-DS queries found in {query_dir}")

    jsonl_path = result_dir / "results.jsonl"
    records = []
    successful = set()
    if args.resume and jsonl_path.exists():
        for line in jsonl_path.read_text(encoding="utf-8").splitlines():
            record = json.loads(line)
            records.append(record)
            if record["status"] == "success":
                successful.add(record["query"])

    spark = (
        SparkSession.builder
        .appName(f"TPCDS-SF100-Power-{engine}")
        .getOrCreate()
    )
    failures = 0
    try:
        for table in TABLES:
            path = data_dir / table
            if not path.is_dir():
                raise FileNotFoundError(f"Missing TPC-DS table: {path}")
            (
                spark.read
                .option("basePath", str(path))
                .parquet(str(path))
                .createOrReplaceTempView(table)
            )

        for query_path in query_paths:
            query_name = query_path.stem
            if query_name in successful:
                print(f"TPCDS_QUERY_SKIP query={query_name} reason=resume")
                continue

            started_at = datetime.now(timezone.utc)
            started = time.monotonic()
            record = {
                "query": query_name,
                "engine": engine,
                "started_at": started_at.isoformat(),
            }
            spark.sparkContext.setJobGroup(query_name, f"TPC-DS {query_name}")
            print(f"TPCDS_QUERY_START query={query_name} engine={engine}", flush=True)
            try:
                frame = spark.sql(read_query(query_path))
                output_rows = frame._jdf.queryExecution().toRdd().count()
                plan = frame._jdf.queryExecution().executedPlan().toString()
                (result_dir / "plans" / f"{query_name}.txt").write_text(
                    plan + "\n", encoding="utf-8"
                )
                record.update(status="success", output_rows=int(output_rows), error="")
            except Exception as error:  # Continue the stream and report every failure.
                failures += 1
                record.update(
                    status="failed",
                    output_rows="",
                    error=f"{type(error).__name__}: {error}",
                    traceback=traceback.format_exc(),
                )
            finally:
                record["elapsed_seconds"] = round(time.monotonic() - started, 3)
                record["finished_at"] = datetime.now(timezone.utc).isoformat()
                records.append(record)
                with jsonl_path.open("a", encoding="utf-8") as handle:
                    handle.write(json.dumps(record, ensure_ascii=False) + "\n")
                write_csv(result_dir / "summary.csv", records)
                print(
                    "TPCDS_QUERY_DONE "
                    f"query={query_name} status={record['status']} "
                    f"elapsed_seconds={record['elapsed_seconds']}",
                    flush=True,
                )

        summary = {
            "engine": engine,
            "queries_requested": len(query_paths),
            "queries_skipped": len(successful.intersection(path.stem for path in query_paths)),
            "failures": failures,
            "finished_at": datetime.now(timezone.utc).isoformat(),
        }
        (result_dir / "run-summary.json").write_text(
            json.dumps(summary, indent=2) + "\n", encoding="utf-8"
        )
        print(json.dumps(summary, indent=2))
        return 1 if failures else 0
    finally:
        spark.stop()


if __name__ == "__main__":
    sys.exit(main())
