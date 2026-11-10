# CodeArena

CodeArena is a local competitive programming platform. Browse 20 seeded problems, write solutions in Monaco, run examples or custom input, and submit solutions for automated judging. Real submissions drive the dashboard, achievements, and global and contest rankings.

The app uses React, Vite, Tailwind CSS, and Recharts for the frontend. The Java 17 and Spring Boot 3.5 API uses PostgreSQL, Flyway, BCrypt, and signed JWTs. A separate Python service compiles and executes Python 3, Java 17, and C++17 submissions. See [architecture](docs/architecture.md), [schema](docs/database-schema.md), [API reference](docs/api-documentation.md), and [development progress](DEVELOPMENT_PROGRESS.md).

## Prerequisites

- Windows PowerShell or PowerShell 7
- Node.js 22.12 or newer and npm
- JDK 17 or newer with `java` and `javac` on PATH, plus Maven 3.6.3 or newer
- Python 3.11 or newer
- Locally installed PostgreSQL with `psql`, `initdb`, `pg_ctl`, and `createdb` on PATH
- A C++17 compiler, such as MinGW-w64 `g++`. Set `CODEARENA_CXX` to the absolute compiler path if it is outside PATH.

## Start locally

From the repository root, install dependencies and initialize a dedicated local PostgreSQL instance.

```powershell
npm.cmd --prefix frontend ci
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r judge-service/requirements.txt
.\scripts\init-local.ps1
```

The initialization script creates the `codearena` and `codearena_test` databases on loopback port `55432`. It creates the development credentials in the ignored `.local/env.ps1` file. Flyway applies the schema and seed data on the first API start. For an existing PostgreSQL installation, create an empty database and a user that can create its tables, then set the database and secret environment variables described in [the example environment file](.env.example).

Run the API from the repository root in a new terminal.

```powershell
. .\.local\env.ps1
mvn.cmd -f backend/pom.xml spring-boot:run '-Dspring-boot.run.jvmArguments=-Xms64m -Xmx384m'
```

Start the judge in a second terminal when you are ready to run your own trusted solutions. If your compiler is outside PATH, set `CODEARENA_CXX` in this terminal.

```powershell
. .\.local\env.ps1
$env:CODEARENA_TRUST_LOCAL_CODE = '1'
.\.venv\Scripts\python.exe -m uvicorn app.main:app --app-dir judge-service --host 127.0.0.1 --port 8091 --no-proxy-headers
```

Start the browser app in a third terminal.

```powershell
npm.cmd --prefix frontend run dev
```

Open [CodeArena](http://127.0.0.1:5173/). Create a regular account to practice. The setup script seeds the development admin with username `arena_admin` and the password in `CODEARENA_ADMIN_PASSWORD` in your local environment file. Admin creation only occurs when the password is set and the account does not already exist. Keep the generated values local.

Stop the app and judge with Ctrl+C in their terminals. Stop the workspace PostgreSQL instance with `pg_ctl.exe stop -D .local/postgres -m fast`.

## Judge scope

The judge is a trusted development runner. The API and judge bind to loopback, the judge requires an explicit opt-in and a shared token, and each job uses a temporary directory with bounded output and execution time. Windows Job Objects constrain memory and terminate process trees. The subprocess inherits the local user’s filesystem and network permissions. Use only code you trust in this mode. Public execution requires an isolated runner behind the `JudgeGateway` interface.

## Verify

```powershell
npm.cmd --prefix frontend run build

Push-Location judge-service
..\.venv\Scripts\python.exe -m unittest discover -s tests -v
Pop-Location

. .\.local\env.ps1
$env:CODEARENA_TEST_DB_URL = $env:CODEARENA_DB_URL -replace '/codearena$', '/codearena_test'
$env:CODEARENA_TEST_JUDGE = '1'
mvn.cmd -f backend/pom.xml test
```

Start the judge before running the integration suite with `CODEARENA_TEST_JUDGE=1`. The Java suite uses only the dedicated test database. The Python tests skip compiler-specific cases when a toolchain is unavailable. Run [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html) with the API running.

## Troubleshooting

- **API startup fails on the database connection.** Run `.\scripts\init-local.ps1` and load `.local/env.ps1` in the API terminal. If the default port is in use on a fresh setup, pass `-Port` to the initialization script. PostgreSQL 18 was verified locally. The Spring Boot-managed Flyway version may print a compatibility notice for PostgreSQL 18.
- **Judge unavailable.** Check [judge health](http://127.0.0.1:8091/health) and verify that `enabled` is true and that the selected language is listed. Load the same local judge token in the API and judge terminals.
- **Java or C++ unavailable.** Confirm that the judge terminal can find `javac` or `g++`, or set `CODEARENA_CXX` for a portable compiler and restart the judge.
- **Local preview reports an API error.** Open the app on `127.0.0.1:5173` and confirm the API is listening on `127.0.0.1:8080`. The Vite proxy connects them.
