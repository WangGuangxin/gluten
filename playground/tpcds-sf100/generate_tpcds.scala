/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */

import com.databricks.spark.sql.perf.tpcds.TPCDSTables
import java.io.File

val scaleFactor = sys.env.getOrElse("TPCDS_SCALE_FACTOR", "100")
val dataDir = sys.env("TPCDS_CONTAINER_DATA_DIR")
val dsdgenDir = sys.env.getOrElse("TPCDS_KIT_DIR", "/opt/tpcds-kit/tools")
val generationPartitions =
  sys.env.getOrElse("TPCDS_GENERATION_PARTITIONS", "32").toInt
val resume = sys.env.getOrElse("TPCDS_RESUME", "true").toBoolean

val nonPartitionedTables = Seq(
  "call_center",
  "catalog_page",
  "customer",
  "customer_address",
  "customer_demographics",
  "date_dim",
  "household_demographics",
  "income_band",
  "item",
  "promotion",
  "reason",
  "ship_mode",
  "store",
  "time_dim",
  "warehouse",
  "web_page",
  "web_site")

val partitionedTables = Seq(
  "inventory",
  "web_returns",
  "catalog_returns",
  "store_returns",
  "web_sales",
  "catalog_sales",
  "store_sales")

val tables = new TPCDSTables(
  spark.sqlContext,
  dsdgenDir = dsdgenDir,
  scaleFactor = scaleFactor,
  useDoubleForDecimal = false,
  useStringForDate = false)

def isComplete(table: String): Boolean =
  new File(s"$dataDir/$table/_SUCCESS").isFile

def generate(table: String, partitions: Int): Unit = {
  if (resume && isComplete(table)) {
    println(s"TPCDS_GENERATE_SKIP table=$table reason=_SUCCESS")
  } else {
    println(s"TPCDS_GENERATE_START table=$table partitions=$partitions")
    tables.genData(
      location = dataDir,
      format = "parquet",
      overwrite = true,
      partitionTables = true,
      clusterByPartitionColumns = true,
      filterOutNullPartitionValues = false,
      tableFilter = table,
      numPartitions = partitions)
    require(isComplete(table), s"$table did not produce _SUCCESS")
    println(s"TPCDS_GENERATE_DONE table=$table")
  }
}

nonPartitionedTables.foreach(generate(_, math.min(generationPartitions, 8)))
partitionedTables.foreach(generate(_, generationPartitions))

println(s"TPCDS_GENERATE_COMPLETE scaleFactor=$scaleFactor dataDir=$dataDir")
System.exit(0)
