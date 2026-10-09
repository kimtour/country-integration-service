package com.samkim.countryintegration.repository;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CountryRepositoryMySqlIT extends CountryPersistenceChecks {
    // Fails explicitly when Docker is unavailable; never silently skips MySQL checks.
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @org.junit.jupiter.api.Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentCreatesReturnOneSuccessAndOneConflict() throws Exception {
        var soap = org.mockito.Mockito.mock(
                com.samkim.countryintegration.integration.CountrySoapClient.class);
        org.mockito.Mockito.when(soap.fetchIsoCode("Kenya")).thenReturn("KE");
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        org.mockito.Mockito.when(soap.fetchFullCountryInfo("KE")).thenAnswer(invocation -> {
            barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return new com.samkim.countryintegration.model.CountryInfo("KE", "Kenya");
        });
        var service = new com.samkim.countryintegration.service.CountryService(soap, repository);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> create = () -> {
                try {
                    service.createCountry("kenya");
                    return 201;
                } catch (org.springframework.web.server.ResponseStatusException error) {
                    return error.getStatusCode().value();
                }
            };
            var first = workers.submit(create);
            var second = workers.submit(create);
            var statuses = new java.util.ArrayList<>(java.util.List.of(
                    first.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(20, java.util.concurrent.TimeUnit.SECONDS)));
            statuses.sort(Integer::compareTo);
            org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(201,409), statuses);
            org.junit.jupiter.api.Assertions.assertEquals(1, repository.count());
        } finally {
            repository.deleteAll();
        }
    }
}
