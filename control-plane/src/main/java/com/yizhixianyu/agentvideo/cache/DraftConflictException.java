package com.yizhixianyu.agentvideo.cache;

/** Raised when a draft save is based on an older revision. */
public class DraftConflictException extends RuntimeException {
    public DraftConflictException(String message) {
        super(message);
    }
}
