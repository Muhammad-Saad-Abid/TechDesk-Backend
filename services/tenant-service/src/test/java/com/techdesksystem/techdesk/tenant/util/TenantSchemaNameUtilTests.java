package com.techdesksystem.techdesk.tenant.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TenantSchemaNameUtilTests {

    @Test
    void normalizesHumanFriendlySlugIntoSafeSchemaName() {
        String slug = TenantSchemaNameUtil.normalizeSlug("  Ácme Pakistan Ltd  ");

        assertThat(slug).isEqualTo("acme-pakistan-ltd");
        assertThat(TenantSchemaNameUtil.toSchemaName(slug))
                .isEqualTo("tenant_acme_pakistan_ltd");
    }

    @Test
    void longSlugsRemainDeterministicAndWithinPostgresLimit() {
        String slug = TenantSchemaNameUtil.normalizeSlug(
                "a-company-name-that-is-intentionally-very-long-for-schema-generation-testing"
        );
        String first = TenantSchemaNameUtil.toSchemaName(slug);
        String second = TenantSchemaNameUtil.toSchemaName(slug);

        assertThat(first).isEqualTo(second).hasSizeLessThanOrEqualTo(63);
        assertThat(first).matches("^tenant_[a-z][a-z0-9_]+$");
    }

    @Test
    void rejectsSlugThatCannotStartWithALetter() {
        assertThatThrownBy(() ->
                TenantSchemaNameUtil.normalizeSlug("123 Incorporated")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("start with a letter");
    }
}
