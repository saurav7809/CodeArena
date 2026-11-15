CREATE TABLE problem_statistics (
    problem_id BIGINT PRIMARY KEY REFERENCES problems (id) ON DELETE CASCADE,
    judged BIGINT NOT NULL DEFAULT 0 CHECK (judged >= 0),
    accepted BIGINT NOT NULL DEFAULT 0 CHECK (accepted >= 0 AND accepted <= judged)
);

-- Block verdict writers between the backfill and trigger installation.
LOCK TABLE submissions IN SHARE ROW EXCLUSIVE MODE;

INSERT INTO problem_statistics (problem_id, judged, accepted)
SELECT problem_id, count(*), count(*) FILTER (WHERE verdict = 'ACCEPTED')
FROM submissions
WHERE mode = 'SUBMIT' AND verdict NOT IN ('PENDING', 'RUNNING', 'INTERNAL_ERROR')
GROUP BY problem_id;

CREATE FUNCTION update_problem_acceptance() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    previously_judged boolean := false;
    now_judged boolean := false;
    previously_accepted boolean := false;
    now_accepted boolean := false;
    affected_problem bigint;
    judged_delta integer;
    accepted_delta integer;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        affected_problem := OLD.problem_id;
        previously_judged := OLD.mode = 'SUBMIT' AND OLD.verdict NOT IN ('PENDING', 'RUNNING', 'INTERNAL_ERROR');
        previously_accepted := previously_judged AND OLD.verdict = 'ACCEPTED';
    END IF;
    IF TG_OP <> 'DELETE' THEN
        affected_problem := NEW.problem_id;
        now_judged := NEW.mode = 'SUBMIT' AND NEW.verdict NOT IN ('PENDING', 'RUNNING', 'INTERNAL_ERROR');
        now_accepted := now_judged AND NEW.verdict = 'ACCEPTED';
    END IF;
    judged_delta := now_judged::integer - previously_judged::integer;
    accepted_delta := now_accepted::integer - previously_accepted::integer;
    IF judged_delta > 0 THEN
        INSERT INTO problem_statistics (problem_id, judged, accepted)
        VALUES (affected_problem, judged_delta, accepted_delta)
        ON CONFLICT (problem_id) DO UPDATE SET
            judged = problem_statistics.judged + excluded.judged,
            accepted = problem_statistics.accepted + excluded.accepted;
    ELSIF judged_delta <> 0 OR accepted_delta <> 0 THEN
        UPDATE problem_statistics SET judged = judged + judged_delta, accepted = accepted + accepted_delta
        WHERE problem_id = affected_problem;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER maintain_problem_acceptance
AFTER INSERT OR DELETE OR UPDATE OF verdict ON submissions
FOR EACH ROW EXECUTE FUNCTION update_problem_acceptance();
