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
    void normalizesMultiWordNamesToSentenceCase() {
        CountryInfo country = new CountryInfo("ZA", "South Africa");
        when(soapClient.fetchIsoCode("South africa")).thenReturn("ZA");
        when(repository.findByIsoCode("ZA")).thenReturn(Optional.empty());
        when(soapClient.fetchFullCountryInfo("ZA")).thenReturn(country);
        when(repository.saveAndFlush(country)).thenReturn(country);
        assertSame(country, service.createCountry("  SOUTH   AFRICA  "));
        verify(soapClient).fetchIsoCode("South africa");
    }

    @Test
    void retriesUnknownMultiWordNameWithProviderTitleCase() {
        CountryInfo country = new CountryInfo("ZA", "South Africa");
        when(soapClient.fetchIsoCode("South africa"))
                .thenThrow(new IllegalArgumentException("Unknown country"));
        when(soapClient.fetchIsoCode("South Africa")).thenReturn("ZA");
        when(repository.findByIsoCode("ZA")).thenReturn(Optional.empty());
        when(soapClient.fetchFullCountryInfo("ZA")).thenReturn(country);
        when(repository.saveAndFlush(country)).thenReturn(country);
        assertSame(country, service.createCountry("south africa"));
        var order = inOrder(soapClient);
        order.verify(soapClient).fetchIsoCode("South africa");
        order.verify(soapClient).fetchIsoCode("South Africa");
        order.verify(soapClient).fetchFullCountryInfo("ZA");
    }

    @Test
    void unknownMultiWordCountryStillReturns404() {
        when(soapClient.fetchIsoCode("Unknown country"))
                .thenThrow(new IllegalArgumentException("Unknown country"));
        when(soapClient.fetchIsoCode("Unknown Country"))
                .thenThrow(new IllegalArgumentException("Unknown country"));
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service.createCountry("unknown country"));
        assertEquals(HttpStatus.NOT_FOUND, failure.getStatusCode());
        verifyNoInteractions(repository);
    }

    @Test
    void unknownSingleWordCountryDoesNotRepeatLookup() {
        when(soapClient.fetchIsoCode("Unknown"))
                .thenThrow(new IllegalArgumentException("Unknown country"));
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service.createCountry("unknown"));
        assertEquals(HttpStatus.NOT_FOUND, failure.getStatusCode());
        verify(soapClient, times(1)).fetchIsoCode("Unknown");
        verify(soapClient).beginImport();
        verify(soapClient).findCanonicalCountryName("Unknown");
        verifyNoMoreInteractions(soapClient);
        verifyNoInteractions(repository);
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

    @Test
    void resolvesHyphenatedCountryNames() {
        for (String[] sample : List.of(new String[]{"guinea-bissau", "Guinea-bissau", "Guinea-Bissau", "GW"},
                new String[]{"papua-new guinea", "Papua-new guinea", "Papua-New Guinea", "PG"})) {
            CountryInfo country = new CountryInfo(sample[3], sample[2]);
            when(soapClient.fetchIsoCode(sample[1])).thenThrow(new IllegalArgumentException("Unknown"));
            when(soapClient.fetchIsoCode(sample[2])).thenReturn(sample[3]);
            when(repository.findByIsoCode(sample[3])).thenReturn(Optional.empty());
            when(soapClient.fetchFullCountryInfo(sample[3])).thenReturn(country);
            when(repository.saveAndFlush(country)).thenReturn(country);
            assertSame(country, service.createCountry(sample[0]));
        }
    }

    @Test
    void usesCanonicalProviderSpellingWhenTitleCaseStillFails() {
        when(soapClient.fetchIsoCode("Moldova, republic of")).thenThrow(new IllegalArgumentException("Unknown"));
        when(soapClient.fetchIsoCode("Moldova, Republic Of")).thenThrow(new IllegalArgumentException("Unknown"));
        when(soapClient.findCanonicalCountryName("Moldova, republic of"))
                .thenReturn(Optional.of("Moldova, Republic of"));
        when(soapClient.fetchIsoCode("Moldova, Republic of")).thenReturn("MD");
        CountryInfo country = new CountryInfo("MD", "Moldova, Republic of");
        when(repository.findByIsoCode("MD")).thenReturn(Optional.empty());
        when(soapClient.fetchFullCountryInfo("MD")).thenReturn(country);
        when(repository.saveAndFlush(country)).thenReturn(country);
        assertSame(country, service.createCountry("MOLDOVA, REPUBLIC OF"));
    }

    @Test
    void upstreamFailureDoesNotTriggerNameFallback() {
        ResponseStatusException failure = new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT);
        when(soapClient.fetchIsoCode("Guinea-bissau")).thenThrow(failure);
        assertSame(failure, assertThrows(ResponseStatusException.class,
                () -> service.createCountry("guinea-bissau")));
        verify(soapClient, never()).findCanonicalCountryName(any());
        verify(soapClient, never()).fetchIsoCode("Guinea-Bissau");
    }

    @Test
    void rejectsInvalidPagination() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.getCountries(-1, 20)).getStatusCode());
        assertThrows(ResponseStatusException.class, () -> service.getCountries(0, 101));
        assertThrows(ResponseStatusException.class, () -> service.getCountries(0, 0));
        verifyNoInteractions(repository);
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