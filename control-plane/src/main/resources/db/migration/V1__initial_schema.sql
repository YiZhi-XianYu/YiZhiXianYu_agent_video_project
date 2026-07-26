-- =============================================================================
-- V1: Initial schema baseline
-- Generated from JPA entities (Agent-Driven Video Pipeline, Spring Boot 3.4.4)
-- =============================================================================

-- ---------------------------------------------------------------------------
-- projects
-- ---------------------------------------------------------------------------
CREATE TABLE projects (
    id          VARCHAR(40)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    name        VARCHAR(200) NOT NULL,
    status      VARCHAR(30)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- assets
-- ---------------------------------------------------------------------------
CREATE TABLE assets (
    id          VARCHAR(40)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    project_id  VARCHAR(40)  NOT NULL,
    type        VARCHAR(40)  NOT NULL,
    status      VARCHAR(30)  NOT NULL,
    file_name   VARCHAR(500) NOT NULL,
    storage_uri VARCHAR(2000) NOT NULL,
    size_bytes  BIGINT       NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_assets_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- workflow_runs
-- ---------------------------------------------------------------------------
CREATE TABLE workflow_runs (
    id                 VARCHAR(40)  NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    version            BIGINT       NOT NULL DEFAULT 0,
    project_id         VARCHAR(40)  NOT NULL,
    asset_id           VARCHAR(40)  NULL,
    workflow_type      VARCHAR(100) NOT NULL,
    proxy_quality      VARCHAR(20)  NULL,
    definition_key     VARCHAR(100) NULL,
    definition_version INTEGER      NULL,
    definition_json    LONGTEXT     NULL,
    status             VARCHAR(30)  NOT NULL,
    progress           INT          NOT NULL DEFAULT 0,
    auto_mode          TINYINT(1)   NOT NULL DEFAULT 0,
    current_gate_key   VARCHAR(100) NULL,
    gates_json         LONGTEXT     NULL,
    completed_gates_json LONGTEXT   NULL,
    error_message      VARCHAR(2000) NULL,
    started_at         DATETIME(6)  NULL,
    completed_at       DATETIME(6)  NULL,
    PRIMARY KEY (id),
    INDEX idx_workflow_runs_project (project_id),
    INDEX idx_workflow_runs_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- workflow_assets
-- ---------------------------------------------------------------------------
CREATE TABLE workflow_assets (
    id              VARCHAR(40) NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    version         BIGINT      NOT NULL DEFAULT 0,
    workflow_run_id VARCHAR(40) NOT NULL,
    asset_id        VARCHAR(40) NOT NULL,
    position_index  INT         NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_workflow_asset UNIQUE (workflow_run_id, asset_id),
    CONSTRAINT uk_workflow_asset_position UNIQUE (workflow_run_id, position_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- task_runs
-- ---------------------------------------------------------------------------
CREATE TABLE task_runs (
    id                  VARCHAR(40)  NOT NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    workflow_run_id     VARCHAR(40)  NOT NULL,
    node_key            VARCHAR(100) NOT NULL,
    asset_id            VARCHAR(40)  NULL,
    instance_key        VARCHAR(180) NULL,
    tool_name           VARCHAR(120) NOT NULL,
    tool_version        VARCHAR(30)  NOT NULL,
    input_binding       VARCHAR(40)  NULL,
    parameters_json     LONGTEXT     NULL,
    depends_on_task_run_id VARCHAR(40) NULL,
    status              VARCHAR(30)  NOT NULL,
    progress            INT          NOT NULL DEFAULT 0,
    attempt             INT          NOT NULL DEFAULT 0,
    retry_count         INT          NOT NULL DEFAULT 0,
    started_at          DATETIME(6)  NULL,
    completed_at        DATETIME(6)  NULL,
    next_attempt_at     DATETIME(6)  NULL,
    retry_same_attempt  TINYINT(1)   NOT NULL DEFAULT 0,
    error_message       VARCHAR(2000) NULL,
    PRIMARY KEY (id),
    INDEX idx_task_runs_workflow (workflow_run_id),
    INDEX idx_task_runs_status (status),
    INDEX idx_task_runs_instance (instance_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- task_dependencies
-- ---------------------------------------------------------------------------
CREATE TABLE task_dependencies (
    id                     VARCHAR(40) NOT NULL,
    created_at             DATETIME(6) NOT NULL,
    updated_at             DATETIME(6) NOT NULL,
    version                BIGINT      NOT NULL DEFAULT 0,
    task_run_id            VARCHAR(40) NOT NULL,
    depends_on_task_run_id VARCHAR(40) NOT NULL,
    dependency_type        VARCHAR(20) NOT NULL DEFAULT 'REQUIRED',
    PRIMARY KEY (id),
    CONSTRAINT uk_task_dependency UNIQUE (task_run_id, depends_on_task_run_id),
    INDEX idx_task_dep_task (task_run_id),
    INDEX idx_task_dep_upstream (depends_on_task_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- tool_executions
-- ---------------------------------------------------------------------------
CREATE TABLE tool_executions (
    id                    VARCHAR(40)  NOT NULL,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    version               BIGINT       NOT NULL DEFAULT 0,
    task_run_id           VARCHAR(40)  NOT NULL,
    idempotency_key       VARCHAR(120) NOT NULL,
    external_execution_id VARCHAR(80)  NOT NULL,
    status                VARCHAR(30)  NOT NULL,
    poll_failure_count    INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_tool_execution_idempotency UNIQUE (idempotency_key),
    INDEX idx_tool_exec_task (task_run_id),
    INDEX idx_tool_exec_external (external_execution_id),
    INDEX idx_tool_exec_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- artifacts
-- ---------------------------------------------------------------------------
CREATE TABLE artifacts (
    id                    VARCHAR(40)  NOT NULL,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    version               BIGINT       NOT NULL DEFAULT 0,
    external_artifact_id  VARCHAR(80)  NOT NULL,
    project_id            VARCHAR(40)  NOT NULL,
    producer_task_run_id  VARCHAR(40)  NOT NULL,
    type                  VARCHAR(80)  NOT NULL,
    storage_uri           VARCHAR(2000) NOT NULL,
    media_type            VARCHAR(120) NOT NULL,
    size_bytes            BIGINT       NOT NULL,
    content_hash          VARCHAR(64)  NOT NULL,
    metadata_json         LONGTEXT     NULL,
    PRIMARY KEY (id),
    INDEX idx_artifacts_project (project_id),
    INDEX idx_artifacts_producer (producer_task_run_id),
    INDEX idx_artifacts_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- custom_story_plans
-- ---------------------------------------------------------------------------
CREATE TABLE custom_story_plans (
    id                     VARCHAR(40)  NOT NULL,
    created_at             DATETIME(6)  NOT NULL,
    updated_at             DATETIME(6)  NOT NULL,
    version                BIGINT       NOT NULL DEFAULT 0,
    project_id             VARCHAR(40)  NOT NULL,
    source_workflow_run_id VARCHAR(40)  NOT NULL,
    plan_json              LONGTEXT     NOT NULL,
    status                 VARCHAR(20)  NOT NULL,
    version_name           VARCHAR(200) NULL,
    PRIMARY KEY (id),
    INDEX idx_csp_source (source_workflow_run_id),
    INDEX idx_csp_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
