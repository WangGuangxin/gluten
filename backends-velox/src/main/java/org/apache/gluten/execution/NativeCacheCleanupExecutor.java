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
package org.apache.gluten.execution;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tracks asynchronous cache maintenance and native-resource removal callbacks. */
public final class NativeCacheCleanupExecutor implements Executor {
  private final Executor delegate;
  private final Object idle = new Object();
  private long pendingTasks;

  public NativeCacheCleanupExecutor(Executor delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  @Override
  public void execute(Runnable command) {
    Objects.requireNonNull(command);
    synchronized (idle) {
      pendingTasks++;
    }
    AtomicBoolean completed = new AtomicBoolean();
    Runnable finish =
        () -> {
          if (completed.compareAndSet(false, true)) {
            synchronized (idle) {
              if (--pendingTasks == 0) {
                idle.notifyAll();
              }
            }
          }
        };
    try {
      delegate.execute(
          () -> {
            try {
              command.run();
            } finally {
              finish.run();
            }
          });
    } catch (RuntimeException | Error error) {
      // Submission may be rejected, or a direct executor may propagate the
      // command's exception. Complete the accounting exactly once in either case.
      finish.run();
      throw error;
    }
  }

  /**
   * Waits for submitted tasks, including any child tasks they submit. Cache writers must be
   * quiescent, and this method must not be called by one of this executor's own tasks. The delegate
   * remains available and is not shut down. Preserve interruption until native releases finish.
   */
  public void awaitPendingTasks() {
    boolean interrupted = false;
    synchronized (idle) {
      while (pendingTasks != 0) {
        try {
          idle.wait();
        } catch (InterruptedException error) {
          interrupted = true;
        }
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
