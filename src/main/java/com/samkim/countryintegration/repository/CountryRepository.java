package com.samkim.countryintegration.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.samkim.countryintegration.model.CountryInfo;

public interface CountryRepository extends JpaRepository<CountryInfo, Long> {

    Optional<CountryInfo> findByIsoCode(String isoCode);

    @EntityGraph(attributePaths = "languages")
    List<CountryInfo> findByIdIn(List<Long> ids);

    @Override
    @EntityGraph(attributePaths = "languages")
    List<CountryInfo> findAll();

    @Override
    @EntityGraph(attributePaths = "languages")
    Optional<CountryInfo> findById(Long id);
}