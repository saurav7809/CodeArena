# API reference

Springdoc serves the [OpenAPI JSON](http://127.0.0.1:8080/v3/api-docs) and [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html). All app endpoints start with `/api`. Send protected requests with `Authorization: Bearer <access-token>`.

| Area | Routes |
| --- | --- |
| Account | `POST /auth/register`, `POST /auth/login`, `POST /auth/logout`, `GET /auth/me` |
| Profile | `GET /users/{username}`, `GET /users/me`, `PUT /users/me`, `PUT /users/me/password`, `GET /users/me/stats` |
| Catalog | `GET /problems`, `GET /problems/{id}`, `GET /problems/slug/{slug}` |
| Execution | `POST /submissions/run`, `POST /submissions/submit`, `GET /submissions`, `GET /submissions/{id}`, `GET /problems/{id}/submissions` |
| Competition | `GET /contests`, `GET /contests/{id}`, `POST /contests/{id}/register`, `GET /contests/{id}/leaderboard` |
| Standings | `GET /leaderboard/global`, `GET /leaderboard/weekly`, `GET /leaderboard/monthly` |
| Admin | `GET /admin/dashboard`, `GET /admin/analytics`, `GET /admin/users`, `PUT /admin/users/{id}/access`, problem and contest management, and submission inspection |

Run and Submit return HTTP 202 with a submission ID. Poll `GET /submissions/{id}` until the verdict leaves `PENDING` and `RUNNING`. Use `customInput` only for Run. Set `contestId` when working inside an eligible contest. Accepted Run jobs do not count toward solved problems.

List responses return `items`, `page`, `size`, and `total`. Pages are zero-based and page size is capped at 50. Problem filters include `search`, `category`, `difficulty`, `solved`, and `sort`. Submission filters include `problemId`, `language`, and `verdict`. Validation errors return a human-readable `message` field.
