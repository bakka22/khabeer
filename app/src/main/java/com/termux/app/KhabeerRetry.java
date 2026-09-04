package com.termux.app;

import java.util.Random;

public final class KhabeerRetry {
    private static final Random RANDOM = new Random();
    private KhabeerRetry() {}

    public static long jitteredBackoff(int attempt, long baseMs, long capMs) {
        long exp = baseMs * (1L << Math.min(attempt, 10));
        long capped = Math.min(exp, capMs);
        return capped / 2 + RANDOM.nextInt((int) (capped / 2 + 1));
    }

    public static long hygieneCooldown(int streak) {
        if (streak <= 0) return 0;
        if (streak == 1) return 1000;
        if (streak == 2) return 3000;
        if (streak == 3) return 9000;
        return 3600_000;
    }

    public static boolean isRetryableHttp(int code) {
        return code == 429 || (code >= 500 && code < 600);
    }
}
