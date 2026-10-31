package com.investary;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Existing databases upgrade to newer schemas without losing data.
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayUpgradeTest {

    @Container
    static final PostgreSQLContainer POSTGRES = TestDatabase.container();

    @BeforeEach
    void emptyDatabase() {
        flyway(null).clean();
    }

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

        var result = flyway("2").migrate();

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

    @Test
    void phaseTwoDataSurvivesKnowledgeBaseMigration() throws Exception {
        flyway("2").migrate();
        UUID documentId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into document (id, original_filename, storage_key, file_size, mime_type, sha256_checksum)
                    values ('%s', 'phase2.pdf', '%s.pdf', 42, 'application/pdf', '%s')"""
                    .formatted(documentId, documentId, "c".repeat(64)));
            statement.executeUpdate("""
                    insert into source_messages (id, source_type, storage_key, original_filename, file_size, content_sha256,
                        subject, parsing_status, normalized_text)
                    values ('%s', 'EML_UPLOAD', '%s.eml', 'n.eml', 10, '%s', 'Synthetic newsletter', 'PARSED', 'Body')"""
                    .formatted(messageId, messageId, "d".repeat(64)));
        }

        var result = flyway(null).migrate();

        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(1);
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            ResultSet counts = statement.executeQuery("select (select count(*) from document), (select count(*) from source_messages)");
            counts.next();
            assertThat(counts.getInt(1)).isEqualTo(1);
            assertThat(counts.getInt(2)).isEqualTo(1);

            ResultSet extension = statement.executeQuery("select count(*) from pg_extension where extname = 'vector'");
            extension.next();
            assertThat(extension.getInt(1)).isEqualTo(1);

            ResultSet tables = statement.executeQuery("""
                    select count(*) from information_schema.tables where table_schema = 'public'
                    and table_name in ('knowledge_sources', 'knowledge_chunks')""");
            tables.next();
            assertThat(tables.getInt(1)).isEqualTo(2);
            // Existing sources are not indexed by the migration itself; the reconciler picks them up.
            ResultSet tracked = statement.executeQuery("select count(*) from knowledge_sources");
            tracked.next();
            assertThat(tracked.getInt(1)).isZero();
        }
    }

    private static Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false);
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
