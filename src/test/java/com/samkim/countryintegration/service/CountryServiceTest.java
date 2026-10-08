package com.samkim.countryintegration.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.samkim.countryintegration.dto.UpdateCountryRequest;
import com.samkim.countryintegration.integration.CountrySoapClient;
import com.samkim.countryintegration.model.CountryInfo;
import com.samkim.countryintegration.model.Language;
import com.samkim.countryintegration.repository.CountryRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CountryServiceTest {

    private CountrySoapClient soapClient;
    private CountryRepository repository;
    private CountryService service;

    @BeforeEach
    void setUp() {
        soapClient = mock(CountrySoapClient.class);
        repository = mock(CountryRepository.class);
        service = new CountryService(soapClient, repository);
    }

    @Test
    void createsCountryUsingNormalizedName() {
        CountryInfo country = kenya();

        when(soapClient.fetchIsoCode("Kenya")).thenReturn("KE");
        when(repository.findByIsoCode("KE")).thenReturn(Optional.empty());
        when(soapClient.fetchFullCountryInfo("KE")).thenReturn(country);
        when(repository.saveAndFlush(country)).thenReturn(country);

        CountryInfo result = service.createCountry("  kENYA  ");

        assertSame(country, result);
        assertEquals("KE", result.getIsoCode());
        assertEquals("Swahili", result.getLanguages().getFirst().getName());

        verify(soapClient).fetchIsoCode("Kenya");
        verify(repository).saveAndFlush(country);
    }

    @Test
    void rejectsBlankNameBeforeCallingSoap() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.createCountry("   "));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(soapClient, repository);
    }

    @Test
    void rejectsDuplicateCountryBeforeFetchingFullDetails() {
        when(soapClient.fetchIsoCode("Kenya")).thenReturn("KE");
        when(repository.findByIsoCode("KE"))
                .thenReturn(Optional.of(kenya()));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.createCountry("kenya"));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(soapClient, never()).fetchFullCountryInfo(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void returnsNotFoundForMissingId() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.getCountryById(999L));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verifyNoInteractions(soapClient);
    }

    @Test
    void updatesDetailsAndReplacesLanguagesWithoutCallingSoap() {
        CountryInfo country = kenya();

        when(repository.findById(2L)).thenReturn(Optional.of(country));
        when(repository.saveAndFlush(country)).thenReturn(country);

        UpdateCountryRequest request = new UpdateCountryRequest(
                "Kenya",
                "Nairobi",
                "254",
                "AF",
                "KES",
                "http://example.com/kenya.jpg",
                List.of(
                        new UpdateCountryRequest.LanguageRequest(
                                "swa", "Swahili"),
                        new UpdateCountryRequest.LanguageRequest(
                                "eng", "English")));

        CountryInfo result = service.updateCountry(2L, request);

        assertEquals("KE", result.getIsoCode());
        assertEquals("http://example.com/kenya.jpg", result.getCountryFlag());
        assertEquals(2, result.getLanguages().size());
        assertTrue(result.getLanguages().stream()
                .anyMatch(language -> "eng".equals(language.getIsoCode())));

        verify(repository).saveAndFlush(country);
        verifyNoInteractions(soapClient);
    }

    @Test
    void deletesExistingCountryWithoutCallingSoap() {
        CountryInfo country = kenya();

        when(repository.findById(2L)).thenReturn(Optional.of(country));

        service.deleteCountry(2L);

        verify(repository).delete(country);
        verify(repository).flush();
        verifyNoInteractions(soapClient);
    }

    private CountryInfo kenya() {
        CountryInfo country = new CountryInfo("KE", "Kenya");
        country.setCapitalCity("Nairobi");
        country.setPhoneCode("254");
        country.setContinentCode("AF");
        country.setCurrencyIsoCode("KES");
        country.replaceLanguages(
                List.of(new Language("swa", "Swahili")));

        return country;
    }
}