ALTER TABLE submissions
    ADD COLUMN execution_attempts INTEGER NOT NULL DEFAULT 0 CHECK (execution_attempts >= 0),
    ADD COLUMN ready_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN request_key VARCHAR(80),
    ADD COLUMN request_hash CHAR(64),
    ADD CONSTRAINT submissions_request_identity_check CHECK ((request_key IS NULL) = (request_hash IS NULL));

UPDATE submissions SET execution_attempts = 1,
    lease_expires_at = coalesce(started_at, now()) + interval '3 minutes'
WHERE verdict = 'RUNNING';

CREATE UNIQUE INDEX submissions_request_key_idx ON submissions (user_id, request_key) WHERE request_key IS NOT NULL;
CREATE INDEX submissions_lease_idx ON submissions (lease_expires_at, id) WHERE verdict = 'RUNNING';
CREATE INDEX submissions_run_retention_idx ON submissions (created_at, id)
    WHERE mode = 'RUN' AND verdict NOT IN ('PENDING', 'RUNNING');
