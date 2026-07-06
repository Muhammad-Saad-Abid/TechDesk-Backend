package com.techdesksystem.techdesk.auth.config;

import com.techdesksystem.techdesk.auth.tenant.SchemaMultiTenantConnectionProvider;
import com.techdesksystem.techdesk.auth.tenant.SecurityContextTenantIdentifierResolver;
import com.techdesksystem.techdesk.auth.tenant.TenantSqlStatementInspector;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HibernateMultitenancyConfig {

    @Bean
    public HibernatePropertiesCustomizer hibernateMultitenancyCustomizer(
            AuthMultitenancyProperties properties,
            SchemaMultiTenantConnectionProvider connectionProvider,
            SecurityContextTenantIdentifierResolver tenantIdentifierResolver,
            TenantSqlStatementInspector statementInspector
    ) {
        return hibernateProperties -> {
            if (!properties.isEnabled()) {
                return;
            }
            hibernateProperties.put(
                    AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER,
                    connectionProvider
            );
            hibernateProperties.put(
                    AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER,
                    tenantIdentifierResolver
            );
            hibernateProperties.put(
                    AvailableSettings.STATEMENT_INSPECTOR,
                    statementInspector
            );
        };
    }
}
