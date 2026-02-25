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
 * Velox specific columnar batch helpers.
 *
 * <p>The common logic is hosted in {@link BackendColumnarBatchesBase}. This class only wires the
 * Velox specific JNI wrapper and comprehensive type name, and exposes the original static API
 * (toVeloxBatch/ensureVeloxBatch/compose/slice/repeatedThenCompose).
 */
public final class VeloxColumnarBatches extends BackendColumnarBatchesBase {

  public static final String COMPREHENSIVE_TYPE_VELOX = "velox";

  private static final VeloxColumnarBatches INSTANCE = new VeloxColumnarBatches();

  private VeloxColumnarBatches() {}

  @Override
  protected String comprehensiveType() {
    return COMPREHENSIVE_TYPE_VELOX;
  }

  @Override
  protected String toBatchActionName() {
    return "VeloxColumnarBatches#toVeloxBatch";
  }

  @Override
  protected String composeActionName() {
    return "VeloxColumnarBatches#compose";
  }

  @Override
  protected String sliceActionName() {
    return "VeloxColumnarBatches#sliceBatch";
  }

  @Override
  protected String repeatedThenComposeActionName() {
    return "VeloxColumnarBatches#repeatedThenCompose";
  }

  @Override
  protected long fromNative(Runtime runtime, long nativeHandle) {
    return VeloxColumnarBatchJniWrapper.create(runtime).from(nativeHandle);
  }

  @Override
  protected long composeNative(Runtime runtime, long[] nativeHandles) {
    return VeloxColumnarBatchJniWrapper.create(runtime).compose(nativeHandles);
  }

  @Override
  protected long sliceNative(Runtime runtime, long nativeHandle, int offset, int limit) {
    return VeloxColumnarBatchJniWrapper.create(runtime).slice(nativeHandle, offset, limit);
  }

  @Override
  protected long repeatedThenComposeNative(
      Runtime runtime, long repeatedBatchHandle, long nonRepeatedBatchHandle, int[] rowId2RowNums) {
    return VeloxColumnarBatchJniWrapper.create(runtime)
        .repeatedThenCompose(repeatedBatchHandle, nonRepeatedBatchHandle, rowId2RowNums);
  }

  private static VeloxColumnarBatches instance() {
    return INSTANCE;
  }

  private VeloxColumnarBatches self() {
    return this;
  }

  public static void checkVeloxBatch(ColumnarBatch batch) {
    instance().checkBackendBatch(batch);
  }

  public static ColumnarBatch toVeloxBatch(ColumnarBatch input) {
    return instance().toBackendBatch(input);
  }

  public static ColumnarBatch ensureVeloxBatch(ColumnarBatch input) {
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
