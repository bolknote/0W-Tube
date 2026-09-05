package ru.tubetv.app;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One bounded pool for all quality batches, independent of the number of search pages. */
final class ParallelTasks {
    private static final ThreadPoolExecutor PROBES = new ThreadPoolExecutor(4, 4,
            30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), task -> {
                Thread thread = new Thread(task, "media-inspection");
                thread.setDaemon(true);
                return thread;
            });
    static { PROBES.allowCoreThreadTimeOut(true); }

    interface Operation<T, R> { R run(T value) throws Exception; }

    static <T, R> List<R> map(List<T> values, int parallel, Operation<T, R> operation) {
        return map(PROBES, values, parallel, operation);
    }

    static <T, R> List<R> map(ThreadPoolExecutor pool, List<T> values, int parallel,
                               Operation<T, R> operation) {
        List<R> results = new ArrayList<>();
        List<Future<R>> pending = new ArrayList<>();
        int window = Math.max(1, Math.min(4, parallel));
        try {
            for (int offset = 0; offset < values.size(); offset += window) {
                if (Thread.currentThread().isInterrupted()) break;
                for (int i = offset; i < Math.min(values.size(), offset + window); i++) {
                    T value = values.get(i);
                    pending.add(pool.submit(() -> {
                        BoundedHttpReader.checkInterrupted();
                        return operation.run(value);
                    }));
                }
                for (Future<R> future : pending) {
                    try {
                        R result = future.get();
                        if (result != null) results.add(result);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return results;
                    } catch (ExecutionException ignored) {
                        // A broken video must not discard the other results in this batch.
                    }
                }
                pending.clear();
            }
            return results;
        } catch (RejectedExecutionException busy) {
            // Keep the queue bounded even if several search screens coexist. The
            // caller completes this batch and may retry missing qualities later.
            return results;
        } finally {
            for (Future<R> future : pending) future.cancel(true);
            pool.purge();
        }
    }

    private ParallelTasks() { }
}
