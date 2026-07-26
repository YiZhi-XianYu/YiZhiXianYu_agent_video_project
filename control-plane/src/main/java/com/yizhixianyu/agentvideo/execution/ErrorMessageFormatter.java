package com.yizhixianyu.agentvideo.execution;

final class ErrorMessageFormatter {

    static final int MAX_LENGTH = 2000;
    private static final String TRUNCATION_MARKER = "\n...[error truncated]...\n";

    private ErrorMessageFormatter() {
    }

    static String fit(String message) {
        if (message == null || message.length() <= MAX_LENGTH) {
            return message;
        }
        int remaining = MAX_LENGTH - TRUNCATION_MARKER.length();
        int headLength = remaining / 2;
        int tailLength = remaining - headLength;
        return message.substring(0, headLength)
            + TRUNCATION_MARKER
            + message.substring(message.length() - tailLength);
    }
}
