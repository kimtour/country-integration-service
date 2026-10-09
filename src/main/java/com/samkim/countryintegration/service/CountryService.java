package com.samkim.countryintegration.service;

import java.util.List;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.samkim.countryintegration.dto.UpdateCountryRequest;
import com.samkim.countryintegration.integration.CountrySoapClient;
import com.samkim.countryintegration.model.CountryInfo;
import com.samkim.countryintegration.model.Language;
import com.samkim.countryintegration.repository.CountryRepository;

@Service
public class CountryService {

    private final CountrySoapClient soapClient;
    private final CountryRepository repository;

    public CountryService(
            CountrySoapClient soapClient,
            CountryRepository repository) {
        this.soapClient = soapClient;
        this.repository = repository;
    }

    public CountryInfo createCountry(String name) {
        String normalizedName = normalizeName(name);
        String isoCode;

        try {
            isoCode = resolveIsoCode(normalizedName);

        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Country was not found",
                    exception);

        } catch (IllegalStateException exception) {
            throw invalidSoapResponse(exception);
        }

        if (repository.findByIsoCode(isoCode).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Country already exists with ISO code " + isoCode);
        }

        CountryInfo country;

        try {
            country = soapClient.fetchFullCountryInfo(isoCode);

        } catch (IllegalStateException exception) {
            throw invalidSoapResponse(exception);
        }

        try {
            return repository.saveAndFlush(country);

        } catch (DataIntegrityViolationException exception) {
            if (repository.findByIsoCode(isoCode).isPresent()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Country already exists with ISO code " + isoCode,
                        exception);
            }

            throw exception;
        }
    }

    public List<CountryInfo> getAllCountries() {
        return repository.findAll();
    }

    public CountryInfo getCountryById(Long id) {
        if (id == null || id < 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Country ID must be a positive number");
        }

        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Country was not found with ID " + id));
    }

    @Transactional
    public CountryInfo updateCountry(Long id, UpdateCountryRequest request) {
        CountryInfo country = getCountryById(id);

        country.setName(request.name().trim());
        country.setCapitalCity(request.capitalCity().trim());
        country.setPhoneCode(request.phoneCode().trim());
        country.setContinentCode(request.continentCode().trim());
        country.setCurrencyIsoCode(request.currencyIsoCode().trim());
        country.setCountryFlag(request.countryFlag());

        List<Language> languages = request.languages().stream()
                .map(language -> new Language(
                        language.isoCode().trim(),
                        language.name().trim()))
                .toList();

        country.replaceLanguages(languages);

        return repository.saveAndFlush(country);
    }

    @Transactional
    public void deleteCountry(Long id) {
        CountryInfo country = getCountryById(id);
        repository.delete(country);
        repository.flush();
    }

    private ResponseStatusException invalidSoapResponse(
            IllegalStateException exception) {

        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Country information service returned an invalid response",
                exception);
    }

    private String resolveIsoCode(String sentenceCaseName) {
        try {
            return soapClient.fetchIsoCode(sentenceCaseName);
        } catch (IllegalArgumentException unknownCountry) {
            // The assessment requires sentence case, but the provider uses exact
            // title-case names for countries such as South Africa.
            if (!sentenceCaseName.contains(" ")) {
                throw unknownCountry;
            }
            String titleCaseName = java.util.Arrays.stream(sentenceCaseName.split(" "))
                    .map(word -> word.substring(0, 1).toUpperCase(Locale.ROOT)
                            + word.substring(1))
                    .collect(java.util.stream.Collectors.joining(" "));
            if (titleCaseName.equals(sentenceCaseName)) {
                throw unknownCountry;
            }
            return soapClient.fetchIsoCode(titleCaseName);
        }
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Country name is required");
        }

        String trimmed = name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);

        return trimmed.substring(0, 1).toUpperCase(Locale.ROOT)
                + trimmed.substring(1);
    }
}