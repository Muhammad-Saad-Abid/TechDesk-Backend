package com.techdesksystem.techdesk.tenant.service;

import com.techdesksystem.techdesk.tenant.entity.ProvisioningStatus;
import com.techdesksystem.techdesk.tenant.entity.Tenant;
import com.techdesksystem.techdesk.tenant.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class TenantSchemaMigrationRunnerTests {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private TenantSchemaMigrator schemaMigrator;

    @Test
    void migratesEveryReadyTenantSchema() {
        given(tenantRepository.findAllByProvisioningStatus(
                ProvisioningStatus.READY
        )).willReturn(List.of(
                tenant("tenant_alpha"),
                tenant("tenant_bravo")
        ));

        runner().run(new DefaultApplicationArguments(new String[0]));

        InOrder order = inOrder(schemaMigrator);
        order.verify(schemaMigrator).migrate("tenant_alpha");
        order.verify(schemaMigrator).migrate("tenant_bravo");
        order.verifyNoMoreInteractions();
    }

    @Test
    void migrationFailureStopsStartupSweep() {
        given(tenantRepository.findAllByProvisioningStatus(
                ProvisioningStatus.READY
        )).willReturn(List.of(tenant("tenant_broken")));
        doThrow(new IllegalStateException("migration failed"))
                .when(schemaMigrator)
                .migrate("tenant_broken");

        assertThatThrownBy(() -> runner().run(
                new DefaultApplicationArguments(new String[0])
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("migration failed");
    }

    private TenantSchemaMigrationRunner runner() {
        return new TenantSchemaMigrationRunner(
                tenantRepository,
                schemaMigrator
        );
    }

    private Tenant tenant(String schemaName) {
        Tenant tenant = new Tenant();
        tenant.setSchemaName(schemaName);
        tenant.setProvisioningStatus(ProvisioningStatus.READY);
        return tenant;
    }
}
