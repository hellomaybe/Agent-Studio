CREATE TABLE studio_draft (
    app_key VARCHAR(64) NOT NULL PRIMARY KEY,
    revision BIGINT NOT NULL,
    config_json TEXT NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE studio_run (
    run_id VARCHAR(36) NOT NULL PRIMARY KEY,
    request_id VARCHAR(96) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    run_mode VARCHAR(12) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP (3),
    state_json MEDIUMTEXT NOT NULL,
    UNIQUE KEY uk_studio_request (request_id),
    KEY idx_studio_history (created_at),
    KEY idx_studio_live (run_mode, status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
