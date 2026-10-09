package com.samkim.countryintegration.repository;

import java.util.List;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import com.samkim.countryintegration.model.CountryInfo;
import com.samkim.countryintegration.model.Language;
import static org.junit.jupiter.api.Assertions.*;

@org.springframework.transaction.annotation.Transactional
abstract class CountryPersistenceChecks {
    @Autowired CountryRepository repository;
    @PersistenceContext EntityManager entityManager;

    CountryInfo kenya() {
        CountryInfo country = new CountryInfo("KE", "Kenya");
        country.replaceLanguages(List.of(new Language("swa", "Swahili")));
        return repository.saveAndFlush(country);
    }

    @Test
    void persistsCountryAndLoadsLanguagesForBothReadPaths() {
        Long id = kenya().getId();
        entityManager.clear();
        CountryInfo byId = repository.findById(id).orElseThrow();
        assertTrue(entityManager.getEntityManagerFactory().getPersistenceUnitUtil()
                .isLoaded(byId, "languages"));
        entityManager.detach(byId);
        assertEquals("Swahili", byId.getLanguages().getFirst().getName());
        entityManager.clear();
        CountryInfo fromList = repository.findAll().getFirst();
        assertTrue(entityManager.getEntityManagerFactory().getPersistenceUnitUtil()
                .isLoaded(fromList, "languages"));
        entityManager.detach(fromList);
        assertEquals("KE", fromList.getIsoCode());
        assertEquals("swa", fromList.getLanguages().getFirst().getIsoCode());
    }

    @Test
    void replacementRemovesOrphanLanguages() {
        CountryInfo country = kenya();
        Long originalLanguage = country.getLanguages().getFirst().getId();
        country.replaceLanguages(List.of(new Language("eng", "English")));
        repository.saveAndFlush(country);
        entityManager.clear();
        assertNull(entityManager.find(Language.class, originalLanguage));
        assertEquals("English", repository.findById(country.getId()).orElseThrow()
                .getLanguages().getFirst().getName());
        assertEquals(1L, entityManager.createQuery("select count(l) from Language l", Long.class)
                .getSingleResult());
    }

    @Test
    void deleteCascadesToLanguages() {
        CountryInfo country = kenya();
        repository.delete(country);
        repository.flush();
        entityManager.clear();
        assertTrue(repository.findById(country.getId()).isEmpty());
        assertEquals(0L, entityManager.createQuery("select count(l) from Language l", Long.class)
                .getSingleResult());
    }

    @Test
    void databaseRejectsDuplicateIsoCodes() {
        kenya();
        assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(new CountryInfo("KE", "Duplicate")));
    }
    @Test
    void pagesCountriesBeforeFetchingLanguages() {
        kenya();
        CountryInfo second = new CountryInfo("GW", "Guinea-Bissau");
        second.replaceLanguages(List.of(new Language("por", "Portuguese")));
        repository.saveAndFlush(second);
        entityManager.clear();
        var service = new com.samkim.countryintegration.service.CountryService(
                org.mockito.Mockito.mock(com.samkim.countryintegration.integration.CountrySoapClient.class), repository);
        List<CountryInfo> page = service.getCountries(1, 1);
        assertEquals(1, page.size());
        assertEquals("GW", page.getFirst().getIsoCode());
        entityManager.detach(page.getFirst());
        assertEquals("Portuguese", page.getFirst().getLanguages().getFirst().getName());
        assertTrue(service.getCountries(2, 1).isEmpty());
    }
}
