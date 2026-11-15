CREATE TABLE user_skills (
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    skill_id VARCHAR(64) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, skill_id)
);

CREATE TABLE ai_requests (
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ai_requests_time_idx ON ai_requests (requested_at);
