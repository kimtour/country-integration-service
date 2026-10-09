# Verified test results

On 9 October 2026 at **12:53:43 EAT**, `./mvnw -B -Pmysql-it clean verify` completed with **BUILD SUCCESS: 55 default + 9 isolated MySQL tests = 64**, zero failures/errors/skips.

```bash
# 55 tests: Java 25; no Docker, credentials or live external services required
./mvnw clean verify
# All 64 tests: Java 25 and a running Docker engine required
./mvnw -Pmysql-it clean verify
```

The default suite uses H2, Mockito, standalone MockMvc and local HTTP servers. The MySQL profile provisions temporary MySQL 8.4 databases through Testcontainers; development and Kubernetes databases are not used.

| Class | Tests | Suite |
| --- | ---: | --- |
| CountryIntegrationServiceApplicationTests | 1 | default |
| CountryControllerTest | 12 | default |
| CountrySoapClientTest | 10 | default |
| SoapTransportTest | 6 | default |
| CountryRepositoryTest | 5 | default |
| CountryServiceFailureTest | 7 | default |
| CountryServiceTest | 14 | default |
| CountryRepositoryMySqlIT | 6 | mysql |
| SchemaMigrationMySqlIT | 3 | mysql |

Coverage includes sentence case, multi-word/hyphen/canonical country names, unknown countries, no fallback on connection failures, POST/PUT success and validation, pagination bounds and relationship loading, duplicate races, XML/DOCTYPE safety, real transport timeouts, SOAP sequence budget cleanup, circuit half-open recovery, MySQL cascade/orphan/unique constraints, fresh Flyway migration, explicit legacy baseline preserving data, and checksum rejection.

[Machine-readable results](evidence/test-results.json) and sanitized JUnit exports accompany this report. Exported XML retains suite counts and case names; environment properties and console logs are deliberately excluded to avoid disclosing local values. Raw reports remain in ignored target/.

SoapUI provides three live provider test cases with nine assertions, separate from Maven's 64 tests. See [project, screenshot and results](soapui/README.md). These tests require network/provider availability.

Docker source builds and Kubernetes checks are separate checks. The fresh image is derived from source inputs; [deployment evidence](evidence/kubernetes-verification.json) records live image IDs and results. Hosted GitHub CI reruns the MySQL profile and Docker build for each push; its result must be read for the exact submitted commit.
