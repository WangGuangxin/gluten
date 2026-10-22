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
package org.apache.gluten.execution

import com.github.benmanes.caffeine.cache.{Caffeine, RemovalCause, RemovalListener}
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent._
import java.util.concurrent.atomic.AtomicBoolean

class NativeCacheCleanupExecutorSuite extends AnyFunSuite {
  test("shutdown waits for asynchronous native cache removal") {
    val cleanup = new NativeCacheCleanupExecutor(ForkJoinPool.commonPool())
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val released = new AtomicBoolean(false)
    val cache = Caffeine.newBuilder
      .executor(cleanup)
      .removalListener(new RemovalListener[Integer, Integer] {
        override def onRemoval(key: Integer, value: Integer, cause: RemovalCause): Unit = {
          entered.countDown()
          assert(release.await(10, TimeUnit.SECONDS))
          released.set(true)
        }
      }).build[Integer, Integer]()
    val waiter = Executors.newSingleThreadExecutor()
    try {
      cache.put(1, 7)
      cache.invalidateAll()
      cache.cleanUp()
      assert(entered.await(10, TimeUnit.SECONDS))
      assert(!released.get())
      val drained = waiter.submit(new Runnable {
        override def run(): Unit = cleanup.awaitPendingTasks()
      })
      intercept[TimeoutException](drained.get(50, TimeUnit.MILLISECONDS))
      release.countDown()
      drained.get(10, TimeUnit.SECONDS)
      assert(released.get())
    } finally {
      release.countDown()
      waiter.shutdownNow()
    }
  }

  test("shutdown includes work submitted by a pending maintenance task") {
    val tasks = new LinkedBlockingQueue[Runnable]()
    val cleanup = new NativeCacheCleanupExecutor((task: Runnable) => tasks.add(task))
    val childRan = new AtomicBoolean(false)
    val waiter = Executors.newSingleThreadExecutor()
    try {
      cleanup.execute(() => cleanup.execute(() => childRan.set(true)))
      val drained = waiter.submit(new Runnable {
        override def run(): Unit = cleanup.awaitPendingTasks()
      })
      tasks.take().run()
      intercept[TimeoutException](drained.get(50, TimeUnit.MILLISECONDS))
      assert(!childRan.get())
      tasks.take().run()
      drained.get(10, TimeUnit.SECONDS)
      assert(childRan.get())
    } finally {
      var remaining = tasks.poll()
      while (remaining != null) {
        remaining.run()
        remaining = tasks.poll()
      }
      waiter.shutdownNow()
    }
  }

  test("failed direct execution completes accounting exactly once") {
    val cleanup = new NativeCacheCleanupExecutor((task: Runnable) => task.run())
    intercept[IllegalArgumentException] {
      cleanup.execute(() => throw new IllegalArgumentException("callback failure"))
    }
    cleanup.execute(() => ())
    cleanup.awaitPendingTasks()
  }

  test("rejected submission does not leave shutdown waiting") {
    val cleanup = new NativeCacheCleanupExecutor(
      (_: Runnable) => throw new RejectedExecutionException("rejected"))
    intercept[RejectedExecutionException](cleanup.execute(() => ()))
    cleanup.awaitPendingTasks()
  }

  test("interruption is preserved after native cleanup has completed") {
    val tasks = new LinkedBlockingQueue[Runnable]()
    val cleanup = new NativeCacheCleanupExecutor((task: Runnable) => tasks.add(task))
    val release = new CountDownLatch(1)
    val interruptedAfterDrain = new AtomicBoolean(false)
    val returned = new CountDownLatch(1)
    cleanup.execute(() => release.countDown())
    val waiter = new Thread(
      () => {
        Thread.currentThread().interrupt()
        cleanup.awaitPendingTasks()
        interruptedAfterDrain.set(Thread.currentThread().isInterrupted)
        returned.countDown()
      })
    waiter.start()
    try {
      assert(!returned.await(50, TimeUnit.MILLISECONDS))
      tasks.take().run()
      assert(returned.await(10, TimeUnit.SECONDS))
      assert(release.getCount == 0)
      assert(interruptedAfterDrain.get())
    } finally {
      var remaining = tasks.poll()
      while (remaining != null) {
        remaining.run()
        remaining = tasks.poll()
      }
      waiter.join(10000)
    }
  }
}
