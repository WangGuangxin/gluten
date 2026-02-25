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

import org.apache.gluten.backendsapi.BackendsApiManager;
import org.apache.gluten.memory.arrow.alloc.ArrowBufferAllocators;
import org.apache.gluten.runtime.Runtime;
import org.apache.gluten.runtime.Runtimes;

import com.google.common.base.Preconditions;
import org.apache.spark.sql.vectorized.ColumnarBatch;
import org.apache.spark.sql.vectorized.SparkColumnarBatchUtil;

import java.util.Arrays;
import java.util.Objects;

/**
 * Backend-agnostic base class that hosts the common logic of Velox/Bolt columnar batch helpers.
 * Concrete backends only need to provide the comprehensive type and the JNI bridging methods.
 */
public abstract class BackendColumnarBatchesBase {

  /** Comprehensive batch type string reported by the backend (e.g. "velox" or "bolt"). */
  protected abstract String comprehensiveType();

  /** Action name used when creating runtime for to-backend conversion. */
  protected abstract String toBatchActionName();

  /** Action name used when creating runtime for compose. */
  protected abstract String composeActionName();

  /** Action name used when creating runtime for slice. */
  protected abstract String sliceActionName();

  /** Action name used when creating runtime for repeatedThenCompose. */
  protected abstract String repeatedThenComposeActionName();

  /** JNI entry for converting a generic native handle to backend specific batch. */
  protected abstract long fromNative(Runtime runtime, long nativeHandle);

  /** JNI entry for composing multiple backend batches. */
  protected abstract long composeNative(Runtime runtime, long[] nativeHandles);

  /** JNI entry for slicing a backend batch. */
  protected abstract long sliceNative(Runtime runtime, long nativeHandle, int offset, int limit);

  /** JNI entry for repeat+compose on two backend batches. */
  protected abstract long repeatedThenComposeNative(
      Runtime runtime, long repeatedBatchHandle, long nonRepeatedBatchHandle, int[] rowId2RowNums);

  protected boolean isBackendBatch(ColumnarBatch batch) {
    final String type = ColumnarBatches.getComprehensiveLightBatchType(batch);
    return Objects.equals(type, comprehensiveType());
  }

  protected void checkBackendBatch(ColumnarBatch batch) {
    if (ColumnarBatches.isZeroColumnBatch(batch)) {
      return;
    }
    Preconditions.checkArgument(
        isBackendBatch(batch),
        String.format(
            "Expected comprehensive batch type %s, but got %s",
            comprehensiveType(), ColumnarBatches.getComprehensiveLightBatchType(batch)));
  }

  protected ColumnarBatch toBackendBatch(ColumnarBatch input) {
    ColumnarBatches.checkOffloaded(input);
    if (ColumnarBatches.isZeroColumnBatch(input)) {
      return input;
    }
    Preconditions.checkArgument(!isBackendBatch(input));
    final Runtime runtime =
        Runtimes.contextInstance(BackendsApiManager.getBackendName(), toBatchActionName());
    final long handle = ColumnarBatches.getNativeHandle(BackendsApiManager.getBackendName(), input);
    final long outHandle = fromNative(runtime, handle);
    final ColumnarBatch output = ColumnarBatches.create(outHandle);

    // Follow input's reference count. This might be optimized using
    // automatic clean-up or once the extensibility of ColumnarBatch is enriched.
    final long refCnt = ColumnarBatches.getRefCntLight(input);
    final IndicatorVector giv = (IndicatorVector) output.column(0);
    for (long i = 0; i < (refCnt - 1); i++) {
      giv.retain();
    }

    // Close the input one.
    for (long i = 0; i < refCnt; i++) {
      input.close();
    }

    // Populate new vectors to input.
    SparkColumnarBatchUtil.transferVectors(output, input);

    return input;
  }

  protected ColumnarBatch ensureBackendBatch(ColumnarBatch input) {
    final ColumnarBatch light =
        ColumnarBatches.ensureOffloaded(ArrowBufferAllocators.contextInstance(), input);
    if (isBackendBatch(light)) {
      return light;
    }
    return toBackendBatch(light);
  }

  protected ColumnarBatch composeBatches(ColumnarBatch... batches) {
    final Runtime runtime =
        Runtimes.contextInstance(BackendsApiManager.getBackendName(), composeActionName());
    final long[] handles =
        Arrays.stream(batches)
            .mapToLong(b -> ColumnarBatches.getNativeHandle(BackendsApiManager.getBackendName(), b))
            .toArray();
    final long handle = composeNative(runtime, handles);
    return ColumnarBatches.create(handle);
  }

  protected ColumnarBatch sliceBatch(ColumnarBatch batch, int offset, int limit) {
    int totalRows = batch.numRows();
    if (limit >= totalRows) {
      // No need to prune.
      return batch;
    } else {
      Runtime runtime =
          Runtimes.contextInstance(BackendsApiManager.getBackendName(), sliceActionName());
      long nativeHandle =
          ColumnarBatches.getNativeHandle(BackendsApiManager.getBackendName(), batch);
      long handle = sliceNative(runtime, nativeHandle, offset, limit);
      return ColumnarBatches.create(handle);
    }
  }

  protected ColumnarBatch repeatedThenComposeBatches(
      ColumnarBatch batch1, ColumnarBatch batch2, int[] rowId2RowNums) {
    final Runtime runtime =
        Runtimes.contextInstance(
            BackendsApiManager.getBackendName(), repeatedThenComposeActionName());
    final long handle =
        repeatedThenComposeNative(
            runtime,
            ColumnarBatches.getNativeHandle(BackendsApiManager.getBackendName(), batch1),
            ColumnarBatches.getNativeHandle(BackendsApiManager.getBackendName(), batch2),
            rowId2RowNums);
    return ColumnarBatches.create(handle);
  }
}
