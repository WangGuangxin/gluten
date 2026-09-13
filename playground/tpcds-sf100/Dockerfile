# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.

FROM apache/gluten:centos-9-jdk17-cuda13.1-cudf

ARG SPARK_VERSION=3.5.5
ARG TPCDS_KIT_COMMIT=1b7fb7529edae091684201fab142d956d6afd881
ARG SPARK_SQL_PERF_COMMIT=17f7b1b898a6d22c10798ad3c447687de35e31df

RUN dnf install -y bison byacc flex gzip tar which \
 && dnf clean all

RUN cd /tmp \
 && curl -fLO "https://archive.apache.org/dist/spark/spark-${SPARK_VERSION}/spark-${SPARK_VERSION}-bin-hadoop3.tgz" \
 && curl -fLO "https://archive.apache.org/dist/spark/spark-${SPARK_VERSION}/spark-${SPARK_VERSION}-bin-hadoop3.tgz.sha512" \
 && sha512sum -c "spark-${SPARK_VERSION}-bin-hadoop3.tgz.sha512" \
 && tar -xzf "spark-${SPARK_VERSION}-bin-hadoop3.tgz" -C /opt \
 && ln -s "/opt/spark-${SPARK_VERSION}-bin-hadoop3" /opt/spark \
 && rm "spark-${SPARK_VERSION}-bin-hadoop3.tgz" "spark-${SPARK_VERSION}-bin-hadoop3.tgz.sha512"

RUN git clone https://github.com/databricks/tpcds-kit.git /opt/tpcds-kit \
 && cd /opt/tpcds-kit \
 && git checkout "${TPCDS_KIT_COMMIT}" \
 && make -C tools OS=LINUX

RUN curl -fsSL https://raw.githubusercontent.com/paulp/sbt-extras/master/sbt \
      -o /usr/local/bin/sbt \
 && chmod +x /usr/local/bin/sbt \
 && git clone https://github.com/KubedAI/spark-sql-perf.git /opt/spark-sql-perf \
 && cd /opt/spark-sql-perf \
 && git checkout "${SPARK_SQL_PERF_COMMIT}" \
 && sbt -Dspark.version="${SPARK_VERSION}" package \
 && cp target/scala-2.12/spark-sql-perf_2.12-*.jar /opt/spark-sql-perf.jar \
 && rm -rf /root/.ivy2/cache /root/.sbt /root/.cache/coursier

ENV SPARK_HOME=/opt/spark
ENV PATH=/opt/spark/bin:${PATH}
ENV TPCDS_KIT_DIR=/opt/tpcds-kit/tools
ENV SPARK_SQL_PERF_JAR=/opt/spark-sql-perf.jar

WORKDIR /benchmark
