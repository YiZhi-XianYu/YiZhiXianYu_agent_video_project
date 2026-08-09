CREATE TABLE gate_feedback (
    id                VARCHAR(40) NOT NULL,
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    version           BIGINT NOT NULL DEFAULT 0,
    workflow_run_id   VARCHAR(40) NOT NULL,
    project_id        VARCHAR(40) NOT NULL,
    gate_key          VARCHAR(100) NOT NULL,
    score             INT NOT NULL,
    action            VARCHAR(40) NULL,
    reason_codes_json LONGTEXT NULL,
    comment           VARCHAR(2000) NULL,
    artifact_ids_json LONGTEXT NULL,
    PRIMARY KEY (id),
    INDEX idx_gate_feedback_workflow (workflow_run_id, created_at),
    INDEX idx_gate_feedback_project_gate (project_id, gate_key, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
