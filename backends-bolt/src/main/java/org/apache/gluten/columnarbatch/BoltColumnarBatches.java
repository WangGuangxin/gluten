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
package org.apache.gluten.columnarbatch;

import org.apache.gluten.runtime.Runtime;

import org.apache.spark.sql.vectorized.ColumnarBatch;

/**
 * Bolt specific columnar batch helpers.
 *
 * <p>The common logic is hosted in {@link BackendColumnarBatchesBase}. This class only wires the
 * Bolt specific JNI wrapper and comprehensive type name, and exposes the original static API
 * (toBoltBatch/ensureBoltBatch/compose/slice/repeatedThenCompose).
 */
public final class BoltColumnarBatches extends BackendColumnarBatchesBase {

  public static final String COMPREHENSIVE_TYPE_BOLT = "bolt";

  private static final BoltColumnarBatches INSTANCE = new BoltColumnarBatches();

  private BoltColumnarBatches() {}

  @Override
  protected String comprehensiveType() {
    return COMPREHENSIVE_TYPE_BOLT;
  }

  @Override
  protected String toBatchActionName() {
    return "BoltColumnarBatches#toBoltBatch";
  }

  @Override
  protected String composeActionName() {
    return "BoltColumnarBatches#compose";
  }

  @Override
  protected String sliceActionName() {
    return "BoltColumnarBatches#sliceBatch";
  }

  @Override
  protected String repeatedThenComposeActionName() {
    return "BoltColumnarBatches#repeatedThenCompose";
  }

  @Override
  protected long fromNative(Runtime runtime, long nativeHandle) {
    return BoltColumnarBatchJniWrapper.create(runtime).from(nativeHandle);
  }

  @Override
  protected long composeNative(Runtime runtime, long[] nativeHandles) {
    return BoltColumnarBatchJniWrapper.create(runtime).compose(nativeHandles);
  }

  @Override
  protected long sliceNative(Runtime runtime, long nativeHandle, int offset, int limit) {
    return BoltColumnarBatchJniWrapper.create(runtime).slice(nativeHandle, offset, limit);
  }

  @Override
  protected long repeatedThenComposeNative(
      Runtime runtime, long repeatedBatchHandle, long nonRepeatedBatchHandle, int[] rowId2RowNums) {
    return BoltColumnarBatchJniWrapper.create(runtime)
        .repeatedThenCompose(repeatedBatchHandle, nonRepeatedBatchHandle, rowId2RowNums);
  }

  private static BoltColumnarBatches instance() {
    return INSTANCE;
  }

  public static void checkBoltBatch(ColumnarBatch batch) {
    instance().checkBackendBatch(batch);
  }

  public static ColumnarBatch toBoltBatch(ColumnarBatch input) {
    return instance().toBackendBatch(input);
  }

  public static ColumnarBatch ensureBoltBatch(ColumnarBatch input) {
    return instance().ensureBackendBatch(input);
  }

  public static ColumnarBatch compose(ColumnarBatch... batches) {
    return instance().composeBatches(batches);
  }

  public static ColumnarBatch slice(ColumnarBatch batch, int offset, int limit) {
    return instance().sliceBatch(batch, offset, limit);
  }

  public static ColumnarBatch repeatedThenCompose(
      ColumnarBatch batch1, ColumnarBatch batch2, int[] rowId2RowNums) {
    return instance().repeatedThenComposeBatches(batch1, batch2, rowId2RowNums);
  }
}
