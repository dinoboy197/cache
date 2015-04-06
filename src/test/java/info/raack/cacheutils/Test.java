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

import java.util.LinkedList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import info.raack.cacheutils.Cache.BlockingFunction;

/**
 * Test harness to show performance benefit of MaximumStalenessCache vs a
 * no-cache scenario for a blocking computation.
 *
 * Example results:
 *
 * test of throughput and staleness for computation of each value (no caching)
 * (200 threads; 10000 calls per thread) average staleness in milliseconds: 0.0
 * average to return value in milliseconds: 4.6624279385
 *
 * test of throughput and staleness for MaximumStalenessCache (1000 max
 * staleness millis; 200 threads; 10000 calls per thread) average staleness in
 * milliseconds: 571.0360734485 average to return value in milliseconds:
 * 0.066887064
 *
 */
public class Test {

    public static Long longBlockingComputation(String key) throws InterruptedException {
        try {
            Thread.sleep(Integer.parseInt(key));
            // return moment when computation "finished" to allow for tracking cache
            // staleness statistics
            return System.nanoTime();
        } catch (NumberFormatException e) {
            throw new RuntimeException("bad key: " + key);
        }
    }

    private static class Worker implements Runnable {
        private final CountDownLatch startSignal;
        private final CountDownLatch doneSignal;
        private final List<Long> microsToComplete;
        private final List<Long> microsStale;
        private final Cache<String, Long> cache;
        private volatile int totalRequests;

        public Worker(int totalRequests, CountDownLatch startSignal, CountDownLatch doneSignal,
                Cache<String, Long> cache) {
            this.startSignal = startSignal;
            this.doneSignal = doneSignal;
            microsToComplete = new LinkedList<>();
            microsStale = new LinkedList<>();
            this.cache = cache;
            this.totalRequests = totalRequests;
        }

        @Override
        public void run() {
            try {
                startSignal.await();
                doWork();
                doneSignal.countDown();
            } catch (InterruptedException ex) {
            } // return;
        }

        void doWork() throws InterruptedException {
            Random r = new Random();
            while (totalRequests-- > 0) {
                String key = r.nextInt(10) + "";
                long start = System.nanoTime();
                long result = cache.get(key);
                long complete = System.nanoTime();
                microsToComplete.add((complete - start) / 1000);
                microsStale.add(Math.max(0, (start - result) / 1000));
            }
        }

        public List<Long> getMicrosToComplete() {
            return microsToComplete;
        }

        public List<Long> getMicrosStale() {
            return microsStale;
        }
    }

    public static void testHarness(int threads, int requestsPerThread, Cache<String, Long> cache)
            throws InterruptedException {
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(threads);

        List<Worker> workers = IntStream.range(0, threads).mapToObj(x -> {
            Worker worker = new Worker(requestsPerThread, startSignal, doneSignal, cache);
            new Thread(worker).start();
            return worker;
        }).collect(Collectors.toList());

        startSignal.countDown(); // let all threads proceed
        doneSignal.await(); // wait for all to finish

        OptionalDouble staleAverage = workers.stream().map(Worker::getMicrosStale).map(List::stream)
                .reduce(Stream.empty(), (a, b) -> Stream.concat(a, b)).mapToLong(l -> l).average();

        System.out.println("average staleness in milliseconds: "
                + staleAverage.orElseThrow(() -> new RuntimeException("No values")) / 1000);

        OptionalDouble completeTimeAverage = workers.stream().map(Worker::getMicrosToComplete).map(List::stream)
                .reduce(Stream.empty(), (a, b) -> Stream.concat(a, b)).mapToLong(l -> l).average();

        System.out.println("average to return value in milliseconds: "
                + completeTimeAverage.orElseThrow(() -> new RuntimeException("No values")) / 1000);
    }

    public static void main(String[] args) throws InterruptedException {
        int threads = 200;
        int callsPerThread = 10000;
        int maximumStalenessInMillis = 1000;

        BlockingFunction<String, Long> blockingValueComputer = k -> longBlockingComputation(k);

        System.out.println("test of throughput and staleness for computation of each value (" + threads + " threads; "
                + callsPerThread + " calls per thread)");
        testHarness(threads, callsPerThread, new NoCache<>(blockingValueComputer));

        System.out.println("");

        System.out.println("test of throughput and staleness for MaximumStalenessCache (" + maximumStalenessInMillis
                + " max staleness millis; " + threads + " threads; " + callsPerThread + " calls per thread)");
        testHarness(threads, callsPerThread,
                new MaximumStalenessCache<>(maximumStalenessInMillis, blockingValueComputer));

    }
}
