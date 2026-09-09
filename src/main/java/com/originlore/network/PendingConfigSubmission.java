package com.originlore.network;

/** A broadcast can refresh the cache but cannot acknowledge another administrator's submission. */
public final class PendingConfigSubmission {
    private final long timeoutNanos;
    private boolean active;
    private long expectedRevision;
    private long lastActivityNanos;
    private String expectedKind;
    private String requestedLanguage;
    private long acknowledgedRevision = -1;

    public PendingConfigSubmission(long timeoutNanos) {
        if (timeoutNanos <= 0) throw new IllegalArgumentException("submission timeout must be positive");
        this.timeoutNanos = timeoutNanos;
    }

    public void start(long revision, String operation, String language, long nowNanos) {
        if (active) throw new IllegalStateException("a configuration submission is already pending");
        expectedRevision = revision;
        expectedKind = "LANGUAGE".equals(operation) ? "LANGUAGE_SAVED" : "SAVED";
        requestedLanguage = "LANGUAGE".equals(operation) ? language : null;
        acknowledgedRevision = -1;
        lastActivityNanos = nowNanos;
        active = true;
    }

    /** Returns an interface language only after the matching language transaction was saved. */
    public String accept(String kind, boolean success, long revision, String language) {
        if (!active) return null;
        if (!success) {
            clear();
            return null;
        }
        if (!expectedKind.equals(kind)) return null;
        String confirmedLanguage = requestedLanguage;
        boolean valid = revision == expectedRevision + 1
                && (requestedLanguage == null || requestedLanguage.equals(language));
        clear();
        if (!valid) throw new IllegalArgumentException("save confirmation does not match the submitted revision or language");
        acknowledgedRevision = revision;
        return confirmedLanguage;
    }

    public void touch(long nowNanos) {
        if (active) lastActivityNanos = nowNanos;
    }

    public boolean expire(long nowNanos) {
        if (!active) return false;
        long elapsed = nowNanos - lastActivityNanos;
        if (elapsed >= 0 && elapsed <= timeoutNanos) return false;
        clear();
        return true;
    }

    public boolean active() { return active; }

    public long acknowledgedRevision() { return acknowledgedRevision; }

    public void clear() {
        active = false;
        expectedKind = null;
        requestedLanguage = null;
        acknowledgedRevision = -1;
    }
}
