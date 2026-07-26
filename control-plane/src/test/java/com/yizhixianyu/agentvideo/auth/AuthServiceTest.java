package com.yizhixianyu.agentvideo.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserAccountRepository userRepository;
    @Mock private AuthSessionRepository sessionRepository;

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(userRepository, sessionRepository, 30);
    }

    @Test
    void registerHashesPasswordAndStoresOnlySessionTokenHash() {
        when(userRepository.existsByEmail("creator@example.com")).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(invocation -> {
            var user = invocation.getArgument(0, UserAccountEntity.class);
            ReflectionTestUtils.setField(user, "id", "user-1");
            return user;
        });
        when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.register(" Creator@Example.com ", "Creator", "password123");

        var userCaptor = ArgumentCaptor.forClass(UserAccountEntity.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("creator@example.com");
        assertThat(userCaptor.getValue().getPasswordHash()).startsWith("$2");
        assertThat(userCaptor.getValue().getPasswordHash()).doesNotContain("password123");

        var sessionCaptor = ArgumentCaptor.forClass(AuthSessionEntity.class);
        verify(sessionRepository).save(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getTokenHash()).hasSize(64);
        assertThat(sessionCaptor.getValue().getTokenHash()).isNotEqualTo(result.rawToken());
    }

    @Test
    void expiredSessionIsRejected() {
        var session = new AuthSessionEntity("user-1", AuthService.hashToken("expired-token"), Instant.now().minusSeconds(1));
        when(sessionRepository.findByTokenHash(AuthService.hashToken("expired-token"))).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.authenticate("expired-token"))
            .isInstanceOf(AuthenticationRequiredException.class)
            .hasMessageContaining("过期");
    }

    @Test
    void logoutRevokesPersistedSession() {
        var session = new AuthSessionEntity("user-1", AuthService.hashToken("token"), Instant.now().plusSeconds(60));
        when(sessionRepository.findByTokenHash(AuthService.hashToken("token"))).thenReturn(Optional.of(session));

        service.logout("token");

        assertThat(session.getRevokedAt()).isNotNull();
    }
}
