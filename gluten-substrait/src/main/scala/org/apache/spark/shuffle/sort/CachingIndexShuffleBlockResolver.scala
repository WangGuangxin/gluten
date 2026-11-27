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
package org.apache.spark.shuffle.sort

import org.apache.spark.{SparkConf, TaskContext}
import org.apache.spark.network.buffer.ManagedBuffer
import org.apache.spark.shuffle.IndexShuffleBlockResolver
import org.apache.spark.storage.{BlockId, ShuffleBlockBatchId, ShuffleBlockId}
import org.apache.spark.util.collection.OpenHashSet

import java.io.{ByteArrayInputStream, EOFException, InputStream, IOException}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

/**
 * Reads the shuffle blocks that tasks of this executor fetch from local disk (its own map outputs
 * and those of other executors on the same host) through file channels kept open per map output.
 *
 * Spark's resolver opens and closes the index file, then the data file, for every block, and
 * interns both file paths. With many partitions the blocks are small (a 20000 x 20000 shuffle has
 * 400M blocks of a few hundred bytes), and these system calls take a large share of the reduce
 * stage. Here the two files of a map output are opened once and read with positional reads.
 *
 * Blocks served to other hosts (no task context) keep Spark's path, which supports zero-copy
 * transfer. The channels of a shuffle are closed when it is unregistered.
 */
class CachingIndexShuffleBlockResolver(
    conf: SparkConf,
    taskIdMapsForShuffle: ConcurrentHashMap[Int, OpenHashSet[Long]])
  extends IndexShuffleBlockResolver(conf, null, taskIdMapsForShuffle) {

  import CachingIndexShuffleBlockResolver._

  private val cache = new ConcurrentHashMap[Key, MapFiles]()
  private val openFiles = new AtomicInteger(0)

  override def getBlockData(blockId: BlockId, dirs: Option[Array[String]]): ManagedBuffer = {
    val range = blockId match {
      case id: ShuffleBlockId if TaskContext.get() != null =>
        Some((id.shuffleId, id.mapId, id.reduceId, id.reduceId + 1))
      case id: ShuffleBlockBatchId if TaskContext.get() != null =>
        Some((id.shuffleId, id.mapId, id.startReduceId, id.endReduceId))
      case _ => None
    }
    val files = range.map { case (shuffleId, mapId, _, _) => mapFiles(shuffleId, mapId, dirs) }
    (range, files) match {
      case (Some((_, _, startReduceId, endReduceId)), Some(f)) if f != null =>
        val start = readLong(f.index, startReduceId * 8L)
        val end = readLong(f.index, endReduceId * 8L)
        new ChannelSegmentBuffer(f.data, start, end - start)
      case _ => super.getBlockData(blockId, dirs)
    }
  }

  /** The cached channels of a map output, or null to let Spark's resolver read the block. */
  private def mapFiles(shuffleId: Int, mapId: Long, dirs: Option[Array[String]]): MapFiles = {
    val key = Key(shuffleId, mapId, dirs.flatMap(_.headOption).getOrElse(""))
    val cached = cache.get(key)
    if (cached != null) {
      return cached
    }
    val indexFile = getIndexFile(shuffleId, mapId, dirs)
    val dataFile = getDataFile(shuffleId, mapId, dirs)
    // Let Spark handle, and report, anything but two existing files.
    if (!indexFile.isFile || !dataFile.isFile) {
      return null
    }
    if (openFiles.addAndGet(2) > MaxOpenFiles) {
      openFiles.addAndGet(-2)
      return null
    }
    val opened =
      try {
        val index = FileChannel.open(indexFile.toPath, StandardOpenOption.READ)
        try {
          new MapFiles(FileChannel.open(dataFile.toPath, StandardOpenOption.READ), index)
        } catch {
          case t: Throwable =>
            index.close()
            throw t
        }
      } catch {
        case t: Throwable =>
          openFiles.addAndGet(-2)
          throw t
      }
    val raced = cache.putIfAbsent(key, opened)
    if (raced != null) {
      close(opened)
      raced
    } else {
      opened
    }
  }

  private def close(files: MapFiles): Unit = {
    files.close()
    openFiles.addAndGet(-2)
  }

  /** Number of map outputs whose files are open. */
  private[sort] def numOpenMapOutputs: Int = cache.size()

  /** Closes the channels of a shuffle. Its blocks must no longer be read. */
  def removeShuffle(shuffleId: Int): Unit = {
    cache.keySet().asScala.filter(_.shuffleId == shuffleId).foreach {
      key => Option(cache.remove(key)).foreach(close)
    }
  }

  override def stop(): Unit = {
    cache.keySet().asScala.foreach(key => Option(cache.remove(key)).foreach(close))
    super.stop()
  }
}

object CachingIndexShuffleBlockResolver {
  // Two channels per map output: a 20000-map shuffle needs 40000.
  private val MaxOpenFiles = 131072

  // Segments up to this size are read into memory in one positional read when opened as a stream.
  private val MaxEagerSegment = 1 << 20

  private case class Key(shuffleId: Int, mapId: Long, dirsKey: String)

  private class MapFiles(val data: FileChannel, val index: FileChannel) {
    def close(): Unit = {
      try data.close()
      catch { case NonFatal(_) => }
      try index.close()
      catch { case NonFatal(_) => }
    }
  }

  private def readFully(channel: FileChannel, buf: ByteBuffer, position: Long): Unit = {
    var pos = position
    while (buf.hasRemaining) {
      val n = channel.read(buf, pos)
      if (n < 0) {
        throw new EOFException(s"Unexpected end of shuffle file at $pos")
      }
      pos += n
    }
  }

  private def readLong(channel: FileChannel, position: Long): Long = {
    val buf = ByteBuffer.allocate(8)
    readFully(channel, buf, position)
    buf.flip()
    buf.getLong()
  }

  /** A segment of a cached channel. Never closes the channel. */
  private class ChannelSegmentBuffer(channel: FileChannel, offset: Long, length: Long)
    extends ManagedBuffer {
    override def size(): Long = length

    override def nioByteBuffer(): ByteBuffer = {
      if (length > Int.MaxValue) {
        throw new IOException(s"Shuffle block of $length bytes is too large to read into memory")
      }
      val buf = ByteBuffer.allocate(length.toInt)
      readFully(channel, buf, offset)
      buf.flip()
      buf
    }

    override def createInputStream(): InputStream = {
      if (length <= MaxEagerSegment) {
        new ByteArrayInputStream(nioByteBuffer().array(), 0, length.toInt)
      } else {
        new ChannelSegmentInputStream(channel, offset, length)
      }
    }

    override def retain(): ManagedBuffer = this

    override def release(): ManagedBuffer = this

    override def convertToNetty(): AnyRef = nioByteBuffer()

    // Not `override`: Spark 3.x has no such method.
    def convertToNettyForSsl(): AnyRef = nioByteBuffer()
  }

  /** Reads a segment of a channel with positional reads. */
  private class ChannelSegmentInputStream(channel: FileChannel, offset: Long, length: Long)
    extends InputStream {
    private var pos = 0L

    override def read(): Int = {
      val one = new Array[Byte](1)
      if (read(one, 0, 1) < 0) -1 else one(0) & 0xff
    }

    override def read(b: Array[Byte], off: Int, len: Int): Int = {
      if (len == 0) {
        0
      } else if (pos >= length) {
        -1
      } else {
        val n = math.min(len.toLong, length - pos).toInt
        val buf = ByteBuffer.wrap(b, off, n)
        readFully(channel, buf, offset + pos)
        pos += n
        n
      }
    }

    override def skip(n: Long): Long = {
      val skipped = math.max(0L, math.min(n, length - pos))
      pos += skipped
      skipped
    }

    override def available(): Int = math.min(Int.MaxValue.toLong, length - pos).toInt
  }
}
