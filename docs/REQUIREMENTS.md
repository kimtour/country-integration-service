# Assessment requirement coverage

Checked against the interviewer’s Integration Microservices Engineer brief and assessment invitation on 9 October 2026. The invitation requests a presentation named `Case Study Submission – Integrations and Microservices Engineer – Samuel Mutua Kimani`, submitted by replying to the original invitation before **3:00 p.m. EAT on 9 October 2026**. That specific invitation deadline takes precedence over the brief’s general 5 p.m. line. Preparing these files does not send an email.

| Brief item | Implementation / evidence |
| --- | --- |
| 1. Spring Boot, Web, JPA, MySQL | `pom.xml`, layered `src/main/java` |
| 2. Install SoapUI and import WSDL | Installed SoapUI 5.10.0; `docs/soapui` project, screenshot, and live request evidence |
| 3. POST name and sentence case | CountryController/CreateCountryRequest/CountryService; single- and multi-word tests |
| 4. CountryISOCode request | CountrySoapClient and SoapTransport; imported SoapUI request |
| 5. FullCountryInfo using ISO result | CountrySoapClient; service/parsing tests and live Tanzania result |
| 6. CountryInfo and Language models | JPA entities; H2 and actual MySQL cascade/orphan tests |
| 7. REST CRUD | Controller/service, validation, HTTP errors and concurrent duplicate checks |
| 8. Kubernetes deployment scripts | `scripts/deploy.sh`, `scripts/verify.sh`, `k8s/*.yaml` |
| 9. Deployment guide | `docs/DEPLOYMENT.md` |
| 10. Troubleshooting guide | Troubleshooting table, diagnostics, image-cache guidance in deployment guide |

## Design and trade-offs

Controllers own HTTP/validation, services coordinate business operations, the SOAP client owns message structure, transport owns resilience, and repositories own database access. SOAP calls finish before the country save, keeping remote delays outside the database transaction.

Replicas are stateless and a Kubernetes Service balances new connections across ready pods. All replicas share durable MySQL data. A unique database constraint handles duplicate races, with 409 verified by two concurrent service calls against real MySQL. Optional HPA and PDB manifests provide scaling/disruption configuration; their behavior under load has not been demonstrated.

Synchronous calls suit interactive country creation. Each SOAP request has a timeout and bounded retries, and the circuit breaker stops repeated calls during outages. The degraded-operation fallback keeps saved-country CRUD available without SOAP while new imports receive an explicit error. No fabricated or stale country details are returned. For bulk import, a durable queue would decouple submission from provider delays; it would require job-status endpoints, retries, idempotency, and dead-letter handling. Read caching should have defined TTL/invalidation, rather than silently returning outdated updates.

Structured ECS logs, request IDs, Actuator health, request metrics and Prometheus exposition support debugging and monitoring integration. Prometheus scraping, dashboards and distributed tracing remain deployment work.

## Production boundaries

The case study demonstrates local deployment, two replicas, persisted shared data, container hardening, Secret-based configuration, probes, and resilience tests. It does not establish production availability, throughput, multi-node failover, database HA, backups, API authentication, or TLS termination. The brief supplies an HTTP SOAP endpoint; HTTPS did not connect from the current environment. COUNTRY_SOAP_URL remains configurable.

Hibernate schema update remains a limitation. The deploy script initializes with one replica to avoid concurrent schema creation on a fresh database. Versioned migrations with schema validation are the next production step. Optional HPA requires the metrics API and enough capacity. Production images should use immutable registry digests, managed secrets, restricted management endpoints and measured connection/thread pool sizes.
