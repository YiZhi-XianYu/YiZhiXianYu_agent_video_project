-- Stage 11 resets the unpublished demo data before introducing ownership.
DELETE FROM custom_story_plans;
DELETE FROM artifacts;
DELETE FROM tool_executions;
DELETE FROM task_dependencies;
DELETE FROM task_runs;
DELETE FROM workflow_assets;
DELETE FROM workflow_runs;
DELETE FROM assets;
DELETE FROM projects;

CREATE TABLE users (
    id            VARCHAR(40)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    email         VARCHAR(254) NOT NULL,
    display_name  VARCHAR(80)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE auth_sessions (
    id          VARCHAR(40) NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    version     BIGINT      NOT NULL DEFAULT 0,
    user_id     VARCHAR(40) NOT NULL,
    token_hash  VARCHAR(64) NOT NULL,
    expires_at  DATETIME(6) NOT NULL,
    revoked_at  DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_auth_sessions_token UNIQUE (token_hash),
    INDEX idx_auth_sessions_user (user_id),
    INDEX idx_auth_sessions_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE projects
    ADD COLUMN owner_user_id VARCHAR(40) NOT NULL AFTER version,
    ADD INDEX idx_projects_owner (owner_user_id);
