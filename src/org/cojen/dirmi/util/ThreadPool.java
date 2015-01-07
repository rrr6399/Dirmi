/*
 *  Copyright 2007-2010 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.dirmi.util;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedList;
import java.util.TreeSet;

import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicLong;

import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;

/**
 * Custom thread pool implementation which is slightly more efficient than the
 * default JDK1.6 thread pool and also provides scheduling services. More
 * importantly, this implementation immediately removes cancelled tasks (in
 * O(log n) time) instead of leaking memory.
 *
 * @author Brian S O'Neill
 */
public class ThreadPool extends AbstractExecutorService implements ScheduledExecutorService {
    private static final boolean LIMIT_REACHED_THREAD_DUMP;
    private static final boolean LIMIT_REACHED_SYSTEM_EXIT;

    static {
        String prefix = ThreadPool.class.getName() + ".limitReached";
        LIMIT_REACHED_THREAD_DUMP = System.getProperty(prefix + "ThreadDump", "").equals("true");
        LIMIT_REACHED_SYSTEM_EXIT = System.getProperty(prefix + "SystemExit", "").equals("true");
    }

    private static final AtomicLong cPoolNumber = new AtomicLong(1);
    static final AtomicLong cTaskNumber = new AtomicLong(1);

    private static final String SHUTDOWN_MESSAGE = "Thread pool is shutdown";

    private final AccessControlContext mContext;
    private final ThreadGroup mGroup;
    private final AtomicLong mThreadNumber = new AtomicLong(1);
    private final String mNamePrefix;
    private final boolean mDaemon;
    private final Thread.UncaughtExceptionHandler mHandler;

    private final int mMax;
    private final long mIdleTimeout = 10000;

    // Pool is accessed like a stack.
    private final LinkedList<PooledThread> mPool;

    private final HashSet<PooledThread> mAllThreads;

    private final TreeSet<Task> mScheduledTasks;
    private boolean mTaskRunnerReady;

    private int mActive;
    private boolean mShutdown;

    /**
     * @param max the maximum allowed number of threads
     * @param daemon pass true for all threads to be daemon -- they won't
     * prevent the JVM from exiting
     */
    public ThreadPool(int max, boolean daemon) {
        this(max, daemon, null, null);
    }

    /**
     * @param max the maximum allowed number of threads
     * @param daemon pass true for all threads to be daemon -- they won't
     * prevent the JVM from exiting
     * @param prefix thread name prefix; default used if null
     */
    public ThreadPool(int max, boolean daemon, String prefix) {
        this(max, daemon, prefix, null);
    }

    /**
     * @param max the maximum allowed number of threads
     * @param daemon pass true for all threads to be daemon -- they won't
     * prevent the JVM from exiting
     * @param prefix thread name prefix; default used if null
     * @param handler optional uncaught exception handler
     */
    public ThreadPool(int max, boolean daemon, String prefix,
                      Thread.UncaughtExceptionHandler handler)
    {
        if (max <= 0) {
            throw new IllegalArgumentException
                ("Maximum number of threads must be greater than zero: " + max);
        }

        mContext = AccessController.getContext();

        SecurityManager s = System.getSecurityManager();
        mGroup = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
        if (prefix == null) {
            prefix = "pool";
        }
        mNamePrefix = prefix + '-' + cPoolNumber.getAndIncrement() + "-thread-";
        mDaemon = daemon;
        mHandler = handler;

        mMax = max;
        mPool = new LinkedList<PooledThread>();
        mAllThreads = new HashSet<PooledThread>();

        mScheduledTasks = new TreeSet<Task>();
    }

    @Override
    public void execute(Runnable command) throws RejectedExecutionException {
        execute(command, false);
    }

    private void execute(Runnable command, boolean force) throws RejectedExecutionException {
        if (command == null) {
            throw new NullPointerException("Command is null");
        }

        PooledThread thread;

        while (true) {
            find: {
                synchronized (mPool) {
                    if (!force && mShutdown) {
                        throw new RejectedExecutionException(SHUTDOWN_MESSAGE);
                    }
                    if (!mPool.isEmpty()) {
                        thread = mPool.removeLast();
                        break find;
                    }
                    if (mActive >= mMax) {
                        limitReached();
                    }
                    // Create a new thread if the number of active threads
                    // is less than the maximum allowed.
                    mActive++;
                    // Create outside synchronized block.
                }

                try {
                    thread = startNewPooledThread(command);
                } catch (Error e) {
                    mActive--;
                    throw e;
                }
                return;
            }

            try {
                if (thread.setCommand(command)) {
                    return;
                }
            } catch (IllegalStateException e) {
                if (isShutdown()) {
                    // Cannot set command because thread is forced to run Shutdown.
                    throw new RejectedExecutionException(SHUTDOWN_MESSAGE);
                }
                throw e;
            }
            
            // Couldn't set the command because the pooled thread is exiting.
            // Wait for it to exit to ensure that the active count is less
            // than the maximum and try to obtain another thread.
            try {
                thread.join();
            } catch (InterruptedException e) {
                throw new RejectedExecutionException(e);
            }
        }
    }

    private void limitReached() {
        String message = "Too many active threads: " + mMax;

        if (LIMIT_REACHED_THREAD_DUMP) {
            System.err.println(new java.util.Date() + ": " + message +
                               "; dumping current thread and all pooled threads");
            Thread current = Thread.currentThread();
            dump(System.err, current);
            synchronized (mAllThreads) {
                for (PooledThread t : mAllThreads) {
                    if (t != current) {
                        dump(System.err, t);
                    }
                }
            }
        }

        if (LIMIT_REACHED_SYSTEM_EXIT) {
            Exception e = new RejectedExecutionException(message + "; exiting");
            try {
                Thread t = Thread.currentThread();
                t.getUncaughtExceptionHandler().uncaughtException(t, e);
                System.exit(1);
            } catch (Throwable e2) {
                // Cannot exit or excpetion handler is broken. Fall through and
                // throw an exception.
            }
        }

        throw new RejectedExecutionException(message);
    }

    private static void dump(java.io.PrintStream out, Thread t) {
        out.println('"' + t.getName() + "\" state=" + t.getState());
        try {
            StackTraceElement[] trace = t.getStackTrace();
            for (StackTraceElement element : trace) {
                out.println("\t at " + element);
            }
        } catch (SecurityException e) {
            out.println(e);
        }
    }

    @Override
    public ScheduledFuture<?> schedule(final Runnable command, long delay, TimeUnit unit) {
        return new Task<Object>(Executors.callable(command), delay, 0, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return new Task<V>(callable, delay, 0, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command,
                                                  long initialDelay,
                                                  long period,
                                                  TimeUnit unit)
    {
        if (period <= 0) {
            throw new IllegalArgumentException();
        }
        return new Task<Object>(Executors.callable(command), initialDelay, period, unit);
    }

    /**
     * Schedules a task which executes with a randomly selected rate, for
     * applying jitter.
     *
     * @param lowPeriod low end of the random period range
     * @param highPeriod high end of the random period range
     */
    public ScheduledFuture<?> scheduleAtRandomRate(Runnable command,
                                                   long initialDelay,
                                                   long lowPeriod,
                                                   long highPeriod,
                                                   TimeUnit unit)
    {
        if (lowPeriod < 0 || highPeriod <= 0 || lowPeriod > highPeriod) {
            throw new IllegalArgumentException();
        }
        Callable<Object> callable = Executors.callable(command);
        if (lowPeriod == highPeriod) {
            return new Task<Object>(callable, initialDelay, lowPeriod, unit);
        } else {
            return new JitterTask<Object>(callable, initialDelay, lowPeriod, highPeriod, unit);
        }
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command,
                                                     long initialDelay,
                                                     long delay,
                                                     TimeUnit unit)
    {
        if (delay <= 0) {
            throw new IllegalArgumentException();
        }
        return new Task<Object>(Executors.callable(command), initialDelay, -delay, unit);
    }

    @Override
    public void shutdown() {
        synchronized (mPool) {
            if (!mShutdown) {
                mShutdown = true;
                Runnable shutdown = new Shutdown();
                for (PooledThread thread : mPool) {
                    thread.setCommand(shutdown);
                }
            }
            mPool.notifyAll();
        }
        synchronized (mScheduledTasks) {
            mScheduledTasks.clear();
            mScheduledTasks.notifyAll();
        }
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown();

        synchronized (mAllThreads) {
            for (Thread thread : mAllThreads) {
                thread.interrupt();
            }
        }

        // Implementation has no queue, so nothing to return. Scheduled tasks
        // should not be returned, because it would imply that they are to be
        // invoked immediately.
        return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
        synchronized (mPool) {
            return mShutdown;
        }
    }

    @Override
    public boolean isTerminated() {
        synchronized (mPool) {
            return mShutdown && mActive <= 0;
        }
    }

    @Override
    public boolean awaitTermination(long time, TimeUnit unit) throws InterruptedException {
        if (time < 0) {
            return false;
        }

        synchronized (mPool) {
            if (isTerminated()) {
                return true;
            }

            if (time == 0) {
                return false;
            }

            long start = System.nanoTime();
            long nanos = unit.toNanos(time);

            do {
                mPool.wait(roundNanosToMillis(nanos));
                long now = System.nanoTime();
                if ((nanos -= now - start) <= 0) {
                    return isTerminated();
                }
                start = now;
            } while (!isTerminated());
        }

        return true;
    }

    private static long roundNanosToMillis(long nanos) {
        if (nanos <= (Long.MAX_VALUE - 999999)) {
            nanos += 999999;
        }
        return nanos / 1000000;
    }

    void threadAvailable(PooledThread thread) {
        synchronized (mPool) {
            mPool.addLast(thread);
            mPool.notify();
        }
    }

    void threadExiting(PooledThread thread) {
        synchronized (mPool) {
            mPool.remove(thread);
            mActive--;
            mPool.notify();
        }
        synchronized (mAllThreads) {
            mAllThreads.remove(thread);
        }
    }

    /**
     * @throws RejectedExecutionException only if shutdown
     */
    void scheduleTask(Task<?> task) {
        if (isShutdown()) {
            throw new RejectedExecutionException(SHUTDOWN_MESSAGE);
        }
        synchronized (mScheduledTasks) {
            if (!mScheduledTasks.add(task)) {
                throw new InternalError();
            }
            if (mScheduledTasks.first() == task) {
                if (mTaskRunnerReady) {
                    mScheduledTasks.notify();
                } else {
                    TaskRunner runner = new TaskRunner();
                    try {
                        execute(runner, true);
                        mTaskRunnerReady = true;
                    } catch (RejectedExecutionException e) {
                        // Task is scheduled as soon as a thread becomes available.
                    }
                }
            }
        }
    }

    TaskRunner needsTaskRunner() {
        synchronized (mScheduledTasks) {
            if (!mTaskRunnerReady && !mScheduledTasks.isEmpty()) {
                TaskRunner runner = new TaskRunner();
                mTaskRunnerReady = true;
                return runner;
            }
        }
        return null;
    }

    void removeTask(Task<?> task) {
        synchronized (mScheduledTasks) {
            mScheduledTasks.remove(task);
            if (mScheduledTasks.isEmpty()) {
                mScheduledTasks.notifyAll();
            }
        }
    }

    void runNextScheduledTask() {
        Task<?> task;
        boolean replaced;

        synchronized (mScheduledTasks) {
            while (true) {
                if (mScheduledTasks.isEmpty()) {
                    mTaskRunnerReady = false;
                    return;
                }

                task = mScheduledTasks.first();
                long delay = task.getAtNanos() - System.nanoTime();

                if (delay <= 0) {
                    mScheduledTasks.remove(task);
                    try {
                        execute(new TaskRunner(), true);
                        replaced = true;
                    } catch (RejectedExecutionException e) {
                        // Task is scheduled as soon as a thread becomes available.
                        mTaskRunnerReady = false;
                        replaced = false;
                    }
                    break;
                }

                try {
                    mScheduledTasks.wait(roundNanosToMillis(delay));
                } catch (InterruptedException e) {
                    // Clear the interrupted status.
                    Thread.interrupted();
                    e = null;
                }
            }
        }

        try {
            task.run();

            if (replaced) {
                // Run any more tasks which need to be run
                // immediately, to avoid having to switch context.
                while (true) {
                    synchronized (mScheduledTasks) {
                        if (mScheduledTasks.isEmpty()) {
                            break;
                        }
                        task = mScheduledTasks.first();
                        if ((task.getAtNanos() - System.nanoTime()) > 0) {
                            break;
                        }
                        mScheduledTasks.remove(task);
                    }

                    // Clear the interrupted state, if set by previous task execution.
                    Thread.interrupted();

                    task.run();
                }
            }
        } catch (Throwable e) {
            Thread t = Thread.currentThread();
            t.getUncaughtExceptionHandler().uncaughtException(t, e);
        }
    }

    private PooledThread startNewPooledThread(Runnable command) {
        PooledThread thread = new PooledThread
            (mGroup, mNamePrefix + mThreadNumber.getAndIncrement(), mContext, command);

        if (thread.isDaemon() != mDaemon) {
            thread.setDaemon(mDaemon);
        }
        if (thread.getPriority() != Thread.NORM_PRIORITY) {
            thread.setPriority(Thread.NORM_PRIORITY);
        }
        if (mHandler != null) {
            thread.setUncaughtExceptionHandler(mHandler);
        }

        synchronized (mAllThreads) {
            mAllThreads.add(thread);
        }

        try {
            thread.start();
        } catch (Error e) {
            synchronized (mAllThreads) {
                mAllThreads.remove(thread);
            }
            throw e;
        }

        return thread;
    }

    private class PooledThread extends Thread {
        private final AccessControlContext mContext;

        private Runnable mCommand;
        private boolean mExiting;

        public PooledThread(ThreadGroup group, String name,
                            AccessControlContext context, Runnable command)
        {
            super(group, null, name);
            mContext = context;
            mCommand = command;
        }

        synchronized boolean setCommand(Runnable command) {
            if (mCommand != null) {
                throw new IllegalStateException("Command in pooled thread is already set");
            }
            if (mExiting) {
                return false;
            } else {
                mCommand = command;
                notify();
                return true;
            }
        }

        private synchronized Runnable waitForCommand() throws InterruptedException {
            Runnable command;
            
            if ((command = mCommand) == null) {
                long idle = mIdleTimeout;
                
                if (idle != 0) {
                    if (idle < 0) {
                        wait(0);
                    } else {
                        wait(idle);
                    }
                }
                    
                if ((command = mCommand) == null) {
                    mExiting = true;
                }
            }

            mCommand = null;
            return command;
        }

        @Override
        public void run() {
            AccessController.doPrivileged(new PrivilegedAction<Object>() {
                public Object run() {
                    run0();
                    return null;
                }
            }, mContext);
        }

        void run0() {
            try {
                while (!isShutdown()) {
                    if (Thread.interrupted()) {
                        continue;
                    }

                    Runnable command;

                    try {
                        if ((command = waitForCommand()) == null) {
                            break;
                        }
                    } catch (InterruptedException e) {
                        e = null;
                        continue;
                    }

                    do {
                        try {
                            command.run();
                        } catch (Throwable e) {
                            if (!(command instanceof Shutdown)) {
                                getUncaughtExceptionHandler().uncaughtException(this, e);
                            }
                            e = null;
                        }
                    } while ((command = needsTaskRunner()) != null);

                    threadAvailable(this);
                }
            } finally {
                threadExiting(this);
            }
        }
    }

    private class Task<V> extends FutureTask<V> implements ScheduledFuture<V> {
        private final long mNum;
        final long mPeriodNanos;

        volatile long mAtNanos;

        /**
         * @param period Period for repeating tasks. A positive value indicates
         * fixed-rate execution. A negative value indicates fixed-delay
         * execution. A value of 0 indicates a non-repeating task.
         */
        Task(Callable<V> callable, long initialDelay, long period, TimeUnit unit) {
            super(callable);

            long periodNanos;
            if (period == 0) {
                periodNanos = 0;
            } else if ((periodNanos = unit.toNanos(period)) == 0) {
                // Account for any rounding error.
                periodNanos = period < 0 ? -1 : 1;
            }
            mPeriodNanos = periodNanos;

            mNum = cTaskNumber.getAndIncrement();

            long atNanos = System.nanoTime();
            if (initialDelay > 0) {
                atNanos += unit.toNanos(initialDelay);
            }
            mAtNanos = atNanos;

            start();
        }

        void start() {
            scheduleTask(this);
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(mAtNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed delayed) {
            if (this == delayed) {
                return 0;
            }
            if (delayed instanceof Task) {
                Task<?> other = (Task<?>) delayed;
                long diff = mAtNanos - other.mAtNanos;
                if (diff < 0) {
                    return -1;
                } else if (diff > 0) {
                    return 1;
                } else if (mNum < other.mNum) {
                    return -1;
                } else {
                    return 1;
                }
            }
            long diff = getDelay(TimeUnit.NANOSECONDS) - delayed.getDelay(TimeUnit.NANOSECONDS);
            return diff == 0 ? 0 : (diff < 0 ? -1 : 1);
        }

        @Override
        public void run() {
            removeTask(this);
            long periodNanos = mPeriodNanos;
            if (periodNanos == 0) {
                super.run();
            } else if (super.runAndReset()) {
                if (periodNanos > 0) {
                    mAtNanos += periodNanos;
                } else {
                    mAtNanos = System.nanoTime() - periodNanos;
                }
                try {
                    scheduleTask(this);
                } catch (RejectedExecutionException e) {
                    // Shutdown.
                }
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            removeTask(this);
            return super.cancel(mayInterruptIfRunning);
        }

        long getAtNanos() {
            return mAtNanos;
        }

        @Override
        public String toString() {
            StringBuilder b = new StringBuilder()
                .append("ScheduledFuture {delayNanos=")
                .append(String.valueOf(getDelay(TimeUnit.NANOSECONDS)));
            if (mPeriodNanos != 0) {
                b.append(", periodNanos=").append(String.valueOf(mPeriodNanos));
            }
            return b.append('}').toString();
        }
    }

    static int randomInt(int n) {
        // See "Xorshift RNGs" by George Marsaglia for why these numbers were chosen.
        n ^= (n << 13);
        n ^= (n >>> 17);
        n ^= (n << 5);
        return n;
    }

    private class JitterTask<V> extends Task<V> {
        private final long mRangeNanos;

        private int mRandom;

        JitterTask(Callable<V> callable, long initialDelay,
                   long lowPeriod, long highPeriod, TimeUnit unit)
        {
            super(callable, initialDelay, lowPeriod, unit);
            mRangeNanos = unit.toNanos(highPeriod - lowPeriod);
            while ((mRandom = Random.randomInt()) == 0);
            super.start();
        }

        @Override
        void start() {
        }

        @Override
        public void run() {
            removeTask(this);
            if (super.runAndReset()) {
                mAtNanos += mPeriodNanos + randomLong(mRangeNanos);
                try {
                    scheduleTask(this);
                } catch (RejectedExecutionException e) {
                    // Shutdown.
                }
            }
        }

        @Override
        public String toString() {
            return new StringBuilder()
                .append("ScheduledFuture {delayNanos=")
                .append(String.valueOf(getDelay(TimeUnit.NANOSECONDS)))
                .append(", lowPeriodNanos=").append(String.valueOf(mPeriodNanos))
                .append(", highPeriodNanos=").append(String.valueOf(mPeriodNanos + mRangeNanos))
                .append('}').toString();
        }

        private long randomLong(final long n) {
            int n2 = mRandom;
            long bits, val;
            do {
                int n1 = randomInt(n2);
                n2 = randomInt(n1);
                bits = (((((long) n1) << 32) + (long) n2) >>> 1);
                val = bits % n;
            } while (bits - val + n - 1 < 0);

            mRandom = n2;
            return val;
        }
    }

    private class TaskRunner implements Runnable {
        @Override
        public void run() {
            runNextScheduledTask();
        }
    }

    private static class Shutdown implements Runnable {
        @Override
        public void run() {
            throw new ThreadDeath();
        }
    }
}
