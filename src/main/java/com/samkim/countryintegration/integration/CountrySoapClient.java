package com.samkim.countryintegration.integration;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.samkim.countryintegration.model.CountryInfo;
import com.samkim.countryintegration.model.Language;

@Component
public class CountrySoapClient {

    private static final String COUNTRY_NAMESPACE =
            "http://www.oorsprong.org/websamples.countryinfo";

    private static final String SOAP_NAMESPACE =
            "http://schemas.xmlsoap.org/soap/envelope/";

    private final SoapTransport transport;

    public CountrySoapClient(SoapTransport transport) {
        this.transport = transport;
    }

    public String fetchIsoCode(String countryName) {
        if (countryName == null || countryName.isBlank()) {
            throw new IllegalArgumentException("Country name is required");
        }

        String envelope = """
                <soap:Envelope
                    xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                    xmlns:web="http://www.oorsprong.org/websamples.countryinfo">
                    <soap:Body>
                        <web:CountryISOCode>
                            <web:sCountryName>%s</web:sCountryName>
                        </web:CountryISOCode>
                    </soap:Body>
                </soap:Envelope>
                """.formatted(escapeXml(countryName));

        Document document = parseXml(transport.send(envelope));

        NodeList results = document.getElementsByTagNameNS(
                COUNTRY_NAMESPACE, "CountryISOCodeResult");

        if (results.getLength() != 1) {
            throw new IllegalStateException(
                    "Country SOAP response is missing the ISO code result");
        }

        String isoCode = results.item(0).getTextContent().trim();

        if (isoCode.isEmpty() || isoCode.equalsIgnoreCase("No country found by that name")) {
            throw new IllegalArgumentException(
                    "Country was not found: " + countryName);
        }

        if (!isoCode.matches("[A-Z]{2}")) {
            throw new IllegalStateException(
                    "Country SOAP service returned an invalid ISO code");
        }

        return isoCode;
    }

    public CountryInfo fetchFullCountryInfo(String isoCode) {
        if (isoCode == null || !isoCode.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException(
                    "Country ISO code must contain two uppercase letters");
        }

        String envelope = """
                <soap:Envelope
                    xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                    xmlns:web="http://www.oorsprong.org/websamples.countryinfo">
                    <soap:Body>
                        <web:FullCountryInfo>
                            <web:sCountryISOCode>%s</web:sCountryISOCode>
                        </web:FullCountryInfo>
                    </soap:Body>
                </soap:Envelope>
                """.formatted(isoCode);

        Document document = parseXml(transport.send(envelope));

        NodeList results = document.getElementsByTagNameNS(
                COUNTRY_NAMESPACE, "FullCountryInfoResult");

        if (results.getLength() != 1) {
            throw new IllegalStateException(
                    "Country SOAP response is missing full country information");
        }

        Element result = (Element) results.item(0);

        String returnedIsoCode = childText(result, "sISOCode");
        String name = childText(result, "sName");

        if (!isoCode.equals(returnedIsoCode) || name.isBlank()) {
            throw new IllegalStateException(
                    "Country SOAP response contains inconsistent country information");
        }

        CountryInfo country = new CountryInfo(returnedIsoCode, name);
        country.setCapitalCity(childText(result, "sCapitalCity"));
        country.setPhoneCode(childText(result, "sPhoneCode"));
        country.setContinentCode(childText(result, "sContinentCode"));
        country.setCurrencyIsoCode(childText(result, "sCurrencyISOCode"));
        country.setCountryFlag(childText(result, "sCountryFlag"));

        List<Language> languages = new ArrayList<>();

        NodeList languageNodes = result.getElementsByTagNameNS(
                COUNTRY_NAMESPACE, "tLanguage");

        for (int index = 0; index < languageNodes.getLength(); index++) {
            Element languageElement = (Element) languageNodes.item(index);

            String languageCode = childText(languageElement, "sISOCode");
            String languageName = childText(languageElement, "sName");

            if (languageCode.isBlank() || languageName.isBlank()) {
                throw new IllegalStateException(
                        "Country SOAP response contains incomplete language information");
            }

            languages.add(new Language(languageCode, languageName));
        }

        country.replaceLanguages(languages);

        return country;
    }

    private String childText(Element parent, String fieldName) {
        NodeList children = parent.getChildNodes();

        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element child
                    && COUNTRY_NAMESPACE.equals(child.getNamespaceURI())
                    && fieldName.equals(child.getLocalName())) {
                return child.getTextContent().trim();
            }
        }

        return "";
    }

    private Document parseXml(String xml) {
        Document document;

        try {
            DocumentBuilderFactory factory =
                    DocumentBuilderFactory.newInstance();

            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl",
                    true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            document = factory.newDocumentBuilder().parse(
                    new InputSource(new StringReader(xml)));

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not parse the country SOAP response", exception);
        }

        if (document.getElementsByTagNameNS(
                SOAP_NAMESPACE, "Fault").getLength() > 0) {
            throw new IllegalStateException(
                    "Country SOAP service returned a SOAP fault");
        }

        return document;
    }

    private String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}