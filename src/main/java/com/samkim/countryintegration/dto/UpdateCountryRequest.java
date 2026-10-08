package com.samkim.countryintegration.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateCountryRequest(

        @NotBlank @Size(max = 100)
        String name,

        @NotBlank @Size(max = 100)
        String capitalCity,

        @NotBlank @Size(max = 20)
        String phoneCode,

        @NotBlank @Size(max = 10)
        String continentCode,

        @NotBlank @Size(max = 10)
        String currencyIsoCode,

        @Size(max = 500)
        String countryFlag,

        @NotNull @Size(max = 100)
        List<@NotNull @Valid LanguageRequest> languages

) {

    public record LanguageRequest(

            @NotBlank @Size(max = 10)
            String isoCode,

            @NotBlank @Size(max = 100)
            String name

    ) {
    }
}