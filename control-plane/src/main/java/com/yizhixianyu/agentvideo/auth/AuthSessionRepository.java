package com.yizhixianyu.agentvideo.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthSessionRepository extends JpaRepository<AuthSessionEntity, String> {
    Optional<AuthSessionEntity> findByTokenHash(String tokenHash);
}
