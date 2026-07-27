package com.yizhixianyu.agentvideo.auth;

public class CsrfProtectionException extends RuntimeException {
    public CsrfProtectionException(String message) {
        super(message);
    }
}
