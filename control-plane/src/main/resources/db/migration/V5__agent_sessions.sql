CREATE TABLE agent_sessions (
    id VARCHAR(40) NOT NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0, user_id VARCHAR(40) NOT NULL, project_id VARCHAR(40) NOT NULL,
    natural_language_goal LONGTEXT NOT NULL, target_duration_ms INT NULL, current_workflow_run_id VARCHAR(40) NULL,
    current_turn_id VARCHAR(80) NULL, current_plan_id VARCHAR(80) NULL, dag_version INT NULL,
    current_gate_key VARCHAR(100) NULL, status VARCHAR(30) NOT NULL,
    PRIMARY KEY (id), INDEX idx_agent_session_project_user (project_id, user_id, updated_at),
    INDEX idx_agent_session_workflow (current_workflow_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_session_turns (
    id VARCHAR(40) NOT NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0, session_id VARCHAR(40) NOT NULL, sequence_number INT NOT NULL,
    role VARCHAR(20) NOT NULL, content LONGTEXT NOT NULL, plan_id VARCHAR(80) NULL, workflow_run_id VARCHAR(40) NULL,
    PRIMARY KEY (id), CONSTRAINT uk_agent_turn_sequence UNIQUE (session_id, sequence_number),
    INDEX idx_agent_turn_session (session_id, sequence_number), INDEX idx_agent_turn_plan (plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE workflow_runs
    ADD COLUMN agent_session_id VARCHAR(40) NULL,
    ADD COLUMN agent_turn_id VARCHAR(80) NULL,
    ADD COLUMN agent_plan_id VARCHAR(80) NULL,
    ADD COLUMN agent_trace_id VARCHAR(80) NULL,
    ADD INDEX idx_workflow_agent_session (agent_session_id),
    ADD INDEX idx_workflow_agent_trace (agent_trace_id);
