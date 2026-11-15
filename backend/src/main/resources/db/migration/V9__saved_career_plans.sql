CREATE TABLE career_plans (
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    track_id VARCHAR(32) NOT NULL,
    plan_text VARCHAR(16000) NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, track_id)
);
