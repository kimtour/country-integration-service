package com.samkim.countryintegration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCountryRequest(

        @NotBlank(message = "Country name is required")
        @Size(max = 100, message = "Country name must be at most 100 characters")
        String name

) {
}