package com.samkim.countryintegration.service;

import java.util.List;
import java.util.Locale;
import java.util.Comparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

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

    private static final Logger log = LoggerFactory.getLogger(CountryService.class);
    private final MeterRegistry metrics;
    private final CountrySoapClient soapClient;
    private final CountryRepository repository;

    public CountryService(
            CountrySoapClient soapClient,
            CountryRepository repository) {
        this(soapClient, repository, new SimpleMeterRegistry());
    }

    @Autowired
    public CountryService(CountrySoapClient soapClient, CountryRepository repository,
            MeterRegistry metrics) {
        this.soapClient = soapClient;
        this.repository = repository;
        this.metrics = metrics;
    }

    public CountryInfo createCountry(String name) {
        String normalizedName = normalizeName(name);
        log.atInfo().addKeyValue("operation", "country_create").log("Country import requested");
        long started = System.nanoTime();
        try (var budget = soapClient.beginImport()) {
            CountryInfo saved = createNormalizedCountry(normalizedName);
            metrics.counter("country.operations", "operation", "create", "outcome", "success").increment();
            return saved;
        } catch (RuntimeException failure) {
            String status = failure instanceof ResponseStatusException error
                    ? String.valueOf(error.getStatusCode().value()) : "500";
            metrics.counter("country.operations", "operation", "create", "outcome", status).increment();
            log.atWarn().addKeyValue("operation", "country_create").addKeyValue("status", status)
                    .log("Country import did not complete");
            throw failure;
        } finally {
            metrics.timer("country.import.duration").record(System.nanoTime() - started,
                    java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private CountryInfo createNormalizedCountry(String normalizedName) {
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

        log.atInfo().addKeyValue("isoCode", isoCode).log("Country ISO resolved");
        if (repository.findByIsoCode(isoCode).isPresent()) {
            log.atWarn().addKeyValue("isoCode", isoCode).log("Duplicate country detected");
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
            CountryInfo saved = repository.saveAndFlush(country);
            log.atInfo().addKeyValue("countryId", saved.getId()).addKeyValue("isoCode", isoCode)
                    .log("Country saved");
            return saved;

        } catch (DataIntegrityViolationException exception) {
            if (repository.findByIsoCode(isoCode).isPresent()) {
                log.atWarn().addKeyValue("isoCode", isoCode).log("Concurrent duplicate country detected");
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Country already exists with ISO code " + isoCode,
                        exception);
            }

            throw exception;
        }
    }

    public List<CountryInfo> getAllCountries() {
        return getCountries(0, 100);
    }

    @Transactional(readOnly = true)
    public List<CountryInfo> getCountries(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "page must be non-negative and size must be between 1 and 100");
        }
        // Page country rows first, then fetch relationships for only these IDs.
        // A collection fetch join directly on a Page can paginate in memory.
        List<Long> ids = repository.findAll(PageRequest.of(page, size, Sort.by("id")))
                .getContent().stream().map(CountryInfo::getId).toList();
        if (ids.isEmpty()) return List.of();
        return repository.findByIdIn(ids).stream()
                .sorted(Comparator.comparing(CountryInfo::getId)).toList();
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

        CountryInfo saved = repository.saveAndFlush(country);
        log.atInfo().addKeyValue("countryId", id).log("Country update flushed");
        metrics.counter("country.operations", "operation", "update", "outcome", "success").increment();
        return saved;
    }

    @Transactional
    public void deleteCountry(Long id) {
        CountryInfo country = getCountryById(id);
        repository.delete(country);
        repository.flush();
        log.atInfo().addKeyValue("countryId", id).log("Country deletion flushed");
        metrics.counter("country.operations", "operation", "delete", "outcome", "success").increment();
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
            // Keep the required sentence-case first request. The provider matches
            // exact punctuation/capitalization, including Guinea-Bissau.
            StringBuilder title = new StringBuilder();
            boolean capitalize = true;
            for (char character : sentenceCaseName.toCharArray()) {
                title.append(capitalize ? Character.toUpperCase(character) : character);
                capitalize = !Character.isLetterOrDigit(character);
            }
            String titleName = title.toString();
            if (!titleName.equals(sentenceCaseName)) {
                try {
                    return soapClient.fetchIsoCode(titleName);
                } catch (IllegalArgumentException stillUnknown) {
                    // Names such as "Moldova, Republic of" need the provider's own spelling.
                }
            }
            String canonical = soapClient.findCanonicalCountryName(sentenceCaseName)
                    .orElseThrow(() -> unknownCountry);
            if (canonical.equals(sentenceCaseName) || canonical.equals(titleName)) {
                throw unknownCountry;
            }
            return soapClient.fetchIsoCode(canonical);
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