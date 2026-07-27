package com.yizhixianyu.agentvideo.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsrfServiceTest {

    private final CsrfService service = new CsrfService(false, 30);

    @Test
    void acceptsMatchingCookieAndHeader() {
        var token = service.issueToken();
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie(CsrfService.COOKIE_NAME, token));
        request.addHeader(CsrfService.HEADER_NAME, token);

        service.validate(request);

        assertThat(service.cookie(token).isHttpOnly()).isFalse();
        assertThat(service.cookie(token).getSameSite()).isEqualTo("Lax");
    }

    @Test
    void rejectsMissingOrMismatchedToken() {
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie(CsrfService.COOKIE_NAME, service.issueToken()));
        request.addHeader(CsrfService.HEADER_NAME, service.issueToken());

        assertThatThrownBy(() -> service.validate(request))
            .isInstanceOf(CsrfProtectionException.class);
    }
}
