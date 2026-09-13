# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.

FROM apache/gluten:centos-9-jdk17-cuda13.1-cudf

ARG SPARK_VERSION=3.5.5
ARG TPCDS_KIT_COMMIT=1b7fb7529edae091684201fab142d956d6afd881
ARG SPARK_SQL_PERF_COMMIT=17f7b1b898a6d22c10798ad3c447687de35e31df

RUN dnf install -y bison byacc flex python3-pip which \
 && dnf clean all

RUN python3 -m pip install --no-cache-dir "pyspark==${SPARK_VERSION}" \
 && ln -s "$(python3 -c 'import pathlib, pyspark; print(pathlib.Path(pyspark.__file__).parent)')" /opt/spark

RUN git clone https://github.com/databricks/tpcds-kit.git /opt/tpcds-kit \
 && cd /opt/tpcds-kit \
 && git checkout "${TPCDS_KIT_COMMIT}" \
 && make -C tools OS=LINUX

RUN git clone https://github.com/KubedAI/spark-sql-perf.git /opt/spark-sql-perf \
 && cd /opt/spark-sql-perf \
 && git checkout "${SPARK_SQL_PERF_COMMIT}" \
 && mkdir -p /tmp/perf-src/com/databricks/spark/sql/perf/tpcds /tmp/perf-classes \
 && cp src/main/scala/com/databricks/spark/sql/perf/Tables.scala \
      /tmp/perf-src/com/databricks/spark/sql/perf/Tables.scala \
 && cp src/main/scala/com/databricks/spark/sql/perf/tpcds/TPCDSTables.scala \
      /tmp/perf-src/com/databricks/spark/sql/perf/tpcds/TPCDSTables.scala \
 && sed -i 's/{BlockingLineStream, DataGenerator, Table, Tables}/{BlockingLineStream, DataGenerator, Tables}/' \
      /tmp/perf-src/com/databricks/spark/sql/perf/tpcds/TPCDSTables.scala \
 && SPARK_CP=$(printf '%s:' /opt/spark/jars/*.jar) \
 && SPARK_CP=${SPARK_CP%:} \
 && java -cp "${SPARK_CP}" scala.tools.nsc.Main \
      -classpath "${SPARK_CP}" \
      -d /tmp/perf-classes \
      /tmp/perf-src/com/databricks/spark/sql/perf/Tables.scala \
      /tmp/perf-src/com/databricks/spark/sql/perf/tpcds/TPCDSTables.scala \
 && jar cf /opt/spark-sql-perf.jar -C /tmp/perf-classes . \
 && rm -rf /tmp/perf-src /tmp/perf-classes /opt/spark-sql-perf

ENV SPARK_HOME=/opt/spark
ENV PATH=/opt/spark/bin:${PATH}
ENV TPCDS_KIT_DIR=/opt/tpcds-kit/tools
ENV SPARK_SQL_PERF_JAR=/opt/spark-sql-perf.jar

WORKDIR /benchmark
