package com.techdesksystem.techdesk.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

import com.techdesksystem.techdesk.tenant.dto.CreateTenantRequest;
import com.techdesksystem.techdesk.tenant.dto.TenantResponse;
import com.techdesksystem.techdesk.tenant.entity.OutboxStatus;
import com.techdesksystem.techdesk.tenant.entity.ProvisioningStatus;
import com.techdesksystem.techdesk.tenant.entity.Tenant;
import com.techdesksystem.techdesk.tenant.entity.TenantNotificationOutbox;
import com.techdesksystem.techdesk.tenant.entity.TenantStatus;
import com.techdesksystem.techdesk.tenant.exception.TenantException;
import com.techdesksystem.techdesk.tenant.repository.TenantNotificationOutboxRepository;
import com.techdesksystem.techdesk.tenant.repository.TenantRepository;
import com.techdesksystem.techdesk.tenant.util.SecureTokenUtil;
import com.techdesksystem.techdesk.tenant.util.TenantSchemaNameUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Testcontainers
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.schemas=public",
        "spring.flyway.default-schema=public",
        "tenant.provisioning.notification-initial-delay=1h",
        "tenant.provisioning.recovery-initial-delay=1h"
})
class TenantProvisioningPostgresIntegrationTests {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("techdesk_tenant_test")
                    .withUsername("techdesk")
                    .withPassword("techdesk_test_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        Path publicMigrations = Path.of(
                "..",
                "..",
                "infrastructure",
                "db",
                "migration",
                "public"
        ).toAbsolutePath().normalize();

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add(
                "spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver"
        );
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(
                "spring.flyway.locations",
                () -> "filesystem:"
                        + publicMigrations.toString().replace('\\', '/')
        );
    }

    @Autowired
    private TenantProvisioningService provisioningService;

    @Autowired
    private TenantManagementService managementService;

    @Autowired
    private TenantMetadataService metadataService;

    @Autowired
    private TenantSchemaManager schemaManager;

    @Autowired
    private TenantProvisioningRecoveryJob recoveryJob;

    @Autowired
    private TenantNotificationOutboxWorker outboxWorker;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private TenantNotificationOutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private TenantSchemaMigrator schemaMigrator;

    @MockitoSpyBean
    private DefaultTenantAdminProvisioner adminProvisioner;

    @MockitoBean
    private TenantInvitationNotificationGateway notificationGateway;

    @AfterEach
    void cleanTenantSchemasAndMetadata() {
        reset(schemaMigrator, adminProvisioner, notificationGateway);

        List<Tenant> tenants = tenantRepository.findAll();
        tenants.forEach(tenant ->
                schemaManager.dropSchema(tenant.getSchemaName()));
        tenantRepository.deleteAll();
        tenantRepository.flush();
    }

    @Test
    void provisionsMigratedSchemaDisabledAdminAndHashedInvitation() {
        CreateTenantRequest request = request("provision-success");

        TenantResponse response = provisioningService.provision(request);

        assertThat(response.status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(response.provisioningStatus())
                .isEqualTo(ProvisioningStatus.READY);
        assertThat(schemaManager.exists(response.schemaName())).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"" + response.schemaName()
                        + "\".flyway_schema_history WHERE success = TRUE",
                Integer.class
        )).isEqualTo(1);

        var admin = jdbcTemplate.queryForMap(
                "SELECT id, email, role, enabled FROM \""
                        + response.schemaName() + "\".auth_users"
        );
        assertThat(admin.get("email")).isEqualTo(request.adminEmail());
        assertThat(admin.get("role")).isEqualTo("COMPANY_ADMIN");
        assertThat(admin.get("enabled")).isEqualTo(false);

        TenantNotificationOutbox event = outboxRepository.findAll()
                .stream()
                .filter(candidate -> candidate.getTenantId().equals(response.id()))
                .findFirst()
                .orElseThrow();
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT jti_hash FROM \"" + response.schemaName()
                        + "\".user_invitation_tokens",
                String.class
        );
        assertThat(storedHash)
                .isEqualTo(SecureTokenUtil.sha256(event.getInvitationJti()))
                .isNotEqualTo(event.getInvitationJti());
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        verifyNoInteractions(notificationGateway);
    }

    @Test
    void flywayFailureDropsSchemaAndDeletesTenantMetadata() {
        CreateTenantRequest request = request("flyway-rollback");
        String schemaName = schemaName(request);
        doThrow(new IllegalStateException("simulated migration failure"))
                .when(schemaMigrator)
                .migrate(schemaName);

        assertProvisioningRolledBack(request, schemaName);
    }

    @Test
    void adminFailureAfterMigrationDropsSchemaAndMetadata() {
        CreateTenantRequest request = request("admin-rollback");
        String schemaName = schemaName(request);
        doThrow(new IllegalStateException("simulated admin failure"))
                .when(adminProvisioner)
                .create(anyString(), any(CreateTenantRequest.class));

        assertProvisioningRolledBack(request, schemaName);
    }

    @Test
    void notificationFailureRetriesWithoutDestroyingReadyTenant() {
        TenantResponse response = provisioningService.provision(
                request("notification-retry")
        );
        doThrow(new MailSendException("mail server offline"))
                .when(notificationGateway)
                .sendInvitation(any(), anyString());

        outboxWorker.deliverPendingInvitations();

        TenantNotificationOutbox failed = outboxRepository.findAll()
                .stream()
                .filter(event -> event.getTenantId().equals(response.id()))
                .findFirst()
                .orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(tenantRepository.existsById(response.id())).isTrue();
        assertThat(schemaManager.exists(response.schemaName())).isTrue();

        failed.setNextAttemptAt(Instant.now().minusSeconds(1));
        outboxRepository.saveAndFlush(failed);
        reset(notificationGateway);

        outboxWorker.deliverPendingInvitations();

        TenantNotificationOutbox sent = outboxRepository
                .findById(failed.getId())
                .orElseThrow();
        assertThat(sent.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(sent.getAttempts()).isEqualTo(2);
        assertThat(sent.getSentAt()).isNotNull();
    }

    @Test
    void concurrentDuplicateProvisioningCreatesExactlyOneTenant() throws Exception {
        CreateTenantRequest request = request("concurrent-company");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<ProvisioningAttempt>> futures = List.of(
                    submitProvisioning(executor, ready, start, request),
                    submitProvisioning(executor, ready, start, request)
            );
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ProvisioningAttempt> attempts = futures.stream()
                    .map(this::awaitAttempt)
                    .toList();
            assertThat(attempts).filteredOn(ProvisioningAttempt::succeeded)
                    .hasSize(1);
            assertThat(attempts).filteredOn(attempt ->
                            "TENANT_ALREADY_EXISTS".equals(attempt.errorCode()))
                    .hasSize(1);
            assertThat(tenantRepository.findAll())
                    .filteredOn(tenant -> tenant.getSlug().equals(request.slug()))
                    .hasSize(1);
            assertThat(schemaManager.exists(schemaName(request))).isTrue();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void staleProvisioningAttemptIsRecoveredAfterProcessFailure() {
        Tenant tenant = new Tenant();
        tenant.setName("Abandoned Company");
        tenant.setSlug("abandoned-company");
        tenant.setSchemaName("tenant_abandoned_company");
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setProvisioningStatus(ProvisioningStatus.PROVISIONING);
        tenant = metadataService.create(tenant);
        schemaManager.createSchema(tenant.getSchemaName());
        jdbcTemplate.update(
                "UPDATE public.tenants SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(2, ChronoUnit.HOURS)),
                tenant.getId()
        );

        recoveryJob.removeStaleProvisioningAttempts();

        assertThat(tenantRepository.existsById(tenant.getId())).isFalse();
        assertThat(schemaManager.exists(tenant.getSchemaName())).isFalse();
    }

    @Test
    void listsFiltersAndSuspendsTenant() {
        TenantResponse alpha = provisioningService.provision(request("alpha-helpdesk"));
        provisioningService.provision(request("beta-support"));

        TenantResponse suspended = managementService.updateStatus(
                alpha.id(),
                TenantStatus.SUSPENDED
        );

        assertThat(suspended.status()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(managementService.list(
                TenantStatus.SUSPENDED,
                "alpha",
                PageRequest.of(0, 10)
        ).getContent())
                .singleElement()
                .extracting(TenantResponse::id)
                .isEqualTo(alpha.id());
    }

    private void assertProvisioningRolledBack(
            CreateTenantRequest request,
            String schemaName
    ) {
        assertThatThrownBy(() -> provisioningService.provision(request))
                .isInstanceOfSatisfying(TenantException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo("TENANT_PROVISIONING_FAILED"));
        assertThat(tenantRepository.existsBySlug(request.slug())).isFalse();
        assertThat(schemaManager.exists(schemaName)).isFalse();
        assertThat(outboxRepository.findAll()).isEmpty();
    }

    private Future<ProvisioningAttempt> submitProvisioning(
            ExecutorService executor,
            CountDownLatch ready,
            CountDownLatch start,
            CreateTenantRequest request
    ) {
        return executor.submit(() -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent test did not start.");
            }

            try {
                return ProvisioningAttempt.success(
                        provisioningService.provision(request)
                );
            } catch (TenantException exception) {
                return ProvisioningAttempt.failure(exception.getCode());
            }
        });
    }

    private ProvisioningAttempt awaitAttempt(Future<ProvisioningAttempt> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent provisioning failed.", exception);
        }
    }

    private CreateTenantRequest request(String slug) {
        return new CreateTenantRequest(
                slug.replace('-', ' '),
                slug,
                slug + "-admin@example.com",
                "Company",
                "Administrator"
        );
    }

    private String schemaName(CreateTenantRequest request) {
        return TenantSchemaNameUtil.toSchemaName(
                TenantSchemaNameUtil.normalizeSlug(request.slug())
        );
    }

    private record ProvisioningAttempt(
            TenantResponse response,
            String errorCode
    ) {
        static ProvisioningAttempt success(TenantResponse response) {
            return new ProvisioningAttempt(response, null);
        }

        static ProvisioningAttempt failure(String errorCode) {
            return new ProvisioningAttempt(null, errorCode);
        }

        boolean succeeded() {
            return response != null;
        }
    }
}
