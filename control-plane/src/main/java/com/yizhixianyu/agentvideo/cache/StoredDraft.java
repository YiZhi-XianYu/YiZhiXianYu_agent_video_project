package com.yizhixianyu.agentvideo.cache;

/** Draft payload plus its optimistic-concurrency revision. */
public record StoredDraft(long revision, String json, long expiresAtEpochMs) {
    public static StoredDraft expiringIn(long revision, String json, long ttlSeconds) {
        return new StoredDraft(revision, json, System.currentTimeMillis() + Math.max(1, ttlSeconds) * 1000);
    }

    public boolean expired() {
        return expiresAtEpochMs <= System.currentTimeMillis();
    }

    public long ttlSeconds() {
        long remainingMs = expiresAtEpochMs - System.currentTimeMillis();
        return Math.max(0, (remainingMs + 999) / 1000);
    }
}
