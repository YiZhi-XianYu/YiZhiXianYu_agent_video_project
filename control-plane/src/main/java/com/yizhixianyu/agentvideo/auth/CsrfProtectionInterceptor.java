package com.yizhixianyu.agentvideo.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

@Component
public class CsrfProtectionInterceptor implements HandlerInterceptor {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final CsrfService csrfService;

    public CsrfProtectionInterceptor(CsrfService csrfService) {
        this.csrfService = csrfService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!SAFE_METHODS.contains(request.getMethod())) {
            csrfService.validate(request);
        }
        return true;
    }
}
