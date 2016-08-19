/**
 * Copyright 2016 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */

package io.reactivex.subscribers;

import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.junit.*;
import org.reactivestreams.*;

import io.reactivex.*;
import io.reactivex.exceptions.TestException;
import io.reactivex.internal.subscriptions.*;
import io.reactivex.plugins.RxJavaPlugins;
import io.reactivex.schedulers.Schedulers;

public class SerializedObserverTest {

    Subscriber<String> observer;

    @Before
    public void before() {
        observer = TestHelper.mockSubscriber();
    }

    private Subscriber<String> serializedSubscriber(Subscriber<String> o) {
        return new SerializedSubscriber<String>(o);
    }

    @Test
    public void testSingleThreadedBasic() {
        TestSingleThreadedObservable onSubscribe = new TestSingleThreadedObservable("one", "two", "three");
        Flowable<String> w = Flowable.unsafeCreate(onSubscribe);

        Subscriber<String> aw = serializedSubscriber(observer);

        w.subscribe(aw);
        onSubscribe.waitToFinish();

        verify(observer, times(1)).onNext("one");
        verify(observer, times(1)).onNext("two");
        verify(observer, times(1)).onNext("three");
        verify(observer, never()).onError(any(Throwable.class));
        verify(observer, times(1)).onComplete();
        // non-deterministic because unsubscribe happens after 'waitToFinish' releases
        // so commenting out for now as this is not a critical thing to test here
        //            verify(s, times(1)).unsubscribe();
    }

    @Test
    public void testMultiThreadedBasic() {
        TestMultiThreadedObservable onSubscribe = new TestMultiThreadedObservable("one", "two", "three");
        Flowable<String> w = Flowable.unsafeCreate(onSubscribe);

        BusySubscriber busySubscriber = new BusySubscriber();
        Subscriber<String> aw = serializedSubscriber(busySubscriber);

        w.subscribe(aw);
        onSubscribe.waitToFinish();

        assertEquals(3, busySubscriber.onNextCount.get());
        assertFalse(busySubscriber.onError);
        assertTrue(busySubscriber.onCompleted);
        // non-deterministic because unsubscribe happens after 'waitToFinish' releases
        // so commenting out for now as this is not a critical thing to test here
        //            verify(s, times(1)).unsubscribe();

        // we can have concurrency ...
        assertTrue(onSubscribe.maxConcurrentThreads.get() > 1);
        // ... but the onNext execution should be single threaded
        assertEquals(1, busySubscriber.maxConcurrentThreads.get());
    }

    @Test(timeout = 1000)
    public void testMultiThreadedWithNPE() throws InterruptedException {
        TestMultiThreadedObservable onSubscribe = new TestMultiThreadedObservable("one", "two", "three", null);
        Flowable<String> w = Flowable.unsafeCreate(onSubscribe);

        BusySubscriber busySubscriber = new BusySubscriber();
        Subscriber<String> aw = serializedSubscriber(busySubscriber);

        w.subscribe(aw);
        onSubscribe.waitToFinish();
        busySubscriber.terminalEvent.await();

        System.out.println("OnSubscribe maxConcurrentThreads: " + onSubscribe.maxConcurrentThreads.get() + "  Subscriber maxConcurrentThreads: " + busySubscriber.maxConcurrentThreads.get());

        // we can't know how many onNext calls will occur since they each run on a separate thread
        // that depends on thread scheduling so 0, 1, 2 and 3 are all valid options
        // assertEquals(3, busySubscriber.onNextCount.get());
        assertTrue(busySubscriber.onNextCount.get() < 4);
        assertTrue(busySubscriber.onError);
        // no onCompleted because onError was invoked
        assertFalse(busySubscriber.onCompleted);
        // non-deterministic because unsubscribe happens after 'waitToFinish' releases
        // so commenting out for now as this is not a critical thing to test here
        //verify(s, times(1)).unsubscribe();

        // we can have concurrency ...
        assertTrue(onSubscribe.maxConcurrentThreads.get() > 1);
        // ... but the onNext execution should be single threaded
        assertEquals(1, busySubscriber.maxConcurrentThreads.get());
    }

    @Test
    public void testMultiThreadedWithNPEinMiddle() {
        int n = 10;
        for (int i = 0; i < n; i++) {
            TestMultiThreadedObservable onSubscribe = new TestMultiThreadedObservable("one", "two", "three", null, 
                    "four", "five", "six", "seven", "eight", "nine");
            Flowable<String> w = Flowable.unsafeCreate(onSubscribe);

            BusySubscriber busySubscriber = new BusySubscriber();
            Subscriber<String> aw = serializedSubscriber(busySubscriber);

            w.subscribe(aw);
            onSubscribe.waitToFinish();

            System.out.println("OnSubscribe maxConcurrentThreads: " + onSubscribe.maxConcurrentThreads.get() + "  Subscriber maxConcurrentThreads: " + busySubscriber.maxConcurrentThreads.get());

            // we can have concurrency ...
            assertTrue(onSubscribe.maxConcurrentThreads.get() > 1);
            // ... but the onNext execution should be single threaded
            assertEquals(1, busySubscriber.maxConcurrentThreads.get());

            // this should not be the full number of items since the error should stop it before it completes all 9
            System.out.println("onNext count: " + busySubscriber.onNextCount.get());
            assertFalse(busySubscriber.onCompleted);
            assertTrue(busySubscriber.onError);
            assertTrue(busySubscriber.onNextCount.get() < 9);
            // no onCompleted because onError was invoked
            // non-deterministic because unsubscribe happens after 'waitToFinish' releases
            // so commenting out for now as this is not a critical thing to test here
            // verify(s, times(1)).unsubscribe();
        }
    }

    /**
     * A non-realistic use case that tries to expose thread-safety issues by throwing lots of out-of-order
     * events on many threads.
     */
    @Test
    public void runOutOfOrderConcurrencyTest() {
        ExecutorService tp = Executors.newFixedThreadPool(20);
        try {
            TestConcurrencySubscriber tw = new TestConcurrencySubscriber();
            // we need Synchronized + SafeSubscriber to handle synchronization plus life-cycle
            Subscriber<String> w = serializedSubscriber(new SafeSubscriber<String>(tw));

            Future<?> f1 = tp.submit(new OnNextThread(w, 12000));
            Future<?> f2 = tp.submit(new OnNextThread(w, 5000));
            Future<?> f3 = tp.submit(new OnNextThread(w, 75000));
            Future<?> f4 = tp.submit(new OnNextThread(w, 13500));
            Future<?> f5 = tp.submit(new OnNextThread(w, 22000));
            Future<?> f6 = tp.submit(new OnNextThread(w, 15000));
            Future<?> f7 = tp.submit(new OnNextThread(w, 7500));
            Future<?> f8 = tp.submit(new OnNextThread(w, 23500));

            Future<?> f10 = tp.submit(new CompletionThread(w, TestConcurrencySubscriberEvent.onCompleted, f1, f2, f3, f4));
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                // ignore
            }
            Future<?> f11 = tp.submit(new CompletionThread(w, TestConcurrencySubscriberEvent.onCompleted, f4, f6, f7));
            Future<?> f12 = tp.submit(new CompletionThread(w, TestConcurrencySubscriberEvent.onCompleted, f4, f6, f7));
            Future<?> f13 = tp.submit(new CompletionThread(w, TestConcurrencySubscriberEvent.onCompleted, f4, f6, f7));
            Future<?> f14 = tp.submit(new CompletionThread(w, TestConcurrencySubscriberEvent.onCompleted, f4, f6, f7));
            // // the next 4 onError events should wait on same as f10
            Future<?> f15 = tp.submit(new CompletionThread(w, TestConcurrencySubscriberEvent.onError, f1, f2, f3, f4));
            Future<?> f16 = tp.submit(new CompletionThread(w, TestConcurrencySubscriberEvent.onError, f1, f2, f3, f4));
            Future<?> f17 = tp.submit(new CompletionThread(w, TestConcurrencySubscriberEvent.onError, f1, f2, f3, f4));
            Future<?> f18 = tp.submit(new CompletionThread(w, TestConcurrencySubscriberEvent.onError, f1, f2, f3, f4));

            waitOnThreads(f1, f2, f3, f4, f5, f6, f7, f8, f10, f11, f12, f13, f14, f15, f16, f17, f18);
            @SuppressWarnings("unused")
            int numNextEvents = tw.assertEvents(null); // no check of type since we don't want to test barging results here, just interleaving behavior
            //            System.out.println("Number of events executed: " + numNextEvents);
        } catch (Throwable e) {
            fail("Concurrency test failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            tp.shutdown();
            try {
                tp.awaitTermination(5000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @Test
    public void runConcurrencyTest() {
        ExecutorService tp = Executors.newFixedThreadPool(20);
        try {
            TestConcurrencySubscriber tw = new TestConcurrencySubscriber();
            // we need Synchronized + SafeSubscriber to handle synchronization plus life-cycle
            Subscriber<String> w = serializedSubscriber(new SafeSubscriber<String>(tw));
            w.onSubscribe(EmptySubscription.INSTANCE);

            Future<?> f1 = tp.submit(new OnNextThread(w, 12000));
            Future<?> f2 = tp.submit(new OnNextThread(w, 5000));
            Future<?> f3 = tp.submit(new OnNextThread(w, 75000));
            Future<?> f4 = tp.submit(new OnNextThread(w, 13500));
            Future<?> f5 = tp.submit(new OnNextThread(w, 22000));
            Future<?> f6 = tp.submit(new OnNextThread(w, 15000));
            Future<?> f7 = tp.submit(new OnNextThread(w, 7500));
            Future<?> f8 = tp.submit(new OnNextThread(w, 23500));

            // 12000 + 5000 + 75000 + 13500 + 22000 + 15000 + 7500 + 23500 = 173500

            Future<?> f10 = tp.submit(new CompletionThread(w, TestConcurrencySubscriberEvent.onCompleted, f1, f2, f3, f4, f5, f6, f7, f8));
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                // ignore
            }

            waitOnThreads(f1, f2, f3, f4, f5, f6, f7, f8, f10);
            int numNextEvents = tw.assertEvents(null); // no check of type since we don't want to test barging results here, just interleaving behavior
            assertEquals(173500, numNextEvents);
            // System.out.println("Number of events executed: " + numNextEvents);
        } catch (Throwable e) {
            fail("Concurrency test failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            tp.shutdown();
            try {
                tp.awaitTermination(25000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Test that a notification does not get delayed in the queue waiting for the next event to push it through.
     * 
     * @throws InterruptedException
     */
    @Ignore("this is non-deterministic ... haven't figured out what's wrong with the test yet (benjchristensen: July 2014)")
    @Test
    public void testNotificationDelay() throws InterruptedException {
        ExecutorService tp1 = Executors.newFixedThreadPool(1);
        ExecutorService tp2 = Executors.newFixedThreadPool(1);
        try {
            int n = 10;
            for (int i = 0; i < n; i++) {
                final CountDownLatch firstOnNext = new CountDownLatch(1);
                final CountDownLatch onNextCount = new CountDownLatch(2);
                final CountDownLatch latch = new CountDownLatch(1);
                final CountDownLatch running = new CountDownLatch(2);

                TestSubscriber<String> to = new TestSubscriber<String>(new DefaultSubscriber<String>() {

                    @Override
                    public void onComplete() {

                    }

                    @Override
                    public void onError(Throwable e) {

                    }

                    @Override
                    public void onNext(String t) {
                        firstOnNext.countDown();
                        // force it to take time when delivering so the second one is enqueued
                        try {
                            latch.await();
                        } catch (InterruptedException e) {
                        }
                    }

                });
                Subscriber<String> o = serializedSubscriber(to);

                Future<?> f1 = tp1.submit(new OnNextThread(o, 1, onNextCount, running));
                Future<?> f2 = tp2.submit(new OnNextThread(o, 1, onNextCount, running));

                running.await(); // let one of the OnNextThread actually run before proceeding
                
                firstOnNext.await();

                Thread t1 = to.lastThread();
                System.out.println("first onNext on thread: " + t1);

                latch.countDown();

                waitOnThreads(f1, f2);
                // not completed yet

                assertEquals(2, to.valueCount());

                Thread t2 = to.lastThread();
                System.out.println("second onNext on thread: " + t2);

                assertSame(t1, t2);

                System.out.println(to.values());
                o.onComplete();
                System.out.println(to.values());
            }
        } finally {
            tp1.shutdown();
            tp2.shutdown();
        }
    }

    /**
     * Demonstrates thread starvation problem.
     * 
     * No solution on this for now. Trade-off in this direction as per https://github.com/ReactiveX/RxJava/issues/998#issuecomment-38959474
     * Probably need backpressure for this to work
     * 
     * When using SynchronizedSubscriber we get this output:
     * 
     * p1: 18 p2: 68 => should be close to each other unless we have thread starvation
     * 
     * When using SerializedSubscriber we get:
     * 
     * p1: 1 p2: 2445261 => should be close to each other unless we have thread starvation
     * 
     * This demonstrates how SynchronizedSubscriber balances back and forth better, and blocks emission.
     * The real issue in this example is the async buffer-bloat, so we need backpressure.
     * 
     * 
     * @throws InterruptedException
     */
    @Ignore("Demonstrates thread starvation problem. Read JavaDoc")
    @Test
    public void testThreadStarvation() throws InterruptedException {

        TestSubscriber<String> to = new TestSubscriber<String>(new DefaultSubscriber<String>() {

            @Override
            public void onComplete() {

            }

            @Override
            public void onError(Throwable e) {

            }

            @Override
            public void onNext(String t) {
                // force it to take time when delivering
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                }
            }

        });
        final Subscriber<String> o = serializedSubscriber(to);

        AtomicInteger p1 = new AtomicInteger();
        AtomicInteger p2 = new AtomicInteger();

        o.onSubscribe(EmptySubscription.INSTANCE);
        ResourceSubscriber<String> as1 = new ResourceSubscriber<String>() {
            @Override
            public void onNext(String t) {
                o.onNext(t);
            }
            
            @Override
            public void onError(Throwable t) {
                RxJavaPlugins.onError(t);
            }
            
            @Override
            public void onComplete() {
                
            }
        };
        
        ResourceSubscriber<String> as2 = new ResourceSubscriber<String>() {
            @Override
            public void onNext(String t) {
                o.onNext(t);
            }
            
            @Override
            public void onError(Throwable t) {
                RxJavaPlugins.onError(t);
            }
            
            @Override
            public void onComplete() {
                
            }
        };
        
        infinite(p1).subscribe(as1);
        infinite(p2).subscribe(as2);

        Thread.sleep(100);

        System.out.println("p1: " + p1.get() + " p2: " + p2.get() + " => should be close to each other unless we have thread starvation");
        assertEquals(p1.get(), p2.get(), 10000); // fairly distributed within 10000 of each other

        as1.dispose();
        as2.dispose();
    }

    private static void waitOnThreads(Future<?>... futures) {
        for (Future<?> f : futures) {
            try {
                f.get(20, TimeUnit.SECONDS);
            } catch (Throwable e) {
                System.err.println("Failed while waiting on future.");
                e.printStackTrace();
            }
        }
    }

    private static Flowable<String> infinite(final AtomicInteger produced) {
        return Flowable.unsafeCreate(new Publisher<String>() {

            @Override
            public void subscribe(Subscriber<? super String> s) {
                BooleanSubscription bs = new BooleanSubscription();
                s.onSubscribe(bs);
                while (!bs.isCancelled()) {
                    s.onNext("onNext");
                    produced.incrementAndGet();
                }
            }

        }).subscribeOn(Schedulers.newThread());
    }

    /**
     * A thread that will pass data to onNext
     */
    public static class OnNextThread implements Runnable {

        private final CountDownLatch latch;
        private final Subscriber<String> observer;
        private final int numStringsToSend;
        final AtomicInteger produced;
        private final CountDownLatch running;

        OnNextThread(Subscriber<String> observer, int numStringsToSend, CountDownLatch latch, CountDownLatch running) {
            this(observer, numStringsToSend, new AtomicInteger(), latch, running);
        }

        OnNextThread(Subscriber<String> observer, int numStringsToSend, AtomicInteger produced) {
            this(observer, numStringsToSend, produced, null, null);
        }

        OnNextThread(Subscriber<String> observer, int numStringsToSend, AtomicInteger produced, CountDownLatch latch, CountDownLatch running) {
            this.observer = observer;
            this.numStringsToSend = numStringsToSend;
            this.produced = produced;
            this.latch = latch;
            this.running = running;
        }

        OnNextThread(Subscriber<String> observer, int numStringsToSend) {
            this(observer, numStringsToSend, new AtomicInteger());
        }

        @Override
        public void run() {
            if (running != null) {
                running.countDown();
            }
            for (int i = 0; i < numStringsToSend; i++) {
                observer.onNext(Thread.currentThread().getId() + "-" + i);
                if (latch != null) {
                    latch.countDown();
                }
                produced.incrementAndGet();
            }
        }
    }

    /**
     * A thread that will call onError or onNext
     */
    public static class CompletionThread implements Runnable {

        private final Subscriber<String> observer;
        private final TestConcurrencySubscriberEvent event;
        private final Future<?>[] waitOnThese;

        CompletionThread(Subscriber<String> Subscriber, TestConcurrencySubscriberEvent event, Future<?>... waitOnThese) {
            this.observer = Subscriber;
            this.event = event;
            this.waitOnThese = waitOnThese;
        }

        @Override
        public void run() {
            /* if we have 'waitOnThese' futures, we'll wait on them before proceeding */
            if (waitOnThese != null) {
                for (Future<?> f : waitOnThese) {
                    try {
                        f.get();
                    } catch (Throwable e) {
                        System.err.println("Error while waiting on future in CompletionThread");
                    }
                }
            }

            /* send the event */
            if (event == TestConcurrencySubscriberEvent.onError) {
                observer.onError(new RuntimeException("mocked exception"));
            } else if (event == TestConcurrencySubscriberEvent.onCompleted) {
                observer.onComplete();

            } else {
                throw new IllegalArgumentException("Expecting either onError or onCompleted");
            }
        }
    }

    private static enum TestConcurrencySubscriberEvent {
        onCompleted, onError, onNext
    }

    private static class TestConcurrencySubscriber extends DefaultSubscriber<String> {

        /**
         * used to store the order and number of events received
         */
        private final LinkedBlockingQueue<TestConcurrencySubscriberEvent> events = new LinkedBlockingQueue<TestConcurrencySubscriberEvent>();
        private final int waitTime;

        @SuppressWarnings("unused")
        public TestConcurrencySubscriber(int waitTimeInNext) {
            this.waitTime = waitTimeInNext;
        }

        public TestConcurrencySubscriber() {
            this.waitTime = 0;
        }

        @Override
        public void onComplete() {
            events.add(TestConcurrencySubscriberEvent.onCompleted);
        }

        @Override
        public void onError(Throwable e) {
            events.add(TestConcurrencySubscriberEvent.onError);
        }

        @Override
        public void onNext(String args) {
            events.add(TestConcurrencySubscriberEvent.onNext);
            // do some artificial work to make the thread scheduling/timing vary
            int s = 0;
            for (int i = 0; i < 20; i++) {
                s += s * i;
            }

            if (waitTime > 0) {
                try {
                    Thread.sleep(waitTime);
                } catch (InterruptedException e) {
                    // ignore
                }
            }
        }

        /**
         * Assert the order of events is correct and return the number of onNext executions.
         * 
         * @param expectedEndingEvent
         * @return int count of onNext calls
         * @throws IllegalStateException
         *             If order of events was invalid.
         */
        public int assertEvents(TestConcurrencySubscriberEvent expectedEndingEvent) throws IllegalStateException {
            int nextCount = 0;
            boolean finished = false;
            for (TestConcurrencySubscriberEvent e : events) {
                if (e == TestConcurrencySubscriberEvent.onNext) {
                    if (finished) {
                        // already finished, we shouldn't get this again
                        throw new IllegalStateException("Received onNext but we're already finished.");
                    }
                    nextCount++;
                } else if (e == TestConcurrencySubscriberEvent.onError) {
                    if (finished) {
                        // already finished, we shouldn't get this again
                        throw new IllegalStateException("Received onError but we're already finished.");
                    }
                    if (expectedEndingEvent != null && TestConcurrencySubscriberEvent.onError != expectedEndingEvent) {
                        throw new IllegalStateException("Received onError ending event but expected " + expectedEndingEvent);
                    }
                    finished = true;
                } else if (e == TestConcurrencySubscriberEvent.onCompleted) {
                    if (finished) {
                        // already finished, we shouldn't get this again
                        throw new IllegalStateException("Received onCompleted but we're already finished.");
                    }
                    if (expectedEndingEvent != null && TestConcurrencySubscriberEvent.onCompleted != expectedEndingEvent) {
                        throw new IllegalStateException("Received onCompleted ending event but expected " + expectedEndingEvent);
                    }
                    finished = true;
                }
            }

            return nextCount;
        }

    }

    /**
     * This spawns a single thread for the subscribe execution
     */
    private static class TestSingleThreadedObservable implements Publisher<String> {

        final String[] values;
        private Thread t = null;

        public TestSingleThreadedObservable(final String... values) {
            this.values = values;

        }

        @Override
        public void subscribe(final Subscriber<? super String> observer) {
            observer.onSubscribe(EmptySubscription.INSTANCE);
            System.out.println("TestSingleThreadedObservable subscribed to ...");
            t = new Thread(new Runnable() {

                @Override
                public void run() {
                    try {
                        System.out.println("running TestSingleThreadedObservable thread");
                        for (String s : values) {
                            System.out.println("TestSingleThreadedObservable onNext: " + s);
                            observer.onNext(s);
                        }
                        observer.onComplete();
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                }

            });
            System.out.println("starting TestSingleThreadedObservable thread");
            t.start();
            System.out.println("done starting TestSingleThreadedObservable thread");
        }

        public void waitToFinish() {
            try {
                t.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

    }

    /**
     * This spawns a thread for the subscription, then a separate thread for each onNext call.
     */
    private static class TestMultiThreadedObservable implements Publisher<String> {

        final String[] values;
        Thread t = null;
        AtomicInteger threadsRunning = new AtomicInteger();
        AtomicInteger maxConcurrentThreads = new AtomicInteger();
        ExecutorService threadPool;

        public TestMultiThreadedObservable(String... values) {
            this.values = values;
            this.threadPool = Executors.newCachedThreadPool();
        }

        @Override
        public void subscribe(final Subscriber<? super String> observer) {
            observer.onSubscribe(EmptySubscription.INSTANCE);
            final NullPointerException npe = new NullPointerException();
            System.out.println("TestMultiThreadedObservable subscribed to ...");
            t = new Thread(new Runnable() {

                @Override
                public void run() {
                    try {
                        System.out.println("running TestMultiThreadedObservable thread");
                        int j = 0;
                        for (final String s : values) {
                            final int fj = ++j;
                            threadPool.execute(new Runnable() {

                                @Override
                                public void run() {
                                    threadsRunning.incrementAndGet();
                                    try {
                                        // perform onNext call
                                        System.out.println("TestMultiThreadedObservable onNext: " + s + " on thread " + Thread.currentThread().getName());
                                        if (s == null) {
                                            // force an error
                                            throw npe;
                                        } else {
                                             // allow the exception to queue up
                                            int sleep = (fj % 3) * 10;
                                            if (sleep != 0) {
                                                Thread.sleep(sleep);
                                            }
                                        }
                                        observer.onNext(s);
                                        // capture 'maxThreads'
                                        int concurrentThreads = threadsRunning.get();
                                        int maxThreads = maxConcurrentThreads.get();
                                        if (concurrentThreads > maxThreads) {
                                            maxConcurrentThreads.compareAndSet(maxThreads, concurrentThreads);
                                        }
                                    } catch (Throwable e) {
                                        observer.onError(e);
                                    } finally {
                                        threadsRunning.decrementAndGet();
                                    }
                                }
                            });
                        }
                        // we are done spawning threads
                        threadPool.shutdown();
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }

                    // wait until all threads are done, then mark it as COMPLETED
                    try {
                        // wait for all the threads to finish
                        if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                            System.out.println("Threadpool did not terminate in time.");
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    observer.onComplete();
                }
            });
            System.out.println("starting TestMultiThreadedObservable thread");
            t.start();
            System.out.println("done starting TestMultiThreadedObservable thread");
        }

        public void waitToFinish() {
            try {
                t.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class BusySubscriber extends DefaultSubscriber<String> {
        volatile boolean onCompleted = false;
        volatile boolean onError = false;
        AtomicInteger onNextCount = new AtomicInteger();
        AtomicInteger threadsRunning = new AtomicInteger();
        AtomicInteger maxConcurrentThreads = new AtomicInteger();
        final CountDownLatch terminalEvent = new CountDownLatch(1);

        @Override
        public void onComplete() {
            threadsRunning.incrementAndGet();
            try {
                onCompleted = true;
            } finally {
                captureMaxThreads();
                threadsRunning.decrementAndGet();
                terminalEvent.countDown();
            }
        }

        @Override
        public void onError(Throwable e) {
            System.out.println(">>>>>>>>>>>>>>>>>>>> onError received: " + e);
            threadsRunning.incrementAndGet();
            try {
                onError = true;
            } finally {
                captureMaxThreads();
                threadsRunning.decrementAndGet();
                terminalEvent.countDown();
            }
        }

        @Override
        public void onNext(String args) {
            threadsRunning.incrementAndGet();
            try {
                onNextCount.incrementAndGet();
                try {
                    // simulate doing something computational
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } finally {
                // capture 'maxThreads'
                captureMaxThreads();
                threadsRunning.decrementAndGet();
            }
        }

        protected void captureMaxThreads() {
            int concurrentThreads = threadsRunning.get();
            int maxThreads = maxConcurrentThreads.get();
            if (concurrentThreads > maxThreads) {
                maxConcurrentThreads.compareAndSet(maxThreads, concurrentThreads);
                if (concurrentThreads > 1) {
                    new RuntimeException("should not be greater than 1").printStackTrace();
                }
            }
        }

    }
    
    @Test
    @Ignore("Null values not permitted")
    public void testSerializeNull() {
        final AtomicReference<Subscriber<Integer>> serial = new AtomicReference<Subscriber<Integer>>();
        TestSubscriber<Integer> to = new TestSubscriber<Integer>() {
            @Override
            public void onNext(Integer t) {
                if (t != null && t == 0) {
                    serial.get().onNext(null);
                }
                super.onNext(t);
            }
        };
        
        SerializedSubscriber<Integer> sobs = new SerializedSubscriber<Integer>(to);
        serial.set(sobs);
        
        sobs.onNext(0);
        
        to.assertValues(0, null);
    }
    
    @Test
    @Ignore("Subscribers can't throw")
    public void testSerializeAllowsOnError() {
        TestSubscriber<Integer> to = new TestSubscriber<Integer>() {
            @Override
            public void onNext(Integer t) {
                throw new TestException();
            }
        };
        
        SerializedSubscriber<Integer> sobs = new SerializedSubscriber<Integer>(to);
        
        try {
            sobs.onNext(0);
        } catch (TestException ex) {
            sobs.onError(ex);
        }
        
        to.assertError(TestException.class);
    }
    
    @Test
    @Ignore("Null values no longer permitted")
    public void testSerializeReentrantNullAndComplete() {
        final AtomicReference<Subscriber<Integer>> serial = new AtomicReference<Subscriber<Integer>>();
        TestSubscriber<Integer> to = new TestSubscriber<Integer>() {
            @Override
            public void onNext(Integer t) {
                serial.get().onComplete();
                throw new TestException();
            }
        };
        
        SerializedSubscriber<Integer> sobs = new SerializedSubscriber<Integer>(to);
        serial.set(sobs);
        
        try {
            sobs.onNext(0);
        } catch (TestException ex) {
            sobs.onError(ex);
        }
        
        to.assertError(TestException.class);
        to.assertNotComplete();
    }
    
    @Test
    @Ignore("Subscribers can't throw")
    public void testSerializeReentrantNullAndError() {
        final AtomicReference<Subscriber<Integer>> serial = new AtomicReference<Subscriber<Integer>>();
        TestSubscriber<Integer> to = new TestSubscriber<Integer>() {
            @Override
            public void onNext(Integer t) {
                serial.get().onError(new RuntimeException());
                throw new TestException();
            }
        };
        
        SerializedSubscriber<Integer> sobs = new SerializedSubscriber<Integer>(to);
        serial.set(sobs);
        
        try {
            sobs.onNext(0);
        } catch (TestException ex) {
            sobs.onError(ex);
        }
        
        to.assertError(TestException.class);
        to.assertNotComplete();
    }
    
    @Test
    @Ignore("Null values no longer permitted")
    public void testSerializeDrainPhaseThrows() {
        final AtomicReference<Subscriber<Integer>> serial = new AtomicReference<Subscriber<Integer>>();
        TestSubscriber<Integer> to = new TestSubscriber<Integer>() {
            @Override
            public void onNext(Integer t) {
                if (t != null && t == 0) {
                    serial.get().onNext(null);
                } else
                if (t == null) {
                    throw new TestException();
                }
                super.onNext(t);
            }
        };
        
        SerializedSubscriber<Integer> sobs = new SerializedSubscriber<Integer>(to);
        serial.set(sobs);
        
        sobs.onNext(0);
        
        to.assertError(TestException.class);
        to.assertNotComplete();
    }
    
    @Test
    public void testErrorReentry() {
        final AtomicReference<Subscriber<Integer>> serial = new AtomicReference<Subscriber<Integer>>();
       
        TestSubscriber<Integer> ts = new TestSubscriber<Integer>() {
            @Override
            public void onNext(Integer v) {
                serial.get().onError(new TestException());
                serial.get().onError(new TestException());
                super.onNext(v);
            }
        };
        SerializedSubscriber<Integer> sobs = new SerializedSubscriber<Integer>(ts);
        sobs.onSubscribe(EmptySubscription.INSTANCE);
        serial.set(sobs);
        
        sobs.onNext(1);
        
        ts.assertValue(1);
        ts.assertError(TestException.class);
    }
    @Test
    public void testCompleteReentry() {
        final AtomicReference<Subscriber<Integer>> serial = new AtomicReference<Subscriber<Integer>>();
       
        TestSubscriber<Integer> ts = new TestSubscriber<Integer>() {
            @Override
            public void onNext(Integer v) {
                serial.get().onComplete();
                serial.get().onComplete();
                super.onNext(v);
            }
        };
        SerializedSubscriber<Integer> sobs = new SerializedSubscriber<Integer>(ts);
        sobs.onSubscribe(EmptySubscription.INSTANCE);
        serial.set(sobs);
        
        sobs.onNext(1);
        
        ts.assertValue(1);
        ts.assertComplete();
        ts.assertNoErrors();
    }
}