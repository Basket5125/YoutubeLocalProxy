package com.viitube.proxy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * How much the phone running this proxy can be asked of. A YouTube answer is a megabyte of JSON
 * and holding one costs several times that while it is parsed, so the number of requests made at
 * the same time and the number of answers kept in memory are what decides whether an old phone
 * runs out of it. Both are read from the heap the phone actually gives this app.
 */
final class MemoryBudget {

    /** A phone of 2012-2014 gives an app 48 or 64 MB; a newer one hundreds. */
    static final boolean SMALL_HEAP = Runtime.getRuntime().maxMemory() < 112L * 1024 * 1024;

    private MemoryBudget() { }

    /** How many entries a cache may keep: a quarter of that on a phone with little heap. */
    static int entries(int roomy) {
        return SMALL_HEAP ? Math.max(4, roomy / 4) : roomy;
    }

    /**
     * How many lookups may run at the same time. Half of them on a phone with little heap: each
     * one holds a megabyte of JSON and several times that while it is parsed, and three or four
     * of those at once is what a 48 MB heap takes.
     */
    static int threads(int roomy) {
        return SMALL_HEAP ? Math.max(3, roomy / 2) : roomy;
    }

    /**
     * A pool for work a request waits for: at most {@code roomy} lookups run at once, the rest
     * waits in a queue of {@code waiting} tasks, and what does not fit even there runs on the
     * thread that handed it in. Nothing is thrown away, because a caller is waiting for it.
     */
    static ExecutorService pool(String name, int roomy, int priority, int waiting) {
        int size = threads(roomy);
        // Every thread counts as a core one, because a pool with a queue in front of it only
        // starts a second thread once that queue is full: the lookups a request fans out would
        // run one after another instead of side by side, which is the opposite of the point.
        ThreadPoolExecutor pool = new ThreadPoolExecutor(size, size, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(Math.max(1, waiting)), threads(name, priority),
                new ThreadPoolExecutor.CallerRunsPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * A pool for work nobody waits for, such as reading the page after the one being looked at.
     * It is deliberately small and throws away what does not fit: a page not read ahead is read
     * when it is asked for, while running it on the request's own thread would hold the answer
     * the app is waiting for.
     */
    static ExecutorService prefetchPool(String name) {
        int size = SMALL_HEAP ? 1 : 2;
        ThreadPoolExecutor pool = new ThreadPoolExecutor(size, size, 20, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(4), threads(name, Thread.MIN_PRIORITY + 2),
                new ThreadPoolExecutor.DiscardPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static ThreadFactory threads(final String name, final int priority) {
        return new ThreadFactory() {
            private int count;

            @Override
            public synchronized Thread newThread(Runnable task) {
                Thread thread = new Thread(task, name + "-" + (++count));
                thread.setDaemon(true);
                thread.setPriority(priority);
                return thread;
            }
        };
    }
}
