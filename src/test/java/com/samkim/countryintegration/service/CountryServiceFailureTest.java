package com.samkim.countryintegration.service;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import com.samkim.countryintegration.model.CountryInfo;
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

    @Test
    void mapsDuplicateSaveRaceToConflict() {
        CountryInfo country = new CountryInfo("KE", "Kenya");
        when(soapClient.fetchIsoCode("Kenya")).thenReturn("KE");
        when(soapClient.fetchFullCountryInfo("KE")).thenReturn(country);
        when(repository.findByIsoCode("KE"))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(country));
        when(repository.saveAndFlush(country))
                .thenThrow(new DataIntegrityViolationException("Duplicate ISO"));
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> service.createCountry("kenya"));
        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
    }

    @Test
    void preservesUnrelatedDatabaseFailures() {
        CountryInfo country = new CountryInfo("KE", "Kenya");
        when(soapClient.fetchIsoCode("Kenya")).thenReturn("KE");
        when(soapClient.fetchFullCountryInfo("KE")).thenReturn(country);
        when(repository.findByIsoCode("KE")).thenReturn(Optional.empty());
        DataIntegrityViolationException cause = new DataIntegrityViolationException("Invalid data");
        when(repository.saveAndFlush(country)).thenThrow(cause);
        assertSame(cause, assertThrows(DataIntegrityViolationException.class,
                () -> service.createCountry("kenya")));
    }

    @Test
    void parsesProviderUnknownMessageBeforeTryingMultiWordFallback() {
        var transport = mock(com.samkim.countryintegration.integration.SoapTransport.class);
        String envelope = """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                    xmlns:x="http://www.oorsprong.org/websamples.countryinfo">
                  <soap:Body>%s</soap:Body>
                </soap:Envelope>
                """;
        when(transport.send(anyString())).thenReturn(
                envelope.formatted("<x:CountryISOCodeResult>No country found by that name</x:CountryISOCodeResult>"),
                envelope.formatted("<x:CountryISOCodeResult>ZA</x:CountryISOCodeResult>"),
                envelope.formatted("""
                    <x:FullCountryInfoResult><x:sISOCode>ZA</x:sISOCode>
                    <x:sName>South Africa</x:sName><x:sCapitalCity>Pretoria</x:sCapitalCity>
                    </x:FullCountryInfoResult>
                    """));
        when(repository.findByIsoCode("ZA")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(CountryInfo.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        var realClient = new CountrySoapClient(transport);
        var result = new CountryService(realClient, repository).createCountry("south africa");
        assertEquals("ZA", result.getIsoCode());
        assertEquals("Pretoria", result.getCapitalCity());
        var order = inOrder(transport);
        order.verify(transport).send(argThat(body -> body.contains("<web:sCountryName>South africa</web:sCountryName>")));
        order.verify(transport).send(argThat(body -> body.contains("<web:sCountryName>South Africa</web:sCountryName>")));
        order.verify(transport).send(argThat(body -> body.contains("<web:sCountryISOCode>ZA</web:sCountryISOCode>")));
    }
}