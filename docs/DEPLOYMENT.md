# Deployment and troubleshooting

## Prerequisites

- Java 25, Docker Desktop, Git, and the included Maven wrapper.
- A Docker Desktop Kubernetes cluster for the Kubernetes instructions.
- Run commands from the project root, beside pom.xml, Dockerfile, and compose.yaml.
- The SOAP service requires outbound network access.

The application uses MySQL 8.4. The supplied Dockerfile packages an already-built executable JAR. Build the JAR before building the image.

## Local credentials

Create a local `.env` file in the project root:

```dotenv
DB_PASSWORD=replace_with_your_local_app_password
DB_ROOT_PASSWORD=replace_with_your_local_root_password
```

Exclude `.env` from Git. Include a placeholder-only `.env.example` for reviewers. MySQL initialization variables configure credentials when its data directory is empty. Existing database volumes retain their existing credentials.

## Build and test

Start the development database:

```bash
docker compose up -d mysql
```

Set DB_PASSWORD in your shell to the same application password used in `.env`. A local `.env` file is read by Compose; it does not automatically populate the environment of a Maven process.

```bash
export DB_PASSWORD='replace_with_your_local_app_password'
./mvnw clean package
ls -lh target/*.jar
```

The full suite currently includes a Spring Boot context test that requires MySQL at localhost:3307. The other five test classes use mocks, standalone MockMvc, or a local HTTP server. The reported full build passed 27 tests with zero failures, errors, or skipped tests.

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

After Java source changes, run the package build again, then rebuild and recreate the application container with `docker compose up -d --build app`.

## Kubernetes deployment

All commands explicitly target the local docker-desktop context.

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

Build the application image from the packaged JAR:

```bash
docker build -t country-integration-service:0.0.1 .
```

For a fresh database, begin with `replicas: 1` in k8s/app.yaml. The current application creates its schema with Hibernate ddl-auto=update. After the first pod has initialized the schema successfully, set replicas to 2 and reapply. Explicit database migrations are a production improvement still to implement.

```bash
kubectl --context=docker-desktop apply -f k8s/app.yaml
kubectl --context=docker-desktop -n country-integration \
  rollout status deployment/country-app --timeout=360s
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
| Startup | Allows approximately five minutes for initialization before restart |
| Readiness | Checks application readiness and database connectivity before Service traffic |
| Liveness | Checks application liveness independently of database availability |

The application requests 250 millicores and 256 MiB, with limits of one CPU and 768 MiB. These are local practice settings that need measurement under load. One recorded second-replica startup took roughly four minutes.

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

These results were observed during the initial validation on 8 October 2026. A later recheck found both application pods unready after liveness/startup probe timeouts and restarts, while MySQL remained ready. Application logs showed slow Spring Boot initialization and successful database connection. Sustained availability has not been established. Inspect pod events and logs, check Docker Desktop resource availability, and measure startup/probe response times before adjusting resources or probe budgets.

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

Country responses reproduce fields supplied by the external SOAP service. Their geopolitical or language data is not independently corrected by the application.

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

Creation depends on SOAP. Stored-country reads use MySQL independently of SOAP. The transport retries eligible failures, applies a circuit breaker, and returns an explicit error when upstream information is unavailable. Logs include request IDs; HTTP metrics are exposed through Actuator. Prometheus scraping and dashboards have not been demonstrated.

## Deployment scope and remaining improvements

This is a verified local Docker Desktop deployment. Both application replicas run on one Kubernetes node. The database has one instance. Multi-node availability, database failover, production backups, automatic scaling, and load capacity have not been verified.

Production preparation includes versioned schema migrations, reproducible isolated database tests, registry images pinned to immutable versions, managed or highly available MySQL, TLS and API access controls, restricted management endpoints, secret lifecycle management, backup/restore verification, and resource/load testing. The current SOAP endpoint uses HTTP. Review provider HTTPS support before changing it.
