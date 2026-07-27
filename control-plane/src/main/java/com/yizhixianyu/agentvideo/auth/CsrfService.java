package com.yizhixianyu.agentvideo.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

@Service
public class CsrfService {

    public static final String COOKIE_NAME = "avp_csrf";
    public static final String HEADER_NAME = "X-CSRF-Token";

    private final SecureRandom secureRandom = new SecureRandom();
    private final boolean secureCookie;
    private final Duration cookieDuration;

    public CsrfService(
        @Value("${app.auth.secure-cookie:false}") boolean secureCookie,
        @Value("${app.auth.session-days:30}") long sessionDays
    ) {
        this.secureCookie = secureCookie;
        this.cookieDuration = Duration.ofDays(Math.max(1, sessionDays));
    }

    public String ensureToken(HttpServletRequest request) {
        var existing = readCookie(request);
        return existing == null || existing.isBlank() ? newToken() : existing;
    }

    public String issueToken() {
        return newToken();
    }

    public void validate(HttpServletRequest request) {
        var cookieToken = readCookie(request);
        var headerToken = request.getHeader(HEADER_NAME);
        if (cookieToken == null || headerToken == null || !constantTimeEquals(cookieToken, headerToken)) {
            throw new CsrfProtectionException("CSRF token is missing or invalid");
        }
    }

    public ResponseCookie cookie(String token) {
        return ResponseCookie.from(COOKIE_NAME, token)
            .httpOnly(false)
            .secure(secureCookie)
            .sameSite("Lax")
            .path("/")
            .maxAge(cookieDuration)
            .build();
    }

    public ResponseCookie clearCookie() {
        return ResponseCookie.from(COOKIE_NAME, "")
            .httpOnly(false)
            .secure(secureCookie)
            .sameSite("Lax")
            .path("/")
            .maxAge(Duration.ZERO)
            .build();
    }

    private String readCookie(HttpServletRequest request) {
        if (request == null) return null;
        var cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private String newToken() {
        var bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private boolean constantTimeEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(
            left.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            right.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }
}
