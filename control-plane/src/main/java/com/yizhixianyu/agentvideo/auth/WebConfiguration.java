package com.yizhixianyu.agentvideo.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    private final AuthenticationInterceptor authenticationInterceptor;
    private final CsrfProtectionInterceptor csrfProtectionInterceptor;

    public WebConfiguration(
        AuthenticationInterceptor authenticationInterceptor,
        CsrfProtectionInterceptor csrfProtectionInterceptor
    ) {
        this.authenticationInterceptor = authenticationInterceptor;
        this.csrfProtectionInterceptor = csrfProtectionInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authenticationInterceptor)
            .addPathPatterns("/api/v1/**")
            .excludePathPatterns("/api/v1/auth/**");
        registry.addInterceptor(csrfProtectionInterceptor)
            .addPathPatterns("/api/v1/**")
            .excludePathPatterns("/api/v1/auth/login", "/api/v1/auth/register");
    }
}
