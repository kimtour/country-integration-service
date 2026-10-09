package com.samkim.countryintegration.repository;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
class CountryRepositoryTest extends CountryPersistenceChecks {
}
