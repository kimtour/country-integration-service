package com.samkim.countryintegration.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.samkim.countryintegration.model.CountryInfo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CountrySoapClientTest {

    private SoapTransport transport;
    private CountrySoapClient client;

    @BeforeEach
    void setUp() {
        transport = mock(SoapTransport.class);
        client = new CountrySoapClient(transport);
    }

    @Test
    void readsIsoCodeRegardlessOfNamespacePrefix() {
        when(transport.send(anyString())).thenReturn(envelope("""
                <x:CountryISOCodeResponse>
                    <x:CountryISOCodeResult>KE</x:CountryISOCodeResult>
                </x:CountryISOCodeResponse>
                """));

        assertEquals("KE", client.fetchIsoCode("Kenya"));
    }

    @Test
    void readsCountryFieldsAndMultipleLanguages() {
        when(transport.send(anyString())).thenReturn(envelope("""
                <x:FullCountryInfoResponse>
                    <x:FullCountryInfoResult>
                        <x:sISOCode>KE</x:sISOCode>
                        <x:sName>Kenya</x:sName>
                        <x:sCapitalCity>Nairobi</x:sCapitalCity>
                        <x:sPhoneCode>254</x:sPhoneCode>
                        <x:sContinentCode>AF</x:sContinentCode>
                        <x:sCurrencyISOCode>KES</x:sCurrencyISOCode>
                        <x:sCountryFlag>http://example.com/ke.jpg</x:sCountryFlag>
                        <x:Languages>
                            <x:tLanguage>
                                <x:sISOCode>swa</x:sISOCode>
                                <x:sName>Swahili</x:sName>
                            </x:tLanguage>
                            <x:tLanguage>
                                <x:sISOCode>eng</x:sISOCode>
                                <x:sName>English</x:sName>
                            </x:tLanguage>
                        </x:Languages>
                    </x:FullCountryInfoResult>
                </x:FullCountryInfoResponse>
                """));

        CountryInfo country = client.fetchFullCountryInfo("KE");

        assertEquals("KE", country.getIsoCode());
        assertEquals("Kenya", country.getName());
        assertEquals("Nairobi", country.getCapitalCity());
        assertEquals("254", country.getPhoneCode());
        assertEquals("AF", country.getContinentCode());
        assertEquals("KES", country.getCurrencyIsoCode());
        assertEquals("http://example.com/ke.jpg", country.getCountryFlag());
        assertEquals(2, country.getLanguages().size());
        assertEquals("swa", country.getLanguages().get(0).getIsoCode());
        assertEquals("English", country.getLanguages().get(1).getName());
    }

    @Test
    void rejectsEmptyIsoCodeResult() {
        when(transport.send(anyString())).thenReturn(envelope("""
                <x:CountryISOCodeResponse>
                    <x:CountryISOCodeResult/>
                </x:CountryISOCodeResponse>
                """));

        assertThrows(
                IllegalArgumentException.class,
                () -> client.fetchIsoCode("UnknownCountry"));
    }

    @Test
    void rejectsSoapFault() {
        when(transport.send(anyString())).thenReturn(envelope("""
                <soap:Fault>
                    <faultcode>soap:Server</faultcode>
                    <faultstring>Service failure</faultstring>
                </soap:Fault>
                """));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> client.fetchIsoCode("Kenya"));

        assertTrue(exception.getMessage().contains("SOAP fault"));
    }

    @Test
    void rejectsMalformedXml() {
        when(transport.send(anyString())).thenReturn("<broken>");

        assertThrows(
                IllegalStateException.class,
                () -> client.fetchIsoCode("Kenya"));
    }

    @Test
    void rejectsDoctypeAndExternalEntities() {
        when(transport.send(anyString())).thenReturn("""
                <?xml version="1.0"?>
                <!DOCTYPE example [
                    <!ENTITY external SYSTEM "file:///nonexistent-test-file">
                ]>
                <example>&external;</example>
                """);

        assertThrows(
                IllegalStateException.class,
                () -> client.fetchIsoCode("Kenya"));
    }

    @Test
    void escapesSpecialCharactersInCountryName() {
        when(transport.send(anyString())).thenReturn(envelope("""
                <x:CountryISOCodeResponse>
                    <x:CountryISOCodeResult>KE</x:CountryISOCodeResult>
                </x:CountryISOCodeResponse>
                """));

        client.fetchIsoCode("A&B");

        verify(transport).send(argThat(
                request -> request.contains(
                        "<web:sCountryName>A&amp;B</web:sCountryName>")));
    }

    private String envelope(String body) {
        return """
                <soap:Envelope
                    xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                    xmlns:x="http://www.oorsprong.org/websamples.countryinfo">
                    <soap:Body>
                        %s
                    </soap:Body>
                </soap:Envelope>
                """.formatted(body);
    }
}