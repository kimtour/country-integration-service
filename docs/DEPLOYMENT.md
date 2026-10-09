# Deployment and troubleshooting

## Prerequisites

- Java 25, Docker Desktop, Git, and the included Maven wrapper.
- A Docker Desktop Kubernetes cluster for the Kubernetes instructions.
- Run commands from the project root, beside pom.xml, Dockerfile, and compose.yaml.
- The SOAP service requires outbound network access.

The application uses MySQL 8.4. The Dockerfile builds from source in a Java 25 stage and packages the resulting JAR in a non-root runtime image. No prebuilt JAR is required.

## Local credentials

Create a local `.env` file in the project root:

```dotenv
DB_PASSWORD=replace_with_your_local_app_password
DB_ROOT_PASSWORD=replace_with_your_local_root_password
```

Exclude `.env` from Git. Include a placeholder-only `.env.example` for reviewers. MySQL initialization variables configure credentials when its data directory is empty. Existing database volumes retain their existing credentials.

## Build and test

Run the isolated default tests:

```bash
./mvnw clean verify
```

No live database or password is required. To verify against a temporary real MySQL 8.4 database, start Docker Desktop and run:

```bash
./mvnw -Pmysql-it clean verify
```

55 default tests and 9 MySQL integration tests pass. The latter verifies persistence, eager language loading, replacement/orphan cleanup, cascading deletion, unique ISO codes, and concurrent duplicate handling. See [test evidence](TEST_RESULTS.md). Testcontainers creates a separate database and cleans it up; existing country data is untouched.

For `./mvnw spring-boot:run`, start Compose MySQL and export DB_PASSWORD matching `.env`. Compose reads `.env`; Maven does not load it automatically.

Expected executable JAR:

```text
target/country-integration-service-0.0.1-SNAPSHOT.jar
```

## Docker Compose deployment

```bash
docker compose up -d --build
docker compose ps
docker compose logs --tail=60 app
```

The verified Compose configuration maps the application to localhost:8081 and MySQL to localhost:3307. Inside the Compose network, the application connects to jdbc:mysql://mysql:3306/countrydb. The named mysql_data volume stores database files.

```bash
curl -i http://localhost:8081/actuator/health
curl -i http://localhost:8081/api/countries
curl -i --max-time 45 -X POST http://localhost:8081/api/countries \
  -H 'Content-Type: application/json' -d '{"name":"uganda"}'
```

Health and GET should return 200. A new country returns 201; an existing country returns 409. Application startup must finish before testing.

To stop the Compose containers while preserving their database volume:

```bash
docker compose stop
```

After Java source changes, rebuild and recreate the application container with `docker compose up -d --build app`.

## Kubernetes deployment

Use the tested deployment script:

```bash
./scripts/deploy.sh
```

It builds from source, applies namespace/credentials/MySQL, starts one app replica to initialize the schema, then scales to APP_REPLICAS (default two). KUBE_CONTEXT defaults to docker-desktop. APP_IMAGE defaults to a source-derived build tag from scripts/image-tag.py. Use a new tag for changed source; a restart alone can reuse a stale node image. For a remote cluster, build/push a registry image and use matching configuration. Existing database volumes keep their initialized passwords; updating a Secret alone does not change MySQL accounts.

The manual commands below target the local docker-desktop context.

```bash
kubectl --context=docker-desktop get nodes
kubectl --context=docker-desktop create namespace country-integration \
  --dry-run=client -o yaml | kubectl --context=docker-desktop apply -f -
```

Create the database Secret once from the local credentials file:

```bash
kubectl --context=docker-desktop -n country-integration \
  create secret generic database-credentials --from-env-file=.env
```

An AlreadyExists response means the Secret has been created previously. Check its key names and deployment environment before changing existing credentials. Avoid printing Secret contents in screenshots or committing exported Secrets.

Deploy MySQL and check its rollout and storage:

```bash
kubectl --context=docker-desktop apply -f k8s/mysql.yaml
kubectl --context=docker-desktop -n country-integration \
  rollout status deployment/mysql --timeout=300s
kubectl --context=docker-desktop -n country-integration get pods,pvc,services
```

MySQL should show 1/1 ready, and mysql-data should be Bound. Its 2 GiB claim provides database storage separate from the Compose volume.

Build the application image from source:

```bash
docker build -t country-integration-service:$(python3 scripts/image-tag.py) .
```

Fresh databases run Flyway V1 automatically and Hibernate validates the resulting schema. The script deploys one replica for migration before scaling to two. For an existing pre-Flyway schema, run `./scripts/backup-db.sh` first, then `BASELINE_EXISTING_SCHEMA=true ./scripts/deploy.sh`. This explicitly records baseline version 1 while preserving data. Confirm validation and data, then set `DB_BASELINE_ON_MIGRATE=false` and return to normal deployment. Never use baseline to conceal an incompatible schema.

```bash
kubectl --context=docker-desktop apply -f k8s/app.yaml
kubectl --context=docker-desktop -n country-integration \
  rollout status deployment/country-app --timeout=900s
kubectl --context=docker-desktop -n country-integration get pods -o wide
```

The verified cluster could use the locally built image. Other local cluster setups may require importing it. Remote deployments require a registry-accessible image and credentials where applicable. Use a new image tag for changed application versions so replicas receive the intended build.

Open a terminal for local API access:

```bash
kubectl --context=docker-desktop -n country-integration \
  port-forward service/country-app 8082:8080
```

Keep that terminal open and test from another terminal:

```bash
curl -i http://localhost:8082/actuator/health
curl -i http://localhost:8082/actuator/health/readiness
curl -i http://localhost:8082/actuator/health/liveness
curl -i http://localhost:8082/api/countries
```

The application ConfigMap contains database connection settings, SOAP URL, and JVM memory settings. The application receives only its database password from the Secret. The container runs as a non-root user with privilege escalation disabled and all Linux capabilities dropped.

## Health checks and scaling

| Check | Behavior |
| --- | --- |
| Startup | Allows approximately ten minutes for initialization before restart |
| Readiness | Checks application readiness and database connectivity before Service traffic |
| Liveness | Checks application liveness independently of database availability |

The application requests 500 millicores and 256 MiB, with limits of two CPUs and 768 MiB. Probe requests have a ten-second timeout. Liveness requires six consecutive failed checks at twenty-second intervals before restarting a started container; readiness still requires database connectivity and fails after three checks. Rolling updates replace one pod at a time without adding a third application replica (maxSurge=0, maxUnavailable=1). A replacement must stay ready for fifteen seconds before progressing, and the rollout progress deadline is fifteen minutes. These local settings need measurement under production load.

The final verified deployment has two application replicas and one MySQL replica. Both application replicas share MySQL, so saved countries are available across replicas.

Inspect Service backends:

```bash
kubectl --context=docker-desktop -n country-integration \
  get endpointslices -l kubernetes.io/service-name=country-app -o yaml
```

A Service port-forward attaches to one selected pod. To verify cross-replica data access, list the current pod names and forward each application pod separately:

```bash
kubectl --context=docker-desktop -n country-integration get pods -l app=country-app
# Run each forward in its own terminal, replacing the pod names.
kubectl --context=docker-desktop -n country-integration \
  port-forward pod/FIRST_POD_NAME 8083:8080
kubectl --context=docker-desktop -n country-integration \
  port-forward pod/SECOND_POD_NAME 8084:8080
```

Create a country through localhost:8083 and read the countries through localhost:8084. Restart the port-forward if its selected pod is replaced.

## Verified results

These results were observed during the initial validation on 8 October 2026. A later recheck found both application pods unready after liveness/startup probe timeouts and restarts, while MySQL remained ready. Application logs showed slow Spring Boot initialization and successful database connection. Investigation found CPU throttling, slow initialization, and healthy HTTP 200 probe responses taking about 8.7 seconds, exceeding the previous five-second timeout. No OOM events were recorded in the inspected container. The manifest now gives the app more CPU headroom, a ten-minute startup allowance, and longer probe budgets; rolling updates avoid a third simultaneous JVM. Production availability still requires separate testing.

| Verification | Initial observed result |
| --- | --- |
| Full Maven package build | 27 tests passed and executable JAR produced |
| Compose SOAP integration | Uganda created with 201 and saved to MySQL |
| Compose application restart | Kenya and Uganda remained available |
| Kubernetes storage | MySQL PVC Bound, 2 GiB |
| Kubernetes health | Health, readiness, and liveness returned 200 UP |
| Kubernetes creation | Kenya created with 201; duplicate returned 409 |
| Application scaling | Two pods ready, two ready Service backends |
| Cross-replica persistence | Tanzania created through replica one and read through replica two |

### Verification after resource and probe tuning

On 8 October 2026, the replacement application pods started in 64.443 and 49.013 seconds. The rollout completed with two ready application pods and two ready Service backends; both new pods had zero restarts. Over five minutes, eleven samples checked health, readiness, liveness, and the countries API on each pod through separate port-forwards: all 88 requests returned HTTP 200, all health responses were UP, and both replicas returned identical Kenya and Tanzania records. Pod identity, readiness, and zero restart counts were verified in every sample. MySQL remained ready.

The first cold countries reads took roughly eighteen seconds under local host load; later sampled reads and health requests completed within their test timeouts. This checks recovery over a short local observation window, not production availability or load capacity. Local host/VM contention can still affect latency. The Mac and Docker Desktop need sufficient CPU/memory capacity for predictable behavior; longer probe budgets do not repair exhausted resources.

Country responses reproduce fields supplied by the external SOAP service. Their geopolitical or language data is not independently corrected by the application.

### Final source verification on 9 October 2026

The source-built image `country-integration-service:build-b3d64cbfb5ab61f8` rolled out to two ready application pods with zero restarts. MySQL remained ready with zero restarts and the Service had two ready endpoints. Both replicas returned health, readiness and liveness 200 UP. Guinea-Bissau (GW), Papua-New Guinea (PG), and Moldova (MD) each returned 201 through the first replica and were readable through the second. Existing Kenya, Tanzania and South Africa records survived schema adoption. Duplicate creation returned 409; an unknown country returned 404; bounded pagination returned 200 and invalid bounds returned 400. [Recorded evidence](evidence/kubernetes-verification.json) includes pod identities, image IDs, endpoints and API responses. The final automated run passed 55 default tests plus nine actual MySQL tests (64 total); see [test results](TEST_RESULTS.md).

Prometheus discovered two healthy application targets and loaded availability/error alert rules; Grafana's provisioned dashboard was reachable. An internal Service read workload returned 200 for all 200 requests, distributed 101/99 between replicas. A private backup restored into disposable MySQL with all six countries, six languages and one successful schema-history entry. See the [monitoring](evidence/monitoring-verification.json), [load](evidence/load-results.json), and [restore](evidence/backup-restore-verification.json) evidence. These are bounded local checks, not proof of production capacity or high availability.

The Mac has 8 GB RAM and Docker Desktop exposes about 3.9 GB to containers. The project’s Compose app and MySQL containers were stopped during verification to reduce competing load; their database volume was preserved. Kubernetes remains running. To resume the separate Compose environment, use `docker compose up -d`; allow enough host resources for both environments. This short local check does not establish production capacity or continuous availability.

## Troubleshooting

| Symptom | Action |
| --- | --- |
| Dockerfile missing | Place Dockerfile beside pom.xml and run the build from that folder |
| Port already allocated | Check the existing listener or container and choose an unused host port |
| Empty reply during startup | Inspect logs and wait for the latest Started CountryIntegrationServiceApplication message |
| Database access denied | Check Secret/environment settings against credentials stored in the existing database |
| PVC remains Pending | Inspect the claim and check the cluster's default StorageClass |
| ImagePullBackOff | Inspect pod events and ensure the image is available to the cluster or registry |
| Pod stays unready | Inspect startup logs, database connectivity, probe events, and resource constraints |
| Startup/liveness probe timeouts and restarts | Compare initialization duration and probe latency with the configured budgets; check Docker Desktop CPU/memory availability and pod events. Readiness gates traffic; failed startup/liveness probes can restart the container. Investigate before increasing budgets |
| OOMKilled | Inspect resource usage and tune JVM heap and container memory together |
| Port-forward stops | Select a current ready pod and start the forwarding session again |
| SOAP request fails | Check upstream availability and network access; inspect transport logs and status |

Useful diagnostics:

```bash
docker compose ps -a
docker compose logs --tail=100 app
kubectl --context=docker-desktop -n country-integration get pods,pvc
kubectl --context=docker-desktop -n country-integration describe pod POD_NAME
kubectl --context=docker-desktop -n country-integration logs POD_NAME --tail=100
kubectl --context=docker-desktop -n country-integration logs POD_NAME --previous
kubectl --context=docker-desktop -n country-integration get events --sort-by=.lastTimestamp
kubectl --context=docker-desktop get storageclass
```

Creation depends on SOAP. Stored-country reads use MySQL independently of SOAP. The transport retries eligible failures, applies a circuit breaker, and returns an explicit error when upstream information is unavailable. Logs include request IDs; HTTP metrics are exposed through Actuator. Optional Prometheus scraping, evaluated alert rules and Grafana provisioning are supplied in `k8s/optional/monitoring.yaml`; notification routing remains outside this local configuration.

## Deployment scope and remaining improvements

This is a verified local Docker Desktop deployment. Both application replicas run on one Kubernetes node. The database has one instance. Multi-node availability, database failover, production backups, automatic scaling under load, and load capacity have not been verified.

Versioned migrations and schema validation are implemented. Production preparation still includes registry images pinned to immutable versions, managed or highly available MySQL, TLS and API access controls, restricted management endpoints, secret lifecycle management, production recovery exercises, and sustained resource/load testing. The current SOAP endpoint uses HTTP. Review provider HTTPS support before changing it.

## Repeatable API verification

After port-forwarding the Service:

```bash
./scripts/verify.sh http://localhost:8082
```

This verifies health, readiness, liveness, stored-country reads, HTTP 415 for a text/plain POST, and HTTP 406 for an unsupported response format. It creates no country records. Repeat against pod-specific forwards to inspect both replicas. Forwarding a Service selects a single pod for that session.

## Optional autoscaling and voluntary disruption protection

```bash
kubectl --context=docker-desktop apply --dry-run=server -f k8s/optional/autoscaling.yaml
# Apply only after installing the metrics API and measuring node capacity:
kubectl --context=docker-desktop apply -f k8s/optional/autoscaling.yaml
kubectl --context=docker-desktop -n country-integration get hpa,pdb
```

The HPA targets 70% CPU utilization of requested CPU, with two to four replicas. The PDB keeps at least one app pod available during supported voluntary disruptions. Neither protects the single MySQL instance or provides another node. The optional objects passed server-side validation; autoscaling and disruption behavior have not been exercised. Once HPA manages replicas, avoid repeatedly setting a fixed replica count during normal deployment.

## Final improvements on 9 October 2026

- Sentence-case lookup remains the first attempt. Punctuation-aware title case handles Guinea-Bissau and Papua-New Guinea; a six-hour provider catalogue cache supplies exact canonical spelling where needed. Provider connection failures never trigger name-format retries.
- GET `/api/countries?page=0&size=100` returns a bounded JSON array. Page is zero-based; size is 1–100. Read pages until an empty array for the complete collection.
- A 20-second budget covers the synchronous SOAP sequence, including eligible retries. It does not bound database operations or servlet scheduling. Request timeout is capped by remaining budget; exhaustion returns 504.
- Structured business logs share the existing request ID. They record resolved ISO, duplicate detection, saved ID, failures, and flushed update/delete outcomes. Request bodies and credentials are excluded. Custom metrics are `country.operations` and `country.import.duration`.
- `IMAGE_LOAD_MODE=auto` supports local Docker Desktop, kind import and minikube import. Remote contexts require explicit registry `APP_IMAGE` and `PUSH_IMAGE=true`. Verify registry access and storage class separately.

### Schema adoption and backup

```bash
./scripts/backup-db.sh
BASELINE_EXISTING_SCHEMA=true ./scripts/deploy.sh
# After validating Flyway history and preserved data:
kubectl --context=docker-desktop -n country-integration set env deployment/country-app DB_BASELINE_ON_MIGRATE=false
```

The backup command uses MySQL's existing pod credentials without printing them and writes an owner-only gzip under ignored `target/backups`. Move private backups to protected storage before `mvn clean`. A local restore was verified in disposable MySQL: six countries, six languages and one successful schema-history entry were recovered. Reproduce the isolated check with:

```bash
python3 scripts/test-backup-restore.py target/backups/countrydb-YYYYMMDD-HHMMSS.sql.gz \
  --expected-iso KE,TZ,ZA,GW,PG,MD \
  --output target/backup-restore-verification.json
```

Supply the ISO codes expected in your own backup. The script removes its test container and never imports into the application database. This check does not establish production RTO/RPO, storage recovery or HA. For an existing Compose schema, set `DB_BASELINE_ON_MIGRATE=true` once after backup, then return to false. Do not delete volumes to bypass schema errors.

### Monitoring

```bash
kubectl --context=docker-desktop apply -f k8s/optional/monitoring.yaml
kubectl --context=docker-desktop -n country-integration rollout status deployment/country-prometheus
kubectl --context=docker-desktop -n country-integration rollout status deployment/country-grafana
kubectl --context=docker-desktop -n country-integration port-forward service/country-prometheus 9090:9090
# Another terminal:
kubectl --context=docker-desktop -n country-integration port-forward service/country-grafana 3000:3000
```

Prometheus discovers namespace application pods and evaluates availability/error rules. Grafana provisions the service dashboard with HTTP latency/rate, import duration and business outcomes. Anonymous Viewer is enabled for this internal local setup; access controls, persistent storage and notification destinations must be configured before wider use. See `docs/evidence/monitoring-verification.json` for observed targets/dashboard state. This 8 GB laptop has a 4 GB Docker allocation; avoid running Compose, integration test containers and monitoring simultaneously when measuring Kubernetes.

### Read workload

```bash
python3 scripts/load-test.py --requests 200 --concurrency 8 --output target/load-results.json
```

This reads data and records request rate, p50/p95/max latency, statuses and X-Instance-ID distribution. A Service port-forward remains bound to a single pod; use `./scripts/load-test-k8s.sh` for an internal ClusterIP Service distribution test. The recorded local sample is in `docs/evidence/load-results.json`. The local cluster has no metrics API, so the optional HPA cannot demonstrate autoscaling here. A metrics-server-enabled multi-node environment, sustained mixed load and failure testing are still needed for production capacity/HA claims.
