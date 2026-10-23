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

import org.apache.gluten.config.VeloxConfig

import org.apache.spark.{ShuffleDependency, SparkEnv}
import org.apache.spark.network.buffer.FileSegmentManagedBuffer
import org.apache.spark.network.netty.SparkTransportConf
import org.apache.spark.sql.GlutenQueryTest
import org.apache.spark.sql.test.SharedSparkSession

class GlutenShuffleIndexCacheSuite extends GlutenQueryTest with SharedSparkSession {
  private val numReducers = 7

  // Keeps shuffled RDDs reachable so that ContextCleaner does not delete their files mid-test.
  private val retained = scala.collection.mutable.ArrayBuffer.empty[AnyRef]

  // Runs a shuffle with 'numMaps' map outputs and returns its id and the map ids.
  private def runShuffle(numMaps: Int): (Int, Seq[Long]) = {
    val rdd = spark.sparkContext
      .parallelize(1 to 5000, numMaps)
      .map(i => (i % 97, i.toLong))
      .reduceByKey(_ + _, numReducers)
    retained += rdd
    assert(rdd.collect().map(_._2).sum == (1 to 5000).map(_.toLong).sum)
    val shuffleId = rdd.dependencies.head.asInstanceOf[ShuffleDependency[_, _, _]].shuffleId
    val mapIds = SparkEnv.get.mapOutputTracker
      .getMapSizesByExecutorId(shuffleId, 0, Int.MaxValue, 0, numReducers)
      .flatMap(_._2.map(_._1.asInstanceOf[ShuffleBlockId].mapId))
      .toSeq
      .distinct
    assert(mapIds.size == numMaps)
    (shuffleId, mapIds)
  }

  private def blockIds(shuffleId: Int, mapId: Long): Seq[BlockId] =
    (0 until numReducers).map(r => ShuffleBlockId(shuffleId, mapId, r)) ++
      Seq(
        ShuffleBlockBatchId(shuffleId, mapId, 0, numReducers),
        ShuffleBlockBatchId(shuffleId, mapId, 2, 5),
        ShuffleBlockBatchId(shuffleId, mapId, 3, 3))

  private def assertSameAsSpark(shuffleId: Int, mapIds: Seq[Long], maxBytes: Long): Unit = {
    val blockManager = SparkEnv.get.blockManager
    val dirs = blockManager.diskBlockManager.localDirsString
    val transportConf = SparkTransportConf.fromSparkConf(SparkEnv.get.conf, "shuffle")
    for (_ <- 0 until 2; mapId <- mapIds; blockId <- blockIds(shuffleId, mapId)) {
      val expected =
        blockManager.getHostLocalShuffleData(blockId, dirs).asInstanceOf[FileSegmentManagedBuffer]
      val actual = GlutenShuffleIndexCache
        .getHostLocalShuffleData(blockManager, blockId, dirs, maxBytes, transportConf)
        .asInstanceOf[FileSegmentManagedBuffer]
      assert(actual.getFile == expected.getFile, blockId)
      assert(actual.getOffset == expected.getOffset, blockId)
      assert(actual.getLength == expected.getLength, blockId)
    }
  }

  test("index cache is opt-in and application-static") {
    assert(!VeloxConfig.SHUFFLE_INDEX_CACHE_ENABLED.defaultValue.get)
    assert(VeloxConfig.SHUFFLE_INDEX_CACHE_ENABLED.key == GlutenShuffleIndexCache.ENABLED_KEY)
    assert(VeloxConfig.SHUFFLE_INDEX_CACHE_MAX_BYTES.key == GlutenShuffleIndexCache.MAX_BYTES_KEY)
  }

  test("cached offsets match Spark for single and batched blocks") {
    val (shuffleId, mapIds) = runShuffle(5)
    assertSameAsSpark(shuffleId, mapIds, 1L << 20)
    assert(GlutenShuffleIndexCache.cachedShuffles.contains(shuffleId))
    assert(GlutenShuffleIndexCache.cachedBytes > 0)
    GlutenShuffleIndexCache.removeShuffle(shuffleId)
    assert(!GlutenShuffleIndexCache.cachedShuffles.contains(shuffleId))
  }

  test("budget evicts least recently used shuffles and serves the rest uncached") {
    val first = runShuffle(3)
    val second = runShuffle(4)
    // One index file holds numReducers + 1 offsets; budget two of them plus overhead.
    val budget = 2L * ((numReducers + 1) * 8 + 64)
    assertSameAsSpark(first._1, first._2, budget)
    assert(GlutenShuffleIndexCache.cachedBytes <= budget)
    assertSameAsSpark(second._1, second._2, budget)
    assert(GlutenShuffleIndexCache.cachedBytes <= budget)
    assert(!GlutenShuffleIndexCache.cachedShuffles.contains(first._1))
    Seq(first._1, second._1).foreach(GlutenShuffleIndexCache.removeShuffle)
    assert(GlutenShuffleIndexCache.cachedBytes == 0)
  }
}
