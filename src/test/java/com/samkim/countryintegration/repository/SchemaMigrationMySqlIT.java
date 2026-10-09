package com.samkim.countryintegration.repository;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

class SchemaMigrationMySqlIT {
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("migration_checks");
    static { MYSQL.start(); }

    Flyway flyway(boolean baseline) {
        return Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").baselineOnMigrate(baseline)
                .baselineVersion("1").cleanDisabled(false).load();
    }

    @BeforeEach void resetIsolatedDatabase() { flyway(false).clean(); }

    @Test void migratesFreshDatabaseAndValidatesSchema() throws Exception {
        assertEquals(1, flyway(false).migrate().migrationsExecuted);
        flyway(false).validate();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO countries (iso_code,name) VALUES ('GW','Guinea-Bissau')");
            statement.executeUpdate("INSERT INTO languages (iso_code,name,country_id) VALUES ('por','Portuguese',1)");
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM languages WHERE country_id=1")) {
                assertTrue(result.next()); assertEquals(1, result.getInt(1));
            }
        }
    }

    @Test void explicitlyBaselinesExistingSchemaWithoutLosingData() throws Exception {
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            String sql = new String(getClass().getResourceAsStream("/db/migration/V1__country_schema.sql").readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            for (String command : sql.split(";")) if (!command.isBlank()) statement.execute(command);
            statement.executeUpdate("INSERT INTO countries (iso_code,name) VALUES ('KE','Kenya')");
            statement.executeUpdate("INSERT INTO languages (iso_code,name,country_id) VALUES ('swa','Swahili',1)");
        }
        assertThrows(org.flywaydb.core.api.FlywayException.class, () -> flyway(false).migrate());
        flyway(true).migrate();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT c.name,l.name FROM countries c JOIN languages l ON l.country_id=c.id")) {
            assertTrue(result.next()); assertEquals("Kenya",result.getString(1));
            assertEquals("Swahili",result.getString(2)); assertFalse(result.next());
        }
    }

    @Test void rejectsChangedMigrationChecksum() throws Exception {
        flyway(false).migrate();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE flyway_schema_history SET checksum=0 WHERE version='1'");
        }
        assertThrows(org.flywaydb.core.api.FlywayException.class, () -> flyway(false).validate());
    }
}
