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
package org.apache.spark.storage;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * An input stream over a segment of a local file whose descriptor is opened, read and closed by
 * native code.
 *
 * <p>Every {@link java.io.FileInputStream} or {@link java.nio.channels.FileChannel} registers its
 * descriptor with the JDK-wide {@link java.lang.ref.Cleaner} on open and unregisters it on close.
 * Both operations take a single global lock. A shuffle read opens one file per (map, reduce) block,
 * so with many concurrent tasks and many small blocks that lock becomes the bottleneck of the whole
 * stage. Native descriptors avoid it. The native reader reads this stream directly through {@link
 * #readTo}; the {@link InputStream} methods are for any other consumer.
 *
 * <p>The descriptor is not reclaimed by GC: the stream must be closed, which the shuffle fetch
 * iterator guarantees on task completion.
 */
public final class GlutenNativeFileSegmentInputStream extends InputStream {
  private static volatile boolean nativeAvailable = true;

  private final String path;
  private final long offset;
  private final long length;
  private int fd;
  private long position = 0L;

  private GlutenNativeFileSegmentInputStream(String path, int fd, long offset, long length) {
    this.path = path;
    this.fd = fd;
    this.offset = offset;
    this.length = length;
  }

  /**
   * Opens the segment, or returns null if the native library does not provide the reader (e.g. a
   * backend that does not link it), in which case the caller should use a Java stream.
   */
  public static GlutenNativeFileSegmentInputStream tryOpen(File file, long offset, long length)
      throws IOException {
    if (!nativeAvailable) {
      return null;
    }
    final String path = file.getPath();
    final int fd;
    try {
      fd = nativeOpen(path);
    } catch (UnsatisfiedLinkError e) {
      nativeAvailable = false;
      return null;
    }
    if (fd < 0) {
      throw new IOException("Error opening " + path + " (errno " + (-fd) + ")");
    }
    return new GlutenNativeFileSegmentInputStream(path, fd, offset, length);
  }

  /** Reads up to maxSize bytes into native memory at address. Returns 0 at the end. */
  public synchronized long readTo(long address, long maxSize) throws IOException {
    ensureOpen();
    final long toRead = Math.min(maxSize, length - position);
    if (toRead <= 0) {
      return 0;
    }
    final long n = nativePread(fd, address, toRead, offset + position);
    if (n < 0) {
      throw new IOException("Error reading " + path + " (errno " + (-n) + ")");
    }
    if (n == 0) {
      throw new IOException(
          "Unexpected end of "
              + path
              + " at "
              + (offset + position)
              + ", segment "
              + offset
              + "+"
              + length);
    }
    position += n;
    return n;
  }

  public synchronized long tell() {
    return position;
  }

  @Override
  public synchronized int read() throws IOException {
    final byte[] b = new byte[1];
    final int n = read(b, 0, 1);
    return n <= 0 ? -1 : (b[0] & 0xff);
  }

  @Override
  public synchronized int read(byte[] b, int off, int len) throws IOException {
    ensureOpen();
    if (off < 0 || len < 0 || len > b.length - off) {
      throw new IndexOutOfBoundsException();
    }
    if (len == 0) {
      return 0;
    }
    final int toRead = (int) Math.min(len, length - position);
    if (toRead <= 0) {
      return -1;
    }
    final int n = nativePreadToArray(fd, b, off, toRead, offset + position);
    if (n < 0) {
      throw new IOException("Error reading " + path + " (errno " + (-n) + ")");
    }
    if (n == 0) {
      throw new IOException("Unexpected end of " + path);
    }
    position += n;
    return n;
  }

  @Override
  public synchronized long skip(long n) {
    final long skipped = Math.max(0L, Math.min(n, length - position));
    position += skipped;
    return skipped;
  }

  @Override
  public synchronized int available() {
    return (int) Math.min(Integer.MAX_VALUE, length - position);
  }

  @Override
  public synchronized void close() {
    if (fd >= 0) {
      nativeClose(fd);
      fd = -1;
    }
  }

  public synchronized boolean isClosed() {
    return fd < 0;
  }

  private void ensureOpen() throws IOException {
    if (fd < 0) {
      throw new IOException("Stream closed: " + path);
    }
  }

  /** Returns the descriptor, or -errno. */
  private static native int nativeOpen(String path);

  /** Returns the number of bytes read (0 at end of file), or -errno. */
  private static native long nativePread(int fd, long address, long size, long position);

  private static native int nativePreadToArray(
      int fd, byte[] dest, int off, int len, long position);

  private static native void nativeClose(int fd);
}
