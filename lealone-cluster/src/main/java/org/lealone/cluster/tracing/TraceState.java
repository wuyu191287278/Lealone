/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lealone.cluster.tracing;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.lealone.cluster.utils.ByteBufferUtil;
import org.slf4j.helpers.MessageFormatter;

import com.google.common.base.Stopwatch;

/**
 * ThreadLocal state for a tracing session. The presence of an instance of this class as a ThreadLocal denotes that an
 * operation is being traced.
 */
public class TraceState {
    public final UUID sessionId;
    public final InetAddress coordinator;
    public final Stopwatch watch;
    public final ByteBuffer sessionIdBytes;
    public final Tracing.TraceType traceType;
    public final int ttl;

    private boolean notify;
    private Object notificationHandle;

    public enum Status {
        IDLE, ACTIVE, STOPPED;
    }

    private Status status;

    // Multiple requests can use the same TraceState at a time, so we need to reference count.
    // See lealone-7626 for more details.
    private final AtomicInteger references = new AtomicInteger(1);

    public TraceState(InetAddress coordinator, UUID sessionId) {
        this(coordinator, sessionId, Tracing.TraceType.QUERY);
    }

    public TraceState(InetAddress coordinator, UUID sessionId, Tracing.TraceType traceType) {
        assert coordinator != null;
        assert sessionId != null;

        this.coordinator = coordinator;
        this.sessionId = sessionId;
        sessionIdBytes = ByteBufferUtil.bytes(sessionId);
        this.traceType = traceType;
        this.ttl = traceType.getTTL();
        watch = Stopwatch.createStarted();
        this.status = Status.IDLE;
    }

    public void enableActivityNotification() {
        assert traceType == Tracing.TraceType.REPAIR;
        notify = true;
    }

    public void setNotificationHandle(Object handle) {
        assert traceType == Tracing.TraceType.REPAIR;
        notificationHandle = handle;
    }

    public int elapsed() {
        long elapsed = watch.elapsed(TimeUnit.MICROSECONDS);
        return elapsed < Integer.MAX_VALUE ? (int) elapsed : Integer.MAX_VALUE;
    }

    public synchronized void stop() {
        status = Status.STOPPED;
        notifyAll();
    }

    /*
     * Returns immediately if there has been trace activity since the last
     * call, otherwise waits until there is trace activity, or until the
     * timeout expires.
     * @param timeout timeout in milliseconds
     * @return activity status
     */
    public synchronized Status waitActivity(long timeout) {
        if (status == Status.IDLE) {
            try {
                wait(timeout);
            } catch (InterruptedException e) {
                throw new RuntimeException();
            }
        }
        if (status == Status.ACTIVE) {
            status = Status.IDLE;
            return Status.ACTIVE;
        }
        return status;
    }

    private synchronized void notifyActivity() {
        status = Status.ACTIVE;
        notifyAll();
    }

    public void trace(String format, Object arg) {
        trace(MessageFormatter.format(format, arg).getMessage());
    }

    public void trace(String format, Object arg1, Object arg2) {
        trace(MessageFormatter.format(format, arg1, arg2).getMessage());
    }

    public void trace(String format, Object[] args) {
        trace(MessageFormatter.arrayFormat(format, args).getMessage());
    }

    public void trace(String message) {
        if (notify)
            notifyActivity();

        TraceState.trace(sessionIdBytes, message, elapsed(), ttl, notificationHandle);
    }

    public static void trace(final ByteBuffer sessionId, final String message, final int elapsed, final int ttl,
            final Object notificationHandle) {
        //        final String threadName = Thread.currentThread().getName();
        //
        //        if (notificationHandle != null)
        //            StorageService.instance.sendNotification("repair", message, notificationHandle);
        //
        //        StageManager.getStage(Stage.TRACING).execute(new WrappedRunnable()
        //        {
        //            public void runMayThrow()
        //            {
        //                Tracing.mutateWithCatch(TraceKeyspace.toEventMutation(sessionId, message, elapsed, threadName, ttl));
        //            }
        //        });
    }

    public boolean acquireReference() {
        while (true) {
            int n = references.get();
            if (n <= 0)
                return false;
            if (references.compareAndSet(n, n + 1))
                return true;
        }
    }

    public int releaseReference() {
        return references.decrementAndGet();
    }
}
