package com.samkim.countryintegration.service;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.samkim.countryintegration.integration.CountrySoapClient;
import com.samkim.countryintegration.repository.CountryRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CountryServiceFailureTest {

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
    void mapsInvalidIsoResponseToBadGateway() {
        when(soapClient.fetchIsoCode("Kenya"))
                .thenThrow(new IllegalStateException("Invalid XML"));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.createCountry("kenya"));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
        verifyNoInteractions(repository);
    }

    @Test
    void mapsInvalidFullCountryResponseToBadGateway() {
        when(soapClient.fetchIsoCode("Kenya")).thenReturn("KE");
        when(repository.findByIsoCode("KE")).thenReturn(Optional.empty());
        when(soapClient.fetchFullCountryInfo("KE"))
                .thenThrow(new IllegalStateException("SOAP fault"));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.createCountry("kenya"));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void preservesServiceUnavailableStatus() {
        ResponseStatusException failure = new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable");

        when(soapClient.fetchIsoCode("Kenya")).thenThrow(failure);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.createCountry("kenya"));

        assertSame(failure, exception);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
        verifyNoInteractions(repository);
    }

    @Test
    void preservesGatewayTimeoutStatus() {
        ResponseStatusException failure = new ResponseStatusException(
                HttpStatus.GATEWAY_TIMEOUT, "Service timed out");

        when(soapClient.fetchIsoCode("Kenya")).thenThrow(failure);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.createCountry("kenya"));

        assertSame(failure, exception);
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, exception.getStatusCode());
        verifyNoInteractions(repository);
    }
}