package com.investary;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The database image used by every container test: the same PostgreSQL + pgvector image as
 * {@code infrastructure/docker-compose.yml}, so migrations that need the {@code vector} extension run.
 */
public final class TestDatabase {

    public static final String IMAGE = "pgvector/pgvector:0.8.7-pg17";

    private TestDatabase() {
    }

    public static PostgreSQLContainer container() {
        return new PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"));
    }
}
