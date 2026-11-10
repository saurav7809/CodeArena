# Deploy CodeArena

Deploy the React frontend to Vercel and the Spring Boot API to Cloud Run. Use a separate managed PostgreSQL database and a private hosted Judge0-compatible sandbox. The Cloud Run buildpack builds the Java service from source. The local Python judge stays on the development machine.

## 1. Prepare the database and hosted judge

- Create a PostgreSQL 17 database with a dedicated account that owns the application schema so Flyway can apply the migrations. Use a TLS endpoint with a certificate trusted by the Java runtime. Set the JDBC URL to use `sslmode=verify-full`. The [sample settings](../deploy/cloud-run.env.example.yaml) use the JVM trust store. Put the database password in Secret Manager. [pgJDBC SSL settings](https://jdbc.postgresql.org/documentation/ssl/)
- Choose a maintained hosted judge that authenticates both job creation and result reads, isolates compilation and execution, and denies network access to submitted programs. Confirm batch submission and polling support and check its authenticated `GET /languages` response for Python 3, Java 17 or newer, and C++17. Language IDs vary by provider. Use a provider whose runtime and memory settings support the three CodeArena templates. [Judge0 API](https://ce.judge0.com/docs)
- Review the provider’s data retention settings. The API sends submitted source and test input to the hosted judge. It compares results in Spring Boot and keeps expected outputs in the application database. It returns only sample output and aggregate results to the browser.

## 2. Deploy the API from source

Choose the frontend’s production HTTPS origin first. In Google Cloud, enable Cloud Run, Cloud Build, Artifact Registry, and Secret Manager. Use a runtime service account with access to the specific secrets below. Follow the build permission steps for your project. [Cloud Run source deployments](https://docs.cloud.google.com/run/docs/deploying-source-code)

Copy [the sample runtime settings](../deploy/cloud-run.env.example.yaml) to the ignored path `.local/cloud-run.env.yaml__ and fill in the origin, database, hosted judge URL, and the three language IDs. Set `CODEARENA_JUDGE0_AUTH` to `rapidapi` when your provider uses RapidAPI headers. Use `token` for a provider that expects `X-Auth-Token`.

Create these Secret Manager secrets in the same project.

| Secret | Value |
| --- | --- |
| `codearena-jwt` | A newly generated, random signing secret of at least 32 bytes |
| `codearena-db` | Password for the production PostgreSQL account |
| `codearena-judge` | Hosted judge token or RapidAPI key |

If you need the initial admin account, optionally create a fourth secret with a random 16–72 character ASCII password and add `CODEARENA_ADMIN_PASSWORD=codearena-admin:1` to the secret bindings. The API creates `arena_admin` on first startup if the account is absent. Once the account exists, remove the bootstrap secret binding.

Run this from the repo root with the Google Cloud CLI authenticated. Replace `REGION`, `PROJECT_ID`, and the example secret versions with your own values.

```powershell
gcloud run deploy codearena-api `
  --source backend `
  --region REGION `
  --service-account codearena-runtime@PROJECT_ID.iam.gserviceaccount.com `
  --allow-unauthenticated `
  --set-build-env-vars "GOOGLE_RUNTIME_VERSION=17" `
  --env-vars-file .local/cloud-run.env.yaml `
  --set-secrets "CODEARENA_JWT_SECRET=codearena-jwt:1,CODEARENA_DB_PASSWORD=codearena-db:1,CODEARENA_JUDGE0_TOKEN=codearena-judge:1" `
  --port 8080 --cpu 1 --memory 1Gi --concurrency 24 `
  --min 1 --max 1 --no-cpu-throttling `
  --startup-probe "httpGet.path=/api/health,httpGet.port=8080,periodSeconds=5,timeoutSeconds=3,failureThreshold=20"
```

Cloud Run must allocate CPU outside requests for the background worker to process the PostgreSQL queue. This configuration keeps one instance running and incurs ongoing compute charges. The API uses JWTs and backend role checks for private routes. The public startup probe checks database connectivity. [Cloud Run billing and background CPU](https://docs.cloud.google.com/run/docs/configuring/billing-settings)

The production defaults cap the queue at 16 active jobs, allow six jobs per user per minute, and admit up to 400 test executions across the platform per rolling day. Each submitted case consumes one unit of this budget. Set the limits to match your judge plan.

## 3. Deploy the frontend

Import the repository into Vercel with **Root Directory** set to `frontend` and the **Vite** preset. Set `VITE_API_URL` to the Cloud Run service’s HTTPS origin without `/api`. Use Node.js 22.12 or newer. The checked-in [Vercel config](../frontend/vercel.json) runs the deployment build and routes direct visits to React pages. The build allows connections to the configured API origin in its Content Security Policy. [Vite on Vercel](https://vercel.com/docs/frameworks/frontend/vite)

Use the same Vercel production origin you set in `CODEARENA_FRONTEND_ORIGIN`. To change domains, update the API’s origin and rebuild the frontend with the correct API URL.

## 4. Verify the deployed service

```powershell
.\scripts\check-deployment.ps1 -FrontendOrigin 'https://your-codearena-project.vercel.app' -ApiOrigin 'https://your-api.run.app'
```

The script needs PowerShell 7. It checks the public catalog, private routes, CORS, direct frontend links, and asset serving. The probe confirms judge configuration. To verify the provider itself, sign in as a regular user and submit a known working solution in each enabled language. Confirm the correct verdict appears in history and hidden output stays private.

For long-running public hosting, plan maintenance for the Spring stack. Spring Boot 3.5.16 was the last open-source release of 3.5. The app retains the requested 3.5 line and pins Tomcat 10.1.61. Commercial Spring support or a move to a supported open-source line is needed for ongoing Spring fixes. [Spring Boot support](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/) [Tomcat security releases](https://tomcat.apache.org/security-10.html)

For local development, use the [Windows setup](../README.md#start-locally).
