package com.yizhixianyu.agentvideo.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AuthRateLimiter {

    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);
    private static final Duration REGISTER_WINDOW = Duration.ofHours(1);
    private static final int LOGIN_LIMIT = 10;
    private static final int REGISTER_LIMIT = 5;

    private final ConcurrentHashMap<String, ArrayDeque<Instant>> attempts = new ConcurrentHashMap<>();

    public void checkLogin(HttpServletRequest request, String email) {
        check("login:" + clientAddress(request) + ":" + normalize(email), LOGIN_LIMIT, LOGIN_WINDOW);
    }

    public void recordLoginFailure(HttpServletRequest request, String email) {
        record("login:" + clientAddress(request) + ":" + normalize(email), LOGIN_WINDOW);
    }

    public void clearLoginFailures(HttpServletRequest request, String email) {
        attempts.remove("login:" + clientAddress(request) + ":" + normalize(email));
    }

    public void checkRegistration(HttpServletRequest request) {
        var key = "register:" + clientAddress(request);
        check(key, REGISTER_LIMIT, REGISTER_WINDOW);
        record(key, REGISTER_WINDOW);
    }

    private void check(String key, int limit, Duration window) {
        var queue = attempts.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            purge(queue, window);
            if (queue.size() >= limit) {
                var retryAt = queue.peekFirst().plus(window);
                throw new AuthRateLimitException(
                    "Too many authentication attempts. Please try again later.",
                    Duration.between(Instant.now(), retryAt).toSeconds()
                );
            }
        }
    }

    private void record(String key, Duration window) {
        var queue = attempts.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            purge(queue, window);
            queue.addLast(Instant.now());
        }
    }

    private void purge(ArrayDeque<Instant> queue, Duration window) {
        var cutoff = Instant.now().minus(window);
        while (!queue.isEmpty() && queue.peekFirst().isBefore(cutoff)) queue.removeFirst();
    }

    private String clientAddress(HttpServletRequest request) {
        var forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",", 2)[0].trim();
        return request.getRemoteAddr();
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
