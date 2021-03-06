/*******************************************************************************
 *                 jMCS project ( http://www.jmmc.fr/dev/jmcs )
 *******************************************************************************
 * Copyright (c) 2013, CNRS. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     - Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     - Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     - Neither the name of the CNRS nor the names of its contributors may be
 *       used to endorse or promote products derived from this software without
 *       specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL CNRS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
package fr.jmmc.jmcs.util.concurrent;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fixed Thread pool executor that clears interrupted flag in afterExecute()
 * to avoid JDK 1.5 creating new threads
 *
 * @author Laurent BOURGES.
 */
public class FixedThreadPoolExecutor extends ThreadPoolExecutor {

    /** Class _logger */
    private static final Logger logger = LoggerFactory.getLogger(FixedThreadPoolExecutor.class.getName());
    /** flag to log debugging information */
    private final static boolean DEBUG_FLAG = false;
    /* members */
    /** running worker counter */
    private final AtomicInteger _runningWorkerCounter = new AtomicInteger(0);
    /** waiters */
    private final ConcurrentLinkedQueue<Thread> _waiters = new ConcurrentLinkedQueue<Thread>();

    /**
     * Create the Fixed Thread pool executor
     * @param nThreads the number of threads in the pool
     * @param threadFactory the factory to use when creating new threads
     */
    protected FixedThreadPoolExecutor(final int nThreads, final ThreadFactory threadFactory) {
        super(nThreads, nThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), threadFactory);
        // Create thread(s) now:
        prestartAllCoreThreads();
    }

    /**
     * Method invoked prior to executing the given Runnable in the given thread:
     * - increments the running worker counter
     *
     * @param t the thread that will run task r.
     * @param r the task that will be executed.
     */
    @Override
    protected void beforeExecute(final Thread t, final Runnable r) {
        if (DEBUG_FLAG) {
            logger.debug("beforeExecute: {}", r);
        }
        incRunningWorkerCounter();
    }

    /**
     * Method invoked upon completion of execution of the given Runnable:
     * - decrements the running worker counter
     * - clears interrupted flag in afterExecute() to avoid JDK 1.5 creating new threads
     *
     * @param r the runnable that has completed.
     * @param t the exception that caused termination, or null if execution
     * completed normally.
     */
    @Override
    protected void afterExecute(final Runnable r, final Throwable t) {
        if (DEBUG_FLAG) {
            logger.debug("afterExecute: {}", r);
        }
        decRunningWorkerCounter();

        // clear interrupt flag:
        // this avoid JDK 1.5 ThreadPoolExecutor to kill current thread and create new threads
        Thread.interrupted();
    }

    /**
     * Blocks the calling thread to wait for task termination ie
     * while the running worker counter > 0
     */
    public void waitForTaskFinished() {
        boolean wasInterrupted = false;

        final Thread current = Thread.currentThread();
        _waiters.add(current);

        if (DEBUG_FLAG) {
            logger.debug("Waiting on tasks: {}", current.getName());
        }

        // Block while the running worker counter > 0
        while (_waiters.peek() != current || !_runningWorkerCounter.compareAndSet(0, 0)) {
            if (DEBUG_FLAG) {
                logger.debug("park: {}", current.getName());
            }
            LockSupport.park(this);
            if (Thread.interrupted()) // ignore interrupts while waiting
            {
                logger.info("waiter interrupted: {}", current.getName());
                wasInterrupted = true;
            }
        }
        _waiters.remove();
        if (DEBUG_FLAG) {
            logger.debug("waiter removed: {}", current.getName());
        }
        if (wasInterrupted) // reassert interrupt status on exit
        {
            current.interrupt();
        }
    }

    /**
     * Return true if there is at least one worker running
     *
     * @return true if there is at least one worker running
     */
    public boolean isTaskRunning() {
        return _runningWorkerCounter.get() > 0;
    }

    /**
     * Increment the counter of running worker
     */
    void incRunningWorkerCounter() {
        final int count = _runningWorkerCounter.incrementAndGet();
        if (DEBUG_FLAG) {
            logger.debug("runningWorkerCounter: {}", count);
        }
    }

    /**
     * Decrement the counter of running worker
     */
    void decRunningWorkerCounter() {
        final int count = _runningWorkerCounter.decrementAndGet();
        if (DEBUG_FLAG) {
            logger.debug("runningWorkerCounter: {}", count);
        }

        if (count == 0) {
            final Thread waiter = _waiters.peek();
            if (waiter != null) {
                if (DEBUG_FLAG) {
                    logger.debug("unpark: {}", waiter.getName());
                }
                LockSupport.unpark(waiter);
            }
        }
    }
}
