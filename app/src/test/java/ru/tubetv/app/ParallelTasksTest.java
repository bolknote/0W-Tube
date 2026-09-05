package ru.tubetv.app;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ParallelTasksTest {
    private ThreadPoolExecutor pool() {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8));
    }

    @Test(timeout = 5000) public void saturatedQueueCompletesBatchWithoutSpawningExtraWorkers() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1));
        CountDownLatch started = new CountDownLatch(1);
        try {
            pool.execute(() -> {
                started.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            pool.execute(() -> { });
            assertTrue(ParallelTasks.map(pool, Arrays.asList(1, 2), 2, value -> value).isEmpty());
            assertEquals(1, pool.getPoolSize());
            assertEquals(1, pool.getQueue().size());
        } finally { pool.shutdownNow(); }
    }

    @Test(timeout = 5000) public void cancellingSearchInterruptsWorkersAndRemovesQueuedProbes() throws Exception {
        ThreadPoolExecutor pool = pool();
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch interrupted = new CountDownLatch(2);
        AtomicBoolean flagPreserved = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            ParallelTasks.map(pool, Arrays.asList(1, 2, 3, 4), 4, value -> {
                started.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException cancelled) { interrupted.countDown(); throw cancelled; }
                return value;
            });
            flagPreserved.set(Thread.currentThread().isInterrupted());
        });
        try {
            caller.start();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(2000);
            assertFalse(caller.isAlive());
            assertTrue(flagPreserved.get());
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertTrue(pool.getQueue().isEmpty());
            assertEquals(Arrays.asList(5, 6), ParallelTasks.map(pool, Arrays.asList(5, 6), 2, value -> value));
        } finally { caller.interrupt(); pool.shutdownNow(); }
    }

    @Test(timeout = 5000) public void limitsConcurrencyAndKeepsSuccessfulResultsAfterOneFailure() {
        ThreadPoolExecutor pool = pool();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CyclicBarrier pair = new CyclicBarrier(2);
        try {
            List<Integer> result = ParallelTasks.map(pool, Arrays.asList(1, 2, 3, 4), 2, value -> {
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                try {
                    pair.await(2, TimeUnit.SECONDS);
                    if (value == 2) throw new Exception("unavailable video");
                    return value;
                } finally { active.decrementAndGet(); }
            });
            assertEquals(2, maximum.get());
            assertEquals(Arrays.asList(1, 3, 4), result);
        } finally { pool.shutdownNow(); }
    }
}
