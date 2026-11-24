CREATE TABLE studio_evaluation_suite (
    app_id VARCHAR(80) NOT NULL PRIMARY KEY,
    revision BIGINT NOT NULL,
    cases_json MEDIUMTEXT NOT NULL,
    passed_hash VARCHAR(64),
    passed_revision BIGINT,
    compared_version BIGINT
);

INSERT INTO studio_evaluation_suite (app_id, revision, cases_json)
VALUES
('meeting-notes', 1, '[{"name":"确认会议事项","text":"Alice: Send the report","approve":true,"expectWait":true,"effects":1},{"name":"无待办会议","text":"For information only.","approve":true,"expectWait":false,"effects":0},{"name":"取消全部事项","text":"Alice: Send the report","approve":false,"expectWait":true,"effects":0}]'),
('feedback-triage', 1, '[{"name":"紧急反馈","text":"Urgent: payments are blocked","approve":true,"expectWait":true,"effects":1},{"name":"普通建议","text":"Please add a dark theme","approve":true,"expectWait":false,"effects":0},{"name":"审核未通过","text":"Urgent: payments are blocked","approve":false,"expectWait":true,"effects":0}]');
