ALTER TABLE problems ADD COLUMN coaching_enabled BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX submissions_problem_accepted_user_idx ON submissions (user_id, problem_id)
    WHERE mode = 'SUBMIT' AND verdict = 'ACCEPTED';
CREATE INDEX submissions_user_active_idx ON submissions (user_id)
    WHERE verdict IN ('PENDING', 'RUNNING');
