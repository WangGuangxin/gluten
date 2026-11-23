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
package org.apache.spark.storage

import org.apache.spark.SparkEnv
import org.apache.spark.internal.config
import org.apache.spark.network.buffer.{FileSegmentManagedBuffer, ManagedBuffer}
import org.apache.spark.network.netty.SparkTransportConf
import org.apache.spark.network.util.TransportConf
import org.apache.spark.shuffle.IndexShuffleBlockResolver

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Local shuffle block lookup that reads each map output's index file once per executor.
 *
 * Spark's [[IndexShuffleBlockResolver.getBlockData]] opens and closes the index file for every
 * block, and every Java file open/close takes the JDK Cleaner's global lock. With many reduce
 * partitions and concurrent tasks this lock serializes the shuffle read. The offsets of an index
 * file never change: a map output is identified by its shuffle id and its map id, which is the
 * unique task attempt id of the writer, and is committed once.
 */
object GlutenLocalShuffleBlocks {
  private val MaxIndexFileBytes = 4L << 20
  private val MaxCachedBytes = 512L << 20

  private val offsetsCache = new ConcurrentHashMap[(Int, Long), Array[Long]]()
  private val cachedBytes = new AtomicLong(0L)
  @volatile private var transportConf: TransportConf = _

  def getLocalBlockData(blockManager: BlockManager, blockId: BlockId): ManagedBuffer = {
    val resolver = indexResolver()
    if (resolver == null) {
      return blockManager.getLocalBlockData(blockId)
    }
    val buf = blockId match {
      case id: ShuffleBlockId =>
        segment(resolver, id.shuffleId, id.mapId, id.reduceId, id.reduceId + 1)
      case id: ShuffleBlockBatchId =>
        segment(resolver, id.shuffleId, id.mapId, id.startReduceId, id.endReduceId)
      case _ => null
    }
    if (buf != null) buf else blockManager.getLocalBlockData(blockId)
  }

  /** Opens a local file segment without a Java file descriptor when the native reader exists. */
  def openFileSegment(buf: FileSegmentManagedBuffer): InputStream = {
    val in = GlutenNativeFileSegmentInputStream.tryOpen(buf.getFile, buf.getOffset, buf.getLength)
    if (in != null) in else buf.createInputStream()
  }

  def removeShuffle(shuffleId: Int): Unit = {
    val it = offsetsCache.entrySet().iterator()
    while (it.hasNext) {
      val e = it.next()
      if (e.getKey._1 == shuffleId && offsetsCache.remove(e.getKey, e.getValue)) {
        cachedBytes.addAndGet(-8L * e.getValue.length)
      }
    }
  }

  def clear(): Unit = {
    offsetsCache.clear()
    cachedBytes.set(0L)
    transportConf = null
  }

  private def indexResolver(): IndexShuffleBlockResolver = {
    val env = SparkEnv.get
    if (env == null) {
      return null
    }
    // BlockManager.getLocalBlockData falls back to the decommission fallback storage on IOException.
    if (env.conf.get(config.STORAGE_DECOMMISSION_FALLBACK_STORAGE_PATH).isDefined) {
      return null
    }
    env.shuffleManager.shuffleBlockResolver match {
      case r: IndexShuffleBlockResolver => r
      case _ => null
    }
  }

  private def segment(
      resolver: IndexShuffleBlockResolver,
      shuffleId: Int,
      mapId: Long,
      startReduceId: Int,
      endReduceId: Int): ManagedBuffer = {
    val offsets = indexOffsets(resolver, shuffleId, mapId)
    if (offsets == null || startReduceId < 0 || endReduceId >= offsets.length) {
      return null
    }
    val start = offsets(startReduceId)
    val end = offsets(endReduceId)
    new FileSegmentManagedBuffer(
      shuffleTransportConf(),
      resolver.getDataFile(shuffleId, mapId),
      start,
      end - start)
  }

  private def indexOffsets(
      resolver: IndexShuffleBlockResolver,
      shuffleId: Int,
      mapId: Long): Array[Long] = {
    val key = (shuffleId, mapId)
    val cached = offsetsCache.get(key)
    if (cached != null) {
      return cached
    }
    val indexFile = resolver.getIndexFile(shuffleId, mapId)
    val size = indexFile.length()
    if (size <= 0 || size > MaxIndexFileBytes || size % 8 != 0) {
      return null
    }
    val bytes = Files.readAllBytes(indexFile.toPath)
    if (bytes.length != size) {
      return null
    }
    val offsets = new Array[Long](bytes.length / 8)
    ByteBuffer.wrap(bytes).asLongBuffer().get(offsets)
    if (cachedBytes.get() + bytes.length > MaxCachedBytes) {
      offsetsCache.clear()
      cachedBytes.set(0L)
    }
    if (offsetsCache.putIfAbsent(key, offsets) == null) {
      cachedBytes.addAndGet(bytes.length)
    }
    offsets
  }

  private def shuffleTransportConf(): TransportConf = {
    var conf = transportConf
    if (conf == null) {
      conf = SparkTransportConf.fromSparkConf(SparkEnv.get.conf, "shuffle")
      transportConf = conf
    }
    conf
  }
}
