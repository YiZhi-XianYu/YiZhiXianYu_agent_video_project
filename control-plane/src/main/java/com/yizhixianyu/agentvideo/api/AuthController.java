package com.yizhixianyu.agentvideo.api;

import com.yizhixianyu.agentvideo.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final boolean secureCookie;

    public AuthController(AuthService authService, @Value("${app.auth.secure-cookie:false}") boolean secureCookie) {
        this.authService = authService;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/register")
    public AuthView register(
        @Valid @RequestBody RegisterRequest request,
        HttpServletResponse response
    ) {
        return complete(authService.register(request.email(), request.displayName(), request.password()), response);
    }

    @PostMapping("/login")
    public AuthView login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        return complete(authService.login(request.email(), request.password()), response);
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(authService.readSessionToken(request));
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO).toString());
    }

    @GetMapping("/me")
    public AuthView me(HttpServletRequest request) {
        var user = authService.authenticate(authService.readSessionToken(request));
        return AuthView.from(user);
    }

    private AuthView complete(AuthService.AuthResult result, HttpServletResponse response) {
        var maxAge = Duration.between(java.time.Instant.now(), result.expiresAt());
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(result.rawToken(), maxAge).toString());
        return AuthView.from(result.user());
    }

    private ResponseCookie sessionCookie(String value, Duration maxAge) {
        return ResponseCookie.from(AuthService.SESSION_COOKIE, value)
            .httpOnly(true)
            .secure(secureCookie)
            .sameSite("Lax")
            .path("/")
            .maxAge(maxAge)
            .build();
    }

    public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @Size(max = 80) String displayName,
        @NotBlank @Size(min = 8, max = 72) String password
    ) {}

    public record LoginRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 72) String password
    ) {}

    public record AuthView(String id, String email, String displayName) {
        static AuthView from(AuthService.CurrentUser user) {
            return new AuthView(user.id(), user.email(), user.displayName());
        }
    }
}
