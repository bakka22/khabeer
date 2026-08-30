package com.termux.app;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class HermesInterruptManager {
    private static final Object LOCK = new Object();
    private static final Set<Long> INTERRUPTED_THREADS = new HashSet<>();
    private static final Map<Long, String> REASONS = new HashMap<>();

    private HermesInterruptManager() {}

    public static void setInterrupt(boolean active, Long threadId, String reason) {
        long tid = threadId != null ? threadId : Thread.currentThread().getId();
        synchronized (LOCK) {
            if (active) {
                INTERRUPTED_THREADS.add(tid);
                if (reason != null) REASONS.put(tid, reason);
                else REASONS.remove(tid);
            } else {
                INTERRUPTED_THREADS.remove(tid);
                REASONS.remove(tid);
            }
        }
    }

    public static boolean isInterrupted() {
        synchronized (LOCK) {
            return INTERRUPTED_THREADS.contains(Thread.currentThread().getId());
        }
    }

    public static String getReason() {
        synchronized (LOCK) {
            return REASONS.get(Thread.currentThread().getId());
        }
    }

    public static void clearCurrentThread() {
        setInterrupt(false, null, null);
    }

    public static final class ThreadAwareEventProxy {
        public boolean isSet() { return isInterrupted(); }
        public void set() { setInterrupt(true, null, null); }
        public void clear() { setInterrupt(false, null, null); }
    }

    public static final ThreadAwareEventProxy EVENT = new ThreadAwareEventProxy();
}
