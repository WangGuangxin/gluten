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

import org.apache.spark.{HashPartitioner, ShuffleDependency, SparkConf, SparkContext, SparkEnv, TaskContext}
import org.apache.spark.network.buffer.ManagedBuffer
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.{BlockId, ShuffleBlockBatchId, ShuffleBlockId}

import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

// Spark's sort shuffle writes the map outputs; the resolver under test reads them from the same
// local directories.
class CachingIndexShuffleBlockResolverSuite extends AnyFunSuiteLike with BeforeAndAfterAll {
  import CachingIndexShuffleBlockResolverSuite._

  private var sc: SparkContext = _
  private var resolver: CachingIndexShuffleBlockResolver = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    val conf = new SparkConf()
      .setMaster("local[4]")
      .setAppName("CachingIndexShuffleBlockResolverSuite")
      .set("spark.ui.enabled", "false")
      // Uncompressed, so that a block of reducer 0 exceeds what is read into memory at once.
      .set("spark.shuffle.compress", "false")
    sc = new SparkContext(conf)
    resolver = new CachingIndexShuffleBlockResolver(conf, new ConcurrentHashMap())
  }

  override def afterAll(): Unit = {
    try {
      if (resolver != null) {
        resolver.stop()
      }
      if (sc != null) {
        sc.stop()
      }
    } finally {
      super.afterAll()
    }
  }

  // Map ids of the map outputs of 'shuffleId' on local disk.
  private def mapIds(shuffleId: Int): Seq[Long] = {
    val dataFile = s"shuffle_${shuffleId}_(\\d+)_0\\.data".r
    SparkEnv.get.blockManager.diskBlockManager.getAllFiles().map(_.getName).collect {
      case dataFile(mapId) => mapId.toLong
    }
  }

  // Runs a shuffle with 2 map tasks and returns its id.
  private def shuffle(numRows: Int): Int = {
    val shuffled = sc
      .parallelize(0 until numRows, 2)
      .map(i => (i % NumReducers, i))
      .partitionBy(new HashPartitioner(NumReducers))
    shuffled.count()
    shuffleIdOf(shuffled)
  }

  private def shuffleIdOf(rdd: RDD[_]): Int =
    rdd.dependencies.head.asInstanceOf[ShuffleDependency[_, _, _]].shuffleId

  private def bytes(buffer: ManagedBuffer): (Array[Byte], Array[Byte]) = {
    val fromBuffer = toArray(buffer.nioByteBuffer())
    val out = new ByteArrayOutputStream()
    val in = buffer.createInputStream()
    try {
      in.transferTo(out)
    } finally {
      in.close()
    }
    (fromBuffer, out.toByteArray)
  }

  private def toArray(buffer: ByteBuffer): Array[Byte] = {
    val array = new Array[Byte](buffer.remaining())
    buffer.get(array)
    array
  }

  // Blocks read by tasks use the cached channels. Without a task context, the resolver serves
  // blocks the way Spark does, so both must return the same bytes.
  private def assertSameBytes(blockId: BlockId): Unit = {
    val expected = bytes(resolver.getBlockData(blockId, None))
    TaskContext.setTaskContext(TaskContext.empty())
    val actual =
      try {
        bytes(resolver.getBlockData(blockId, None))
      } finally {
        TaskContext.unset()
      }
    assert(actual._1.sameElements(expected._1), blockId)
    assert(actual._2.sameElements(expected._2), blockId)
    assert(actual._1.sameElements(expected._2), blockId)
  }

  test("returns the same bytes as Spark for single and batch block ids") {
    // Reducer 0 gets about 3 MiB, more than the 1 MiB that is read into memory at once.
    val payload = "x" * 1000
    val shuffled = sc
      .parallelize(0 until 20000, 2)
      .map(i => (if (i % 10 < 3) 0 else i % NumReducers, payload + i))
      .partitionBy(new HashPartitioner(NumReducers))
    shuffled.count()
    val shuffleId = shuffleIdOf(shuffled)
    val maps = mapIds(shuffleId)
    assert(maps.size == 2)
    maps.foreach {
      mapId =>
        (0 until NumReducers).foreach {
          reduceId => assertSameBytes(ShuffleBlockId(shuffleId, mapId, reduceId))
        }
        assertSameBytes(ShuffleBlockBatchId(shuffleId, mapId, 0, NumReducers))
        assertSameBytes(ShuffleBlockBatchId(shuffleId, mapId, 2, 5))
    }
  }

  test("removeShuffle closes the channels of the shuffle") {
    val first = shuffle(1000)
    val second = shuffle(1000)
    Seq(first, second).foreach {
      shuffleId =>
        mapIds(shuffleId).foreach(mapId => assertSameBytes(ShuffleBlockId(shuffleId, mapId, 0)))
    }
    val numOpen = resolver.numOpenMapOutputs
    resolver.removeShuffle(first)
    assert(resolver.numOpenMapOutputs == numOpen - mapIds(first).size)
  }

  test("lets Spark report a missing map output") {
    val shuffleId = shuffle(100)
    val missing = ShuffleBlockId(shuffleId, mapIds(shuffleId).max + 1000, 0)
    TaskContext.setTaskContext(TaskContext.empty())
    try {
      intercept[Exception](resolver.getBlockData(missing, None))
    } finally {
      TaskContext.unset()
    }
  }
}

object CachingIndexShuffleBlockResolverSuite {
  // In an object so that closures shipped to tasks do not capture the suite.
  private val NumReducers = 8
}
