CREATE TABLE studio_graph_app (
    app_id VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(120) NOT NULL,
    revision BIGINT NOT NULL,
    latest_version BIGINT NOT NULL,
    graph_json MEDIUMTEXT NOT NULL
);

CREATE TABLE studio_graph_version (
    app_id VARCHAR(64) NOT NULL,
    version_no BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    graph_json MEDIUMTEXT NOT NULL,
    capabilities_json TEXT NOT NULL,
    PRIMARY KEY (app_id, version_no),
    UNIQUE (app_id, revision)
);

CREATE TABLE studio_live_budget_lock (lock_id INTEGER PRIMARY KEY);
INSERT INTO studio_live_budget_lock (lock_id) VALUES (1);
CREATE TABLE studio_live_reservation (
    request_key VARCHAR(160) PRIMARY KEY,
    started_ms BIGINT NOT NULL,
    finished_ms BIGINT NULL
);
CREATE INDEX idx_studio_live_reservation ON studio_live_reservation (started_ms, finished_ms);

INSERT INTO studio_graph_app (app_id, display_name, revision, latest_version, graph_json)
VALUES ('meeting-notes', 'Meeting notes', 1, 1, '{"nodes":[{"id":"start","type":"start","bindings":{},"config":{}},{"id":"extract","type":"extract-tasks","bindings":{"text":"start.text"},"config":{}},{"id":"filter","type":"filter-assigned","bindings":{"items":"extract.items"},"config":{}},{"id":"gate","type":"condition","bindings":{"value":"filter.hasItems"},"config":{}},{"id":"confirm","type":"confirm","bindings":{"items":"filter.items"},"config":{}},{"id":"create","type":"create-tasks","bindings":{"items":"confirm.items"},"config":{}},{"id":"end","type":"end","bindings":{},"config":{"message":"Confirmed tasks created"}},{"id":"empty","type":"end","bindings":{},"config":{"message":"No confirmed tasks"}}],"edges":[{"from":"start","port":"next","to":"extract"},{"from":"extract","port":"next","to":"filter"},{"from":"filter","port":"next","to":"gate"},{"from":"gate","port":"yes","to":"confirm"},{"from":"gate","port":"no","to":"empty"},{"from":"confirm","port":"yes","to":"create"},{"from":"confirm","port":"no","to":"empty"},{"from":"create","port":"next","to":"end"}]}');
INSERT INTO studio_graph_version (app_id, version_no, revision, graph_json, capabilities_json)
SELECT app_id, 1, revision, graph_json, '{"start":"1","extract-tasks":"1","filter-assigned":"1","condition":"1","confirm":"1","create-tasks":"1","end":"1"}' FROM studio_graph_app WHERE app_id = 'meeting-notes';

INSERT INTO studio_graph_app (app_id, display_name, revision, latest_version, graph_json)
VALUES ('feedback-triage', 'Customer feedback', 1, 1, '{"nodes":[{"id":"start","type":"start","bindings":{},"config":{}},{"id":"classify","type":"classify-feedback","bindings":{"text":"start.text"},"config":{}},{"id":"route","type":"condition","bindings":{"value":"classify.urgent"},"config":{}},{"id":"review","type":"confirm","bindings":{"items":"classify.items"},"config":{}},{"id":"ticket","type":"create-ticket","bindings":{"text":"classify.text","priority":"classify.priority"},"config":{}},{"id":"end","type":"end","bindings":{},"config":{"message":"Priority ticket created"}},{"id":"ignore","type":"end","bindings":{},"config":{"message":"Feedback recorded without escalation"}}],"edges":[{"from":"start","port":"next","to":"classify"},{"from":"classify","port":"next","to":"route"},{"from":"route","port":"yes","to":"review"},{"from":"route","port":"no","to":"ignore"},{"from":"ticket","port":"next","to":"end"},{"from":"review","port":"yes","to":"ticket"},{"from":"review","port":"no","to":"ignore"}]}');
INSERT INTO studio_graph_version (app_id, version_no, revision, graph_json, capabilities_json)
SELECT app_id, 1, revision, graph_json, '{"start":"1","classify-feedback":"1","condition":"1","confirm":"1","create-ticket":"1","end":"1"}' FROM studio_graph_app WHERE app_id = 'feedback-triage';
