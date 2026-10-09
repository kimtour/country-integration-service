CREATE TABLE countries (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    capital_city VARCHAR(100),
    continent_code VARCHAR(10),
    country_flag VARCHAR(500),
    currency_iso_code VARCHAR(10),
    iso_code VARCHAR(2) NOT NULL,
    name VARCHAR(100) NOT NULL,
    phone_code VARCHAR(20),
    CONSTRAINT uk_country_iso UNIQUE (iso_code)
) ENGINE=InnoDB;

CREATE TABLE languages (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    iso_code VARCHAR(10) NOT NULL,
    name VARCHAR(100) NOT NULL,
    country_id BIGINT,
    CONSTRAINT fk_language_country FOREIGN KEY (country_id) REFERENCES countries(id)
) ENGINE=InnoDB;
