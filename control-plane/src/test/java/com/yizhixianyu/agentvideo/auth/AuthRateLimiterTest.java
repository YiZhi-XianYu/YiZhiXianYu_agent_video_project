package com.yizhixianyu.agentvideo.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthRateLimiterTest {

    @Test
    void blocksEleventhFailedLoginWithinWindow() {
        var limiter = new AuthRateLimiter();
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.10");

        for (int index = 0; index < 10; index++) {
            limiter.checkLogin(request, "user@example.com");
            limiter.recordLoginFailure(request, "user@example.com");
        }

        assertThatThrownBy(() -> limiter.checkLogin(request, "user@example.com"))
            .isInstanceOf(AuthRateLimitException.class);
    }
}
