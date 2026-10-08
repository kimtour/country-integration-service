package com.samkim.countryintegration.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import com.samkim.countryintegration.exception.GlobalExceptionHandler;
import com.samkim.countryintegration.model.CountryInfo;
import com.samkim.countryintegration.service.CountryService;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CountryControllerTest {

    private CountryService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(CountryService.class);

        mvc = MockMvcBuilders
                .standaloneSetup(new CountryController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsCountryById() throws Exception {
        when(service.getCountryById(2L))
                .thenReturn(new CountryInfo("KE", "Kenya"));

        mvc.perform(get("/api/countries/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isoCode").value("KE"))
                .andExpect(jsonPath("$.name").value("Kenya"));
    }

    @Test
    void returnsNotFoundWithClearDetail() throws Exception {
        when(service.getCountryById(999L))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Country was not found with ID 999"));

        mvc.perform(get("/api/countries/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail")
                        .value("Country was not found with ID 999"));
    }

    @Test
    void rejectsBlankCountryName() throws Exception {
        mvc.perform(post("/api/countries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name")
                        .value("Country name is required"));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mvc.perform(post("/api/countries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{broken"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void returnsMethodNotAllowedForPostToCountryId() throws Exception {
        mvc.perform(post("/api/countries/2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "kenya"}
                                """))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.status").value(405));

        verifyNoInteractions(service);
    }

    @Test
    void deletesCountryAndReturnsEmptyBody() throws Exception {
        mvc.perform(delete("/api/countries/2"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(service).deleteCountry(2L);
    }
}