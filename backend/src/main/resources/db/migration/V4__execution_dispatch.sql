ALTER TABLE submissions ADD COLUMN dispatch_after TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX submissions_dispatch_idx ON submissions (dispatch_after, id) WHERE verdict = 'PENDING';
