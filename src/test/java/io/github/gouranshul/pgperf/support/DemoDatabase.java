package io.github.gouranshul.pgperf.support;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * One Postgres container shared by every integration test, built from the same Dockerfile and init
 * scripts as {@code docker compose}: pg_stat_statements preloaded, hypopg installed, the
 * {@code mcp_readonly} role and the demo shop schema seeded at 1% scale.
 */
public final class DemoDatabase {

    public static final String READONLY_USER = "mcp_readonly";
    public static final String READONLY_PASSWORD = "test-only-readonly-password";

    private static PostgreSQLContainer container;

    private DemoDatabase() {
    }

    public static synchronized PostgreSQLContainer get() {
        if (container == null) {
            String image = new ImageFromDockerfile("pg-perf-mcp-db-test", false)
                    .withFileFromPath(".", Path.of("docker/postgres"))
                    .get();
            PostgreSQLContainer pg = new PostgreSQLContainer(
                    DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("shop")
                    .withEnv("MCP_DB_PASSWORD", READONLY_PASSWORD)
                    .withEnv("SEED_SCALE", "0.01")
                    .withCopyFileToContainer(MountableFile.forHostPath("docker/init.sql"), "/docker/init.sql")
                    .withCopyFileToContainer(MountableFile.forHostPath("docker/initdb/00-init.sh", 0755),
                            "/docker-entrypoint-initdb.d/00-init.sh")
                    .withCopyFileToContainer(MountableFile.forHostPath("docker/initdb/10-demo.sh", 0755),
                            "/docker-entrypoint-initdb.d/10-demo.sh")
                    .withCopyFileToContainer(MountableFile.forHostPath("demo/schema.sql"), "/demo/schema.sql")
                    .withCopyFileToContainer(MountableFile.forHostPath("demo/seed.sql"), "/demo/seed.sql")
                    .withCommand("postgres",
                            "-c", "shared_preload_libraries=pg_stat_statements",
                            "-c", "pg_stat_statements.track=all",
                            "-c", "fsync=off");
            pg.start();
            container = pg;
        }
        return container;
    }

    /** Superuser connection for test fixtures (creating lock contention, running workload). */
    public static Connection adminConnection() throws SQLException {
        PostgreSQLContainer pg = get();
        return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    /** Connection as the same read-only role the application uses. */
    public static Connection readonlyConnection() throws SQLException {
        return DriverManager.getConnection(get().getJdbcUrl(), READONLY_USER, READONLY_PASSWORD);
    }
}
