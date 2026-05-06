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

import org.apache.gluten.config.GlutenConfig;

import io.substrait.proto.ReadRel;
import io.substrait.proto.ReadRel.LocalFiles.FileOrFiles.OrcReadOptions;
import io.substrait.proto.ReadRel.LocalFiles.FileOrFiles.PaimonReadOptions;
import io.substrait.proto.ReadRel.LocalFiles.FileOrFiles.ParquetReadOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

public class PaimonLocalFilesNode extends LocalFilesNode {
  private final List<Integer> buckets;
  private final List<Long> firstRowIds;
  private final List<Long> maxSequenceNumbers;
  private final List<Integer> splitGroups;
  private final boolean useHiveSplit;
  private final List<String> primaryKeys;
  private final boolean allRawConvertible;

  // Serialized Paimon splits from Java DataSplit.serialize()
  // Used by the native Paimon connector to deserialize splits directly.
  // Populated only when useHiveSplit == false (native connector path).
  // One entry per DataSplit in the partition — emitted as one FileOrFiles each.
  private final List<byte[]> serializedPaimonSplits;

  /** Whether this node uses the native Paimon connector (serialized splits) vs Hive fallback. */
  private final boolean isNativePaimon;

  /**
   * Hive-fallback constructor: creates a PaimonLocalFilesNode with per-file hive-style metadata
   * (paths, bucket, firstRowId, etc.). Used when the native Paimon connector is not available.
   */
  public PaimonLocalFilesNode(
      Integer index,
      List<String> paths,
      List<Long> starts,
      List<Long> lengths,
      List<Map<String, String>> partitionColumns,
      ReadFileFormat fileFormat,
      List<String> preferredLocations,
      Map<String, String> properties,
      List<Integer> buckets,
      List<Long> firstRowIds,
      List<Long> maxSequenceNumbers,
      List<Integer> splitGroups,
      boolean useHiveSplit,
      List<String> primaryKeys,
      boolean allRawConvertible) {
    super(
        index,
        paths,
        starts,
        lengths,
        new ArrayList<>(),
        new ArrayList<>(),
        partitionColumns,
        new ArrayList<>(),
        fileFormat,
        preferredLocations,
        properties,
        new ArrayList<>());
    this.buckets = buckets;
    this.firstRowIds = firstRowIds;
    this.maxSequenceNumbers = maxSequenceNumbers;
    this.splitGroups = splitGroups;
    this.useHiveSplit = useHiveSplit;
    this.primaryKeys = primaryKeys;
    this.allRawConvertible = allRawConvertible;
    this.isNativePaimon = false;
    this.serializedPaimonSplits = null;
  }

  /**
   * Native Paimon connector constructor: creates a PaimonLocalFilesNode carrying serialized
   * DataSplit bytes. Each entry in serializedPaimonSplits becomes one FileOrFiles in the proto,
   * with the serialized bytes stored in PaimonReadOptions.serialized_split. No per-file paths or
   * hive-style metadata is needed — the C++ side deserializes these directly into
   * PaimonConnectorSplits.
   *
   * @param index partition index
   * @param fileFormat file format (parquet/orc)
   * @param preferredLocations preferred execution locations
   * @param properties table properties
   * @param serializedPaimonSplits serialized DataSplit bytes, one per split in this partition
   */
  public PaimonLocalFilesNode(
      Integer index,
      ReadFileFormat fileFormat,
      List<String> preferredLocations,
      Map<String, String> properties,
      List<byte[]> serializedPaimonSplits) {
    super(
        index,
        IntStream.range(0, serializedPaimonSplits.size())
            .mapToObj(i -> "paimon-native-split-" + i)
            .collect(java.util.stream.Collectors.toList()),
        LongStream.range(0, serializedPaimonSplits.size()).boxed().collect(Collectors.toList()),
        LongStream.range(0, serializedPaimonSplits.size()).boxed().collect(Collectors.toList()),
        new ArrayList<>(),
        new ArrayList<>(),
        IntStream.range(0, serializedPaimonSplits.size())
            .mapToObj(unused -> new HashMap<String, String>())
            .collect(Collectors.toList()),
        new ArrayList<>(),
        fileFormat,
        preferredLocations,
        properties,
        new ArrayList<>());
    this.buckets = new ArrayList<>();
    this.firstRowIds = new ArrayList<>();
    this.maxSequenceNumbers = new ArrayList<>();
    this.splitGroups = new ArrayList<>();
    this.useHiveSplit = false;
    this.primaryKeys = new ArrayList<>();
    this.allRawConvertible = true;
    this.isNativePaimon = true;
    this.serializedPaimonSplits = serializedPaimonSplits;
  }

  @Override
  protected void processFileBuilder(ReadRel.LocalFiles.FileOrFiles.Builder fileBuilder, int index) {
    PaimonReadOptions.Builder paimonBuilder = PaimonReadOptions.newBuilder();

    if (isNativePaimon) {
      // Native path: embed serialized split bytes directly. C++ will deserialize into
      // PaimonConnectorSplit.
      if (serializedPaimonSplits != null && index < serializedPaimonSplits.size()) {
        paimonBuilder.setSerializedSplit(
            com.google.protobuf.ByteString.copyFrom(serializedPaimonSplits.get(index)));
      }
      // Set raw_convertible = true so C++ knows this is a native-serialized split
      paimonBuilder.setRawConvertible(true);
    } else {
      // Hive-fallback path: per-file paimon metadata
      Integer bucket = buckets.get(index);
      Long firstRowId = firstRowIds.get(index);
      Long maxSequenceNumber = maxSequenceNumbers.get(index);
      Integer splitGroup = splitGroups.get(index);
      paimonBuilder.setBucket(bucket);
      paimonBuilder.setFirstRowId(firstRowId);
      paimonBuilder.setMaxSequenceNumber(maxSequenceNumber);
      paimonBuilder.setSplitGroup(splitGroup);
      paimonBuilder.setUseHiveSplit(useHiveSplit);
      paimonBuilder.addAllPrimaryKeys(primaryKeys);
      paimonBuilder.setRawConvertible(allRawConvertible);
    }

    switch (fileFormat) {
      case ParquetReadFormat:
        ParquetReadOptions parquetReadOptions =
            ParquetReadOptions.newBuilder()
                .setEnableRowGroupMaxminIndex(GlutenConfig.get().enableParquetRowGroupMaxMinIndex())
                .build();
        paimonBuilder.setParquet(parquetReadOptions);
        break;
      case OrcReadFormat:
        paimonBuilder.setOrc(OrcReadOptions.newBuilder().build());
        break;
      default:
        throw new UnsupportedOperationException(
            "Unsupported file format " + fileFormat.name() + " for paimon data file.");
    }
    fileBuilder.setPaimon(paimonBuilder);
  }

  public boolean isNativePaimon() {
    return isNativePaimon;
  }

  public List<byte[]> getSerializedPaimonSplits() {
    return serializedPaimonSplits;
  }
}
