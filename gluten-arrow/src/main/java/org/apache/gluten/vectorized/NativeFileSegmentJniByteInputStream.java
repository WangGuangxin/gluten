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
package org.apache.gluten.vectorized;

import org.apache.gluten.exception.GlutenException;

import org.apache.spark.storage.GlutenNativeFileSegmentInputStream;

import java.io.IOException;
import java.io.InputStream;

/** Reads a {@link GlutenNativeFileSegmentInputStream} straight into native memory. */
public class NativeFileSegmentJniByteInputStream implements JniByteInputStream {
  private final InputStream in;
  private final GlutenNativeFileSegmentInputStream segment;

  NativeFileSegmentJniByteInputStream(InputStream in, GlutenNativeFileSegmentInputStream segment) {
    this.in = in; // closing the outermost stream releases the fetched block
    this.segment = segment;
  }

  public static boolean isSupported(InputStream unwrapped) {
    return unwrapped instanceof GlutenNativeFileSegmentInputStream;
  }

  @Override
  public long read(long destAddress, long maxSize) {
    try {
      return segment.readTo(destAddress, maxSize);
    } catch (IOException e) {
      throw new GlutenException(e);
    }
  }

  @Override
  public long tell() {
    return segment.tell();
  }

  @Override
  public void close() {
    try {
      in.close();
    } catch (IOException e) {
      throw new GlutenException(e);
    } finally {
      segment.close();
    }
  }
}
