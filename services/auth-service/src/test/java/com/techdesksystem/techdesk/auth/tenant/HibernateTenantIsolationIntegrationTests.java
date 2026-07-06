package com.techdesksystem.techdesk.auth.tenant;

import com.techdesksystem.techdesk.auth.entity.User;
import com.techdesksystem.techdesk.auth.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "auth.multitenancy.enabled=true",
        "auth.multitenancy.audit-service-url=http://localhost:1",
        "auth.multitenancy.audit-internal-key=test-internal-key",
        "spring.jpa.hibernate.ddl-auto=validate",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false"
})
class HibernateTenantIsolationIntegrationTests {

    private static final String TENANT_ALPHA = "tenant_alpha";
    private static final String TENANT_BRAVO = "tenant_bravo";
    private static final String SHARED_EMAIL = "shared@example.com";

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("techdesk_multitenancy_test")
                    .withUsername("techdesk")
                    .withPassword("techdesk_test_password")
                    .withStartupTimeout(Duration.ofMinutes(2));

    static {
        POSTGRES.start();
        initializeDatabase();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private SchemaMultiTenantConnectionProvider connectionProvider;

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    private TenantIsolationViolationReporter violationReporter;

    @BeforeEach
    void resetReporter() {
        clearInvocations(violationReporter);
        TenantContext.clear();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    void simultaneousRequestsReadOnlyTheirOwnTenantSchema() throws Exception {
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch startTogether = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> alpha = executor.submit(readFirstName(
                    TENANT_ALPHA,
                    workersReady,
                    startTogether
            ));
            Future<String> bravo = executor.submit(readFirstName(
                    TENANT_BRAVO,
                    workersReady,
                    startTogether
            ));

            assertThat(workersReady.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    .isTrue();
            startTogether.countDown();

            assertThat(List.of(alpha.get(), bravo.get()))
                    .containsExactlyInAnyOrder("Alpha", "Bravo");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void explicitForeignTenantSchemaIsRejectedAndReported() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> {
            try (TenantContext.Scope ignored = TenantContext.open(TENANT_ALPHA)) {
                transaction.executeWithoutResult(status -> entityManager
                        .createNativeQuery(
                                "SELECT email FROM tenant_bravo.auth_users"
                        )
                        .getResultList());
            }
        }).isInstanceOf(TenantIsolationException.class);

        verify(violationReporter).report(argThat(violation ->
                TENANT_ALPHA.equals(violation.expectedTenant())
                        && violation.reason().contains("different tenant schema")
                        && violation.sqlFingerprint() != null
                        && !violation.sqlFingerprint().isBlank()
        ));
    }

    @Test
    void checkedInConnectionIsResetToPublicSchema() throws Exception {
        try (TenantContext.Scope ignored = TenantContext.open(TENANT_ALPHA)) {
            Connection tenantConnection = connectionProvider.getConnection(TENANT_ALPHA);
            assertThat(tenantConnection.getSchema()).isEqualTo(TENANT_ALPHA);
            connectionProvider.releaseConnection(TENANT_ALPHA, tenantConnection);
        }

        try (Connection pooledConnection = dataSource.getConnection()) {
            assertThat(pooledConnection.getSchema()).isEqualTo("public");
        }
    }

    private Callable<String> readFirstName(
            String tenant,
            CountDownLatch workersReady,
            CountDownLatch startTogether
    ) {
        return () -> {
            workersReady.countDown();
            if (!startTogether.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent test did not start.");
            }

            try (TenantContext.Scope ignored = TenantContext.open(tenant)) {
                TransactionTemplate transaction = new TransactionTemplate(
                        transactionManager
                );
                return transaction.execute(status -> userRepository
                        .findByEmail(SHARED_EMAIL)
                        .map(User::getFirstName)
                        .orElseThrow());
            }
        };
    }

    private static void initializeDatabase() {
        migrate("public", publicMigrationLocation());

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        ); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + TENANT_ALPHA);
            statement.execute("CREATE SCHEMA " + TENANT_BRAVO);
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }

        migrate(TENANT_ALPHA, tenantMigrationLocation());
        migrate(TENANT_BRAVO, tenantMigrationLocation());

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        )) {
            seedTenantMetadata(connection, TENANT_ALPHA, "alpha", "Alpha Ltd");
            seedTenantMetadata(connection, TENANT_BRAVO, "bravo", "Bravo Ltd");
            seedUser(connection, TENANT_ALPHA, "Alpha");
            seedUser(connection, TENANT_BRAVO, "Bravo");
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void migrate(String schema, String location) {
        Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword()
                )
                .schemas(schema)
                .defaultSchema(schema)
                .locations(location)
                .load()
                .migrate();
    }

    private static void seedTenantMetadata(
            Connection connection,
            String schema,
            String slug,
            String name
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO public.tenants (
                    name, slug, schema_name, status, provisioning_status
                ) VALUES (?, ?, ?, 'ACTIVE', 'READY')
                """)) {
            statement.setString(1, name);
            statement.setString(2, slug);
            statement.setString(3, schema);
            statement.executeUpdate();
        }
    }

    private static void seedUser(
            Connection connection,
            String schema,
            String firstName
    ) throws Exception {
        String sql = "INSERT INTO " + schema + ".auth_users "
                + "(email, password_hash, first_name, last_name, role, enabled) "
                + "VALUES (?, 'not-used', ?, 'User', 'EMPLOYEE', TRUE)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, SHARED_EMAIL);
            statement.setString(2, firstName);
            statement.executeUpdate();
        }
    }

    private static String publicMigrationLocation() {
        return filesystemLocation("public");
    }

    private static String tenantMigrationLocation() {
        return filesystemLocation("tenant");
    }

    private static String filesystemLocation(String migrationGroup) {
        Path path = Path.of(
                "..",
                "..",
                "infrastructure",
                "db",
                "migration",
                migrationGroup
        ).toAbsolutePath().normalize();
        return "filesystem:" + path.toString().replace('\\', '/');
    }
}
