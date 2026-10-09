# Verified test results

Executed on **9 October 2026 (EAT)** against the final source:

```bash
./mvnw -B -Pmysql-it clean verify
```

**BUILD SUCCESS: 42 default tests + 5 isolated MySQL tests = 47 tests; zero failures, errors, or skips.** The complete run finished at 09:46:53 EAT. Default tests use an isolated H2 database, Mockito, standalone MockMvc and local HTTP servers. The MySQL profile uses Testcontainers with an independent MySQL 8.4 database, requiring only a running Docker engine. It never connects to the development or Kubernetes country database.

| Class | Tests | Suite |
| --- | ---: | --- |
| CountryIntegrationServiceApplicationTests | 1 | default |
| CountryControllerTest | 8 | default |
| CountrySoapClientTest | 9 | default |
| SoapTransportTest | 3 | default |
| CountryRepositoryTest | 4 | default |
| CountryServiceFailureTest | 7 | default |
| CountryServiceTest | 10 | default |
| CountryRepositoryMySqlIT | 5 | mysql |

The [machine-readable summary](evidence/test-results.json) and sanitized JUnit XML files in the same directory preserve the runner’s test-case names and outcomes. Environment properties and console streams were removed from the XML exports; these files are execution evidence, not a substitute for rerunning tests.

Regression checks cover 406/415 preservation; provider text for unknown countries; actual parser-to-service multi-word fallback; invalid ISO data; normalization; concurrent duplicate creates returning one success and one 409; language cascade/orphan cleanup and reads after detachment; XML hardening; retry and circuit behavior.

## Reproduce

```bash
# Java 25; no Docker or external services needed:
./mvnw clean verify

# Java 25 and Docker; temporary real MySQL:
./mvnw -Pmysql-it clean verify
```

The [GitHub workflow](../.github/workflows/ci.yaml) runs the full profile, uploads the original reports and builds the image. Hosted workflow results must be inspected separately; this document records local verification.

## Additional live evidence

SoapUI passed **3 live provider test cases and 9 assertions**: Tanzania → TZ, FullCountryInfo(TZ) → Tanzania, and South Africa → ZA. See [SoapUI results and request/response samples](soapui/README.md). These external calls are separate from the 47 Maven tests and depend on network/provider availability.

Kubernetes verification and local deployment limits are documented in [DEPLOYMENT.md](DEPLOYMENT.md). Unit/integration passes do not establish production throughput or availability.

On 9 October 2026, the final source-derived Kubernetes image passed checks on both replicas: health/readiness/liveness and countries returned 200, unsupported content type returned 415, and unsupported response format returned 406. Creating `south africa` through the first replica returned 201 with ISO ZA; the second replica returned Kenya, Tanzania, and South Africa. Repeating the POST returned 409 and an unknown country returned 404. Both application pods and MySQL were ready with zero restarts; the Service had two ready endpoints. See [recorded Kubernetes verification](evidence/kubernetes-verification.json).
