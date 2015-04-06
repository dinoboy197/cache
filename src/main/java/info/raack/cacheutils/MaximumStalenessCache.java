/**
 * This file is part of CacheUtils.
 *
 * (C) Copyright 2015 Taylor Raack.
 *
 * CacheUtils is free software: you can redistribute it and/or modify
 * it under the terms of the Affero GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * CacheUtils is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Affero GNU General Public License for more details.
 *
 * You should have received a copy of the Affero GNU General Public License
 * along with CacheUtils.  If not, see <http://www.gnu.org/licenses/>.
 */

package info.raack.cacheutils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ManagedBlocker;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * A mapping between keys and values, which automatically computes entries for
 * keys when requested and manages a staleness-bounded cache for the values.
 * This class allows clients to very easily trade off a bounded staleness for
 * speed. It should be considered an intelligent memory-only cache for
 * performance.
 *
 * Staleness is bounded for all key values; if a value for a key is requested
 * that was requested farther back in time than the staleness threshold allows,
 * that value is re-computed. Multiple threads requesting a key which has
 * reached its staleness bound will only re-load the key once; all threads will
 * be returned the single newly computed value.
 *
 * The computation function is provided by the user. This may either be a
 * non-blocking or blocking method. Any computation function which internally
 * uses ANY blocking methods MUST use the constructor with the BlockingFunction
 * argument, or this class will fail to work properly.
 *
 * This class is thread-safe.
 */
public class MaximumStalenessCache<K, V> implements Cache<K, V> {

    private final ConcurrentHashMap<K, Result<V>> cache = new ConcurrentHashMap<>();
    private final long maximumStalenessNanos;
    private final BlockingFunction<K, V> blockingValueComputer;
    private final Function<K, V> nonBlockingValueComputer;

    /**
     * Creates an instance of a MaximumStalenessCache based on a value computation
     * which may block.
     *
     * @param maximumStalenessMillis
     *            the maximum number of milliseconds that can elapse from the last
     *            time that any key was requested
     * @param blockingValueComputer
     *            a functional interface which computes cache key values which may
     *            block
     */
    public MaximumStalenessCache(long maximumStalenessMillis, BlockingFunction<K, V> blockingValueComputer) {
        this.maximumStalenessNanos = maximumStalenessMillis * 1000000;
        this.blockingValueComputer = blockingValueComputer;
        this.nonBlockingValueComputer = null;
    }

    /**
     * Creates an instance of a MaximumStalenessCache based on a value computation
     * which is guaranteed not to block.
     *
     * @param maximumStalenessMillis
     *            the maximum number of milliseconds that can elapse from the last
     *            time that any key was requested
     * @param blockingValueComputer
     *            a functional interface which computes cache key values which is
     *            guaranteed not to block
     */
    public MaximumStalenessCache(long maximumStalenessMillis, Function<K, V> nonBlockingValueComputer) {
        this.maximumStalenessNanos = maximumStalenessMillis * 1000000;
        this.nonBlockingValueComputer = nonBlockingValueComputer;
        this.blockingValueComputer = null;
    }

    /**
     * All values obtained via get() will return a value computed by the cacheLoader
     * which was requested less than maximumStalenessMillis ago.
     */
    @Override
    public V get(K key) throws InterruptedException {

        // look for value in cache
        Result<V> validResult = cache.computeIfPresent(key, (k, r) -> {
            // there is a result in the cache
            if (Long.compare(r.startedAt, System.nanoTime() - maximumStalenessNanos) > 0) {
                // result is still valid; return it
                return r;
            } else {
                // result is not valid (started loading result too long ago)
                return null;
            }
        });

        // was a sufficiently fresh value returned from the cache?
        if (validResult == null) {
            // no - compute one

            // computeIfAbsent ensures that only one thread competing on this call will
            // succeed,
            // and all will be returned the same single result

            // this creates a future with the result
            validResult = cache.computeIfAbsent(key, k -> createResult(k));
        }

        try {
            // return the value of the future, which may be a blocking call
            return validResult.data.get();
        } catch (ExecutionException e) {
            throw new RuntimeException("Could not compute value for key " + key, e.getCause());
        }
    }

    /**
     * Removes all pre-computed values from the cache.
     */
    public void clear() {
        cache.clear();
    }

    /**
     * Removes a single pre-computed value from the cache.
     *
     * @param key
     *            the key for the cache entry to be removed
     */
    public void remove(K key) {
        cache.remove(key);
    }

    private Result<V> createResult(final K key) {
        return new Result<>(System.nanoTime(), resourcePool.submit(() -> {
            if (blockingValueComputer != null) {
                // execute long-running, blocking computation
                Blocker<K, V> blocker = new Blocker<>(blockingValueComputer, key);
                // managedBlock ensures that enough threads are spun up for blocking methods to
                // prevent thread stavation
                ForkJoinPool.managedBlock(blocker);
                // extract result, as when managedBlock is done, the blocking computation is
                // finished
                return blocker.getItem();
            } else {
                return nonBlockingValueComputer.apply(key);
            }
        }));
    }

    private static class Blocker<A, B> implements ManagedBlocker {
        private final A key;
        private final BlockingFunction<A, B> cacheLoader;
        private volatile B item;

        public Blocker(BlockingFunction<A, B> cacheLoader, A key) {
            this.key = key;
            this.cacheLoader = cacheLoader;
        }

        @Override
        public boolean block() throws InterruptedException {
            if (item == null) {
                item = cacheLoader.apply(key);
            }
            return true; // no additional blocking is necessary
        }

        @Override
        public boolean isReleasable() {
            return item != null;
        }

        public B getItem() {
            return item;
        }
    }

    private static class Result<V> {
        private final long startedAt;
        private final Future<V> data;

        public Result(long startedAt, Future<V> data) {
            this.startedAt = startedAt;
            this.data = data;
        }
    }

    // create pool which will maximize computational resources available all times
    // it will scale up and down available threads as more tasks block
    // uses async-style tasks which are unrelated
    private static ExecutorService resourcePool = new ForkJoinPool(Runtime.getRuntime().availableProcessors(),
            ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, true);
}
