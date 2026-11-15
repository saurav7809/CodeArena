# Database schema

Flyway creates the schema in [V1](../backend/src/main/resources/db/migration/V1__arena_schema.sql) and seeds the catalog, contests, and achievement definitions in [V2](../backend/src/main/resources/db/migration/V2__starter_catalog.sql).

| Relation | Purpose |
| --- | --- |
| `users` | Unique username and email, BCrypt hash, role, enabled state, and token version |
| `user_profiles` | One profile per user with full name and bio |
| `problems` | Unique slug, statement, difficulty, limits, and publication state |
| `problem_test_cases` | Ordered sample and hidden tests for each problem |
| `submissions` | Source, language, Run or Submit mode, verdict, resource usage, timestamps, and visible results |
| `contests` | Schedule, publication state, and penalty settings |
| `contest_problems` | Ordered problems and point values in a contest |
| `contest_participants` | Unique contest registration per user |
| `contest_submissions` | Eligible competition attached to a submission |
| `achievements` | Achievement definitions |
| `user_achievements` | Unique earned award per user and achievement |
| `user_activity` | View of actual submissions for dashboard and analytics queries |
| `user_skills` | Completion time per user and roadmap skill |
| `ai_requests` | Recent request timestamps used to enforce AI usage caps |
| `career_plans` | Latest saved guidance for each account and track |
| `problem_statistics` | Counts of judged and accepted submissions per problem |

Foreign keys preserve relationships. Problems are archived so previous submissions remain readable. Leaderboards are calculated from judged submissions rather than stored counters. User history, problem submissions, contests, and queue lookups have dedicated indexes.

[V3](../backend/src/main/resources/db/migration/V3__execution_budget_index.sql) indexes submission timestamps for the production execution budget, which includes both Run and Submit jobs.

[V4](../backend/src/main/resources/db/migration/V4__execution_dispatch.sql) adds the next dispatch time and an index for pending jobs. [V5](../backend/src/main/resources/db/migration/V5__career_progress_and_ai_budget.sql) adds skill completion and AI request accounting. [V6](../backend/src/main/resources/db/migration/V6__additional_dsa_problems.sql) seeds five DSA problems with public and hidden tests.

V7 adds worker leases, attempt numbers, and request keys. V8 indexes solved-problem and active-job queries, and adds the administrator’s coaching flag. V9 stores saved career plans. V10 backfills judged and accepted totals and keeps them consistent with verdict writes using a database trigger.
