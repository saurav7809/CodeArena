# Deploy CodeArena

Deploy the React frontend to Vercel and the Spring Boot API to Cloud Run. Provision managed PostgreSQL and a private Judge0-compatible sandbox. The Cloud Run buildpack builds the Java service from source. PostgreSQL handles execution dispatch for the default deployment.

## 1. Prepare the database and hosted judge

- Create a PostgreSQL 17 database with a dedicated account that owns the application schema so Flyway can apply the migrations. Use a TLS endpoint with a certificate trusted by the Java runtime. Set the JDBC URL to use `sslmode=verify-full`. The [sample settings](../deploy/cloud-run.env.example.yaml) use the JVM trust store. Put the database password in Secret Manager. [pgJDBC SSL settings](https://jdbc.postgresql.org/documentation/ssl/)
- Choose a maintained hosted judge that authenticates both job creation and result reads, isolates compilation and execution, and denies network access to submitted programs. Confirm batch submission and polling support and check its authenticated `GET /languages` response for Python 3, Java 17 or newer, and C++17. Language IDs vary by provider. Use a provider whose runtime and memory settings support the three CodeArena templates. [Judge0 API](https://ce.judge0.com/docs)
- Review the provider’s data retention settings. The API sends submitted source and test input to the hosted judge. It compares results in Spring Boot and keeps expected outputs in the application database. It returns only sample output and aggregate results to the browser.

## 2. Deploy the API from source

Choose the frontend’s production HTTPS origin first. In Google Cloud, enable Cloud Run, Cloud Build, Artifact Registry, and Secret Manager. Use a runtime service account with access to the specific secrets below. Follow the build permission steps for your project. [Cloud Run source deployments](https://docs.cloud.google.com/run/docs/deploying-source-code)

Copy [the sample runtime settings](../deploy/cloud-run.env.example.yaml) to `.local/cloud-run.env.yaml`. Fill in the frontend origin, database, hosted judge URL, and language IDs. Set `CODEARENA_JUDGE0_AUTH` to `rapidapi` when your provider uses RapidAPI headers. Use `token` for a provider that expects `X-Auth-Token`.

Create these Secret Manager secrets in the same project.

| Secret | Value |
| --- | --- |
| `codearena-jwt` | A newly generated, random signing secret of at least 32 bytes |
| `codearena-db` | Password for the production PostgreSQL account |
| `codearena-judge` | Hosted judge token or RapidAPI key |

To create the initial admin account, add `CODEARENA_ADMIN_PASSWORD=codearena-admin:1` with a random 16–72 character ASCII password. The API creates `arena_admin` if the account is absent. Remove the bootstrap secret binding after setup.

To enable the AI coach and career guidance, store an OpenAI API key in Secret Manager and add `OPENAI_API_KEY=codearena-openai:1` to the secret bindings. The server applies per-account and platform-wide request limits. See [AI setup](../README.md#ai-coach) for the model and the data each action sends.

Run this from the repo root with the Google Cloud CLI authenticated. Replace `REGION`, `PROJECT_ID`, and the example secret versions with your own values.

```powershell
gcloud run deploy codearena-api `
  --source backend `
  --project PROJECT_ID `
  --region REGION `
  --service-account codearena-runtime@PROJECT_ID.iam.gserviceaccount.com `
  --allow-unauthenticated `
  --set-build-env-vars "GOOGLE_RUNTIME_VERSION=17" `
  --env-vars-file .local/cloud-run.env.yaml `
  --set-secrets "CODEARENA_JWT_SECRET=codearena-jwt:1,CODEARENA_DB_PASSWORD=codearena-db:1,CODEARENA_JUDGE0_TOKEN=codearena-judge:1" `
  --port 8080 --cpu 1 --memory 1Gi --concurrency 24 `
  --min 1 --max 1 --no-cpu-throttling `
  --startup-probe "httpGet.path=/actuator/health/readiness,httpGet.port=8080,periodSeconds=5,timeoutSeconds=3,failureThreshold=20" `
  --liveness-probe "httpGet.path=/actuator/health/liveness,httpGet.port=8080,periodSeconds=15,timeoutSeconds=3,failureThreshold=3"
```

Cloud Run must allocate CPU outside requests so workers can keep processing the queue. This configuration keeps one instance running and incurs ongoing compute charges. Readiness checks PostgreSQL. Liveness checks the process. In-flight jobs that outlive an instance shutdown are recovered after their database lease expires. [Cloud Run container lifecycle](https://docs.cloud.google.com/run/docs/container-contract)

The production defaults cap the queue at 16 active jobs, allow six jobs per user per minute, and admit up to 400 test executions across the platform per rolling day. Each submitted case consumes one unit of this budget. Set the limits to match your judge plan.

For RabbitMQ dispatch, set `SPRING_PROFILES_ACTIVE: "prod,rabbitmq"` in the runtime settings. Use a dedicated virtual host and user with declare, publish, and consume permissions on `codearena.execution`. Set `CODEARENA_RABBITMQ_URL` to one `amqps://host:port` endpoint and supply `CODEARENA_RABBITMQ_VHOST` and `CODEARENA_RABBITMQ_USER` separately. Bind `CODEARENA_RABBITMQ_PASSWORD` from Secret Manager. Readiness also checks the broker in this profile. [RabbitMQ TLS](https://www.rabbitmq.com/docs/ssl)

## 3. Deploy the frontend

Import the repository into Vercel with **Root Directory** set to `frontend` and the **Vite** preset. Set `VITE_API_URL` to the Cloud Run service’s HTTPS origin without `/api`. Use Node.js 22.12 or newer. The checked-in [Vercel config](../frontend/vercel.json) runs the deployment build and routes direct visits to React pages. The build allows connections to the configured API origin in its Content Security Policy. [Vite on Vercel](https://vercel.com/docs/frameworks/frontend/vite)

Use the same Vercel production origin you set in `CODEARENA_FRONTEND_ORIGIN`. To change domains, update the API’s origin and rebuild the frontend with the correct API URL.

## 4. Verify the deployed service

```powershell
.\scripts\check-deployment.ps1 -FrontendOrigin 'https://your-codearena-project.vercel.app' -ApiOrigin 'https://your-api.run.app'
```

The script needs PowerShell 7. It checks readiness, the catalog, private routes, CORS, direct links to practice and career pages, and asset serving. On staging, run the same workflow learners use. Submit a known working solution in each enabled language. Confirm results and history contain public case output. Exercise each coaching action with the staging OpenAI key and verify that a saved career plan can be reopened and removed.

## 5. Operate and recover

- Require the GitHub verification jobs before release. They exercise real PostgreSQL and RabbitMQ, the three language toolchains, the frontend build, and the Compose route from browser origin to judge.
- Monitor the readiness endpoint and the admin-protected `/actuator/prometheus` endpoint. Track `arena_execution_oldest_pending_seconds` for a growing backlog, `arena_execution_recovery_total` for expired or requeued jobs, and `arena_ai_request_seconds` for AI latency and errors. RabbitMQ deployments can also track `arena_execution_publish_total` for broker delivery. Use the managed judge’s health and quota metrics to supplement application metrics.
- The API logs JSON in production and returns `X-Request-ID` on API requests. For user reports, collect that ID, the submission ID, and the time. Provider failures log provider request IDs when available.
- Configure automatic PostgreSQL backups and point-in-time recovery. Verify a restore to an isolated staging database before release. Take a backup before upgrading an existing deployment. The acceptance-count migration briefly locks submission writes while backfilling, so run it in a maintenance window sized for the submission table.
- If the broker is unavailable, check readiness, TLS, and virtual-host permissions first. After connectivity returns, pending IDs are republished. Review the pending-age and recovery metrics before opening traffic again. The public API uses the hosted judge in the production profile.

Use the [native Windows setup](../README.md#windows-setup-without-docker) for local development. The [Compose setup](../README.md#start-with-docker) exercises the RabbitMQ path with a trusted development judge.
