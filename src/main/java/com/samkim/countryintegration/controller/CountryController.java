package com.samkim.countryintegration.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.samkim.countryintegration.dto.CreateCountryRequest;
import com.samkim.countryintegration.dto.UpdateCountryRequest;
import com.samkim.countryintegration.model.CountryInfo;
import com.samkim.countryintegration.service.CountryService;

@RestController
@RequestMapping("/api/countries")
public class CountryController {

    private final CountryService service;

    public CountryController(CountryService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CountryInfo> createCountry(
            @Valid @RequestBody CreateCountryRequest request) {

        CountryInfo country = service.createCountry(request.name());
        URI location = URI.create("/api/countries/" + country.getId());

        return ResponseEntity.created(location).body(country);
    }

    @GetMapping
    public List<CountryInfo> getAllCountries(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return service.getCountries(page, size);
    }

    @GetMapping("/{id}")
    public CountryInfo getCountryById(@PathVariable("id") Long id) {
        return service.getCountryById(id);
    }

    @PutMapping("/{id}")
    public CountryInfo updateCountry(
            @PathVariable("id") Long id,
            @Valid @RequestBody UpdateCountryRequest request) {
        return service.updateCountry(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCountry(@PathVariable("id") Long id) {
        service.deleteCountry(id);
        return ResponseEntity.noContent().build();
    }
}