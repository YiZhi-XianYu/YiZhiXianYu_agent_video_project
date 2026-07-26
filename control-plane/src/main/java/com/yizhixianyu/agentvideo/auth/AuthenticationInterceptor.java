package com.yizhixianyu.agentvideo.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthenticationInterceptor implements HandlerInterceptor {

    private final AuthService authService;

    public AuthenticationInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var user = authService.authenticate(authService.readSessionToken(request));
        request.setAttribute(AuthService.REQUEST_USER_ATTRIBUTE, user);
        return true;
    }
}
