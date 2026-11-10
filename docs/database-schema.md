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

Foreign keys preserve relationships. Problems are archived so previous submissions remain readable. Leaderboards are calculated from judged submissions rather than stored counters. User history, problem submissions, contests, and queue lookups have dedicated indexes.
