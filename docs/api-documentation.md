# API reference

Springdoc serves the [OpenAPI JSON](http://127.0.0.1:8080/v3/api-docs) and [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html). All app endpoints start with `/api`. Send protected requests with `Authorization: Bearer <access-token>`.

| Area | Routes |
| --- | --- |
| Account | `POST /auth/register`, `POST /auth/login`, `POST /auth/logout`, `GET /auth/me` |
| Profile | `GET /users/{username}`, `GET /users/me`, `PUT /users/me`, `PUT /users/me/password`, `GET /users/me/stats` |
| Catalog | `GET /problems`, `GET /problems/{id}`, `GET /problems/slug/{slug}` |
| Execution | `POST /submissions/run`, `POST /submissions/submit`, `GET /submissions`, `GET /submissions/{id}`, `GET /submissions/{id}/result`, `GET /problems/{id}/submissions` |
| Career | `GET /career/roadmaps`, `PUT /career/skills/{id}`, `GET /career/plans/{track}`, `DELETE /career/plans/{track}` |
| AI coach | `GET /ai/status`, `POST /ai/practice`, `POST /ai/career` |
| Competition | `GET /contests`, `GET /contests/{id}`, `POST /contests/{id}/register`, `GET /contests/{id}/leaderboard` |
| Standings | `GET /leaderboard/global`, `GET /leaderboard/weekly`, `GET /leaderboard/monthly` |
| Admin | `GET /admin/dashboard`, `GET /admin/analytics`, `GET /admin/users`, `PUT /admin/users/{id}/access`, problem and contest management, and submission inspection |

Run and Submit return HTTP 202 with a submission ID. Send an `Idempotency-Key` of 16–80 letters, digits, hyphens, or underscores and reuse the same key and payload when retrying. An unchanged request returns the existing job for that account. A different payload with the same key returns HTTP 409. Poll `GET /submissions/{id}/result` until the verdict leaves `PENDING` and `RUNNING`. This route returns sample output without the source. New results also include the expected output used to grade each public sample. Use `customInput` for Run and `contestId` for an eligible contest. Accepted Submit jobs count toward solved problems.

`GET /career/roadmaps` returns tracks and matching published practice problems. Signed-in users also receive their completed skill IDs and solved flags. To mark or clear a skill, send `{"completed":true}` or `{"completed":false}` to `PUT /career/skills/{id}`. Success returns HTTP 204.

The account’s latest saved career plan is available at `GET /career/plans/{track}`. The response has `text` and `generatedAt` fields, which are null when the account has no plan for the track. `DELETE /career/plans/{track}` clears it.

`GET /ai/status` indicates whether an AI provider is configured. `POST /ai/practice` accepts `problemId` plus an `action` of `HINT`, `EXPLANATION`, or `REVIEW`. Hints accept an optional `hintLevel` from 1 to 3. Reviews require `language` and `code`. The API returns HTTP 403 if coaching is disabled for the problem or the problem is in an active contest. `POST /ai/career` accepts a `track` ID, an `experience` of `BEGINNER`, `SOME_PRACTICE`, or `PROJECT_EXPERIENCE`, `weeklyHours` from 1 to 40, and an optional `goal`. Pass `"save":true` to store the successful plan. Both POST routes require authentication and return `{"text":"..."}`. HTTP 429 indicates a request limit, and HTTP 503 indicates a provider failure.

List responses return `items`, `page`, `size`, and `total`. Pages are zero-based and page size is capped at 50. Problem filters include `search`, `category`, `difficulty`, `solved`, and `sort`. Submission filters include `problemId`, `language`, and `verdict`. Validation errors return a human-readable `message` field.

`GET /api/health` reports database connectivity and whether an execution adapter is configured. It returns HTTP 503 when the database is unavailable. A configured judge is verified separately by submitting a test solution.

The API returns `X-Request-ID` for support and troubleshooting. Public readiness and liveness probes are under `/actuator/health/`. The production readiness probe checks PostgreSQL and RabbitMQ. Metrics require an admin bearer token.
