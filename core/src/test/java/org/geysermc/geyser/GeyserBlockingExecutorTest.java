/*
 * Copyright (c) 2019-2026 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GeyserBlockingExecutorTest {

    @Test
    void threadsAreNamedDaemons() throws Exception {
        ExecutorService executor = GeyserImpl.newBlockingExecutor();
        try {
            Thread thread = executor.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
            assertTrue(thread.getName().startsWith("Geyser Blocking IO"), thread.getName());
            assertTrue(thread.isDaemon());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void boundedToSixteenConcurrentTasks() throws Exception {
        ExecutorService executor = GeyserImpl.newBlockingExecutor();
        try {
            int tasks = 17;
            AtomicInteger running = new AtomicInteger();
            AtomicInteger maxRunning = new AtomicInteger();
            Set<Thread> threads = ConcurrentHashMap.newKeySet();
            List<Future<?>> futures = new ArrayList<>();
            long start = System.nanoTime();
            for (int i = 0; i < tasks; i++) {
                futures.add(executor.submit(() -> {
                    maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                    threads.add(Thread.currentThread());
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        running.decrementAndGet();
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertEquals(16, maxRunning.get());
            assertEquals(16, threads.size());
            // The 17th task waits for a thread, so at least two batches of 200 ms ran
            assertTrue(elapsedMillis >= 390, "elapsed " + elapsedMillis + " ms");
            assertTrue(((ThreadPoolExecutor) executor).allowsCoreThreadTimeOut());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shutdownNowInterruptsBlockedTasks() throws Exception {
        ExecutorService executor = GeyserImpl.newBlockingExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(2));
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        executor.shutdownNow();
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
}
