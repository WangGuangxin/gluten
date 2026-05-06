/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.gluten.substrait.rel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PaimonLocalFilesBuilder {

  /**
   * Creates a PaimonLocalFilesNode for the Hive-fallback path. Each file in the scan carries
   * per-file hive-style metadata (bucket, firstRowId, etc.) that the C++ side uses to construct
   * HiveConnectorSplits.
   */
  public static PaimonLocalFilesNode makePaimonLocalFiles(
      Integer index,
      List<String> paths,
      List<Long> starts,
      List<Long> lengths,
      List<Map<String, String>> partitionColumns,
      LocalFilesNode.ReadFileFormat fileFormat,
      List<String> preferredLocations,
      Map<String, String> properties,
      List<Integer> buckets,
      List<Long> firstRowIds,
      List<Long> maxSequenceNumbers,
      List<Integer> splitGroups,
      boolean useHiveSplit,
      List<String> primaryKeys,
      boolean allRawConvertible) {
    return new PaimonLocalFilesNode(
        index,
        paths,
        starts,
        lengths,
        partitionColumns,
        fileFormat,
        preferredLocations,
        properties,
        buckets,
        firstRowIds,
        maxSequenceNumbers,
        splitGroups,
        useHiveSplit,
        primaryKeys,
        allRawConvertible);
  }

  /**
   * Creates a PaimonLocalFilesNode for the native Paimon connector path. Each serialized split
   * becomes one FileOrFiles entry in the proto, carrying the split bytes in
   * PaimonReadOptions.serialized_split. The C++ side deserializes these directly into
   * PaimonConnectorSplits — no per-file paths or hive-style metadata needed.
   *
   * @param index partition index
   * @param fileFormat file format (parquet/orc)
   * @param preferredLocations preferred execution locations
   * @param serializedPaimonSplits serialized DataSplit bytes, one per split in this partition
   */
  public static PaimonLocalFilesNode makeNativePaimonLocalFiles(
      Integer index,
      LocalFilesNode.ReadFileFormat fileFormat,
      List<String> preferredLocations,
      List<byte[]> serializedPaimonSplits) {
    return new PaimonLocalFilesNode(
        index, fileFormat, preferredLocations, new HashMap<>(), serializedPaimonSplits);
  }
}
