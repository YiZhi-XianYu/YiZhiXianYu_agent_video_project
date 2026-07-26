package com.yizhixianyu.agentvideo.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccountEntity, String> {
    Optional<UserAccountEntity> findByEmail(String email);
    boolean existsByEmail(String email);
}
