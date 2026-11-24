CREATE TABLE workflow_run (
    run_id VARCHAR(36) NOT NULL PRIMARY KEY,
    app_key VARCHAR(96) NOT NULL,
    request_id VARCHAR(96) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    lease_until TIMESTAMP(3),
    expires_at TIMESTAMP(3),
    state_json MEDIUMTEXT NOT NULL,
    UNIQUE KEY uk_workflow_request (app_key, request_id),
    KEY idx_workflow_queue (status, lease_until, created_at),
    KEY idx_workflow_history (app_key, created_at),
    KEY idx_workflow_expiry (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE workflow_event (
    run_id VARCHAR(36) NOT NULL,
    seq BIGINT NOT NULL,
    event_json TEXT NOT NULL,
    PRIMARY KEY (run_id, seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE workflow_step_attempt (
    run_id VARCHAR(36) NOT NULL,
    activation_no INT NOT NULL,
    attempt_no INT NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    started_at TIMESTAMP(3) NOT NULL,
    finished_at TIMESTAMP(3),
    elapsed_ms BIGINT,
    PRIMARY KEY (run_id, activation_no, attempt_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE workflow_signal (
    run_id VARCHAR(36) NOT NULL,
    signal_id VARCHAR(96) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    PRIMARY KEY (run_id, signal_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE workflow_local_effect (
    run_id VARCHAR(36) NOT NULL,
    activation_no INT NOT NULL,
    position_no INT NOT NULL,
    kind VARCHAR(64) NOT NULL,
    effect_json TEXT NOT NULL,
    PRIMARY KEY (run_id, activation_no, position_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
