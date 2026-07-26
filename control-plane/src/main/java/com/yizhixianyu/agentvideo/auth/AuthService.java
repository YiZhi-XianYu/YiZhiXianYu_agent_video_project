package com.yizhixianyu.agentvideo.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class AuthService {

    public static final String SESSION_COOKIE = "avp_session";
    public static final String REQUEST_USER_ATTRIBUTE = AuthService.class.getName() + ".user";

    private final UserAccountRepository userRepository;
    private final AuthSessionRepository sessionRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);
    private final SecureRandom secureRandom = new SecureRandom();
    private final Duration sessionDuration;

    public AuthService(
        UserAccountRepository userRepository,
        AuthSessionRepository sessionRepository,
        @Value("${app.auth.session-days:30}") long sessionDays
    ) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.sessionDuration = Duration.ofDays(Math.max(1, sessionDays));
    }

    @Transactional
    public AuthResult register(String email, String displayName, String password) {
        var normalizedEmail = normalizeEmail(email);
        validatePassword(password);
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new IllegalArgumentException("该邮箱已经注册");
        }
        var normalizedName = displayName == null || displayName.isBlank()
            ? normalizedEmail.substring(0, normalizedEmail.indexOf('@'))
            : displayName.trim();
        if (normalizedName.length() > 80) {
            throw new IllegalArgumentException("昵称不能超过 80 个字符");
        }
        var user = userRepository.save(new UserAccountEntity(
            normalizedEmail, normalizedName, passwordEncoder.encode(password)
        ));
        return createSession(user);
    }

    @Transactional
    public AuthResult login(String email, String password) {
        var user = userRepository.findByEmail(normalizeEmail(email))
            .orElseThrow(() -> new AuthenticationRequiredException("邮箱或密码错误"));
        if (!"ACTIVE".equals(user.getStatus()) || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new AuthenticationRequiredException("邮箱或密码错误");
        }
        return createSession(user);
    }

    @Transactional(readOnly = true)
    public CurrentUser authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new AuthenticationRequiredException("请先登录");
        }
        var session = sessionRepository.findByTokenHash(hashToken(rawToken))
            .orElseThrow(() -> new AuthenticationRequiredException("登录状态无效"));
        if (session.getRevokedAt() != null || !session.getExpiresAt().isAfter(Instant.now())) {
            throw new AuthenticationRequiredException("登录状态已过期");
        }
        var user = userRepository.findById(session.getUserId())
            .orElseThrow(() -> new AuthenticationRequiredException("用户不存在"));
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new AuthenticationRequiredException("用户已停用");
        }
        return CurrentUser.from(user);
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        sessionRepository.findByTokenHash(hashToken(rawToken)).ifPresent(AuthSessionEntity::revoke);
    }

    public CurrentUser requireUser(HttpServletRequest request) {
        var value = request.getAttribute(REQUEST_USER_ATTRIBUTE);
        if (value instanceof CurrentUser user) return user;
        throw new AuthenticationRequiredException("请先登录");
    }

    public String readSessionToken(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private AuthResult createSession(UserAccountEntity user) {
        var bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        var rawToken = HexFormat.of().formatHex(bytes);
        var expiry = Instant.now().plus(sessionDuration);
        sessionRepository.save(new AuthSessionEntity(user.getId(), hashToken(rawToken), expiry));
        return new AuthResult(CurrentUser.from(user), rawToken, expiry);
    }

    private String normalizeEmail(String email) {
        var normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !normalized.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalArgumentException("请输入有效的邮箱地址");
        }
        return normalized;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 72) {
            throw new IllegalArgumentException("密码长度必须为 8 到 72 个字符");
        }
    }

    static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exc) {
            throw new IllegalStateException("SHA-256 is unavailable", exc);
        }
    }

    public record CurrentUser(String id, String email, String displayName) {
        static CurrentUser from(UserAccountEntity user) {
            return new CurrentUser(user.getId(), user.getEmail(), user.getDisplayName());
        }
    }

    public record AuthResult(CurrentUser user, String rawToken, Instant expiresAt) {}
}
