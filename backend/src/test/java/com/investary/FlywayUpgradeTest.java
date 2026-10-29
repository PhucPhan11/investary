package com.investary;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A Phase 1 database with documents upgrades to the Phase 2 schema without losing data.
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayUpgradeTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @Test
    void phaseOneDataSurvivesPhaseTwoMigration() throws Exception {
        Flyway phaseOne = flyway("1");
        phaseOne.migrate();
        UUID documentId = UUID.randomUUID();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into document (id, original_filename, storage_key, file_size, mime_type, sha256_checksum, report_type, ticker)
                    values ('%s', 'phase1.pdf', '%s.pdf', 42, 'application/pdf', '%s', 'STOCK_ANALYSIS', 'VNM')"""
                    .formatted(documentId, documentId, "b".repeat(64)));
        }

        var result = flyway(null).migrate();

        assertThat(result.migrationsExecuted).isEqualTo(1);
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            ResultSet document = statement.executeQuery("select original_filename, ticker from document where id = '" + documentId + "'");
            assertThat(document.next()).isTrue();
            assertThat(document.getString("original_filename")).isEqualTo("phase1.pdf");
            assertThat(document.getString("ticker")).isEqualTo("VNM");

            ResultSet tables = statement.executeQuery("""
                    select count(*) from information_schema.tables where table_schema = 'public' and table_name in
                    ('source_messages', 'source_attachments', 'source_links', 'message_documents', 'research_items', 'download_jobs')""");
            tables.next();
            assertThat(tables.getInt(1)).isEqualTo(6);
        }
    }

    private static Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
