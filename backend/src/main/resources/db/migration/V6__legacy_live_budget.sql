INSERT INTO studio_live_reservation (request_key, started_ms, finished_ms)
SELECT
    CONCAT('legacy:', s.run_id),
    CAST(UNIX_TIMESTAMP(s.created_at) AS DECIMAL(18, 3)) * 1000,
    CASE
        WHEN s.status = 'running' THEN NULL
        ELSE CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP) AS DECIMAL(18, 3)) * 1000
    END
FROM studio_run AS s
WHERE
    s.run_mode = 'live'
    AND NOT EXISTS (
        SELECT 1
        FROM studio_live_reservation AS r
        WHERE r.request_key = CONCAT('legacy:', s.run_id)
    );
