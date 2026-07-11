package com.techdesksystem.techdesk.gateway.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class TenantResolutionPropertiesValidationTests {

    private static final String VALID_SECRET =
            "0123456789abcdef0123456789abcdef";

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(TestConfiguration.class);

    @Test
    void startsWhenJwtHmacSecretContainsAtLeastThirtyTwoUtf8Bytes() {
        contextRunner
                .withPropertyValues(
                        "tenant.resolution.jwt-hmac-secret=" + VALID_SECRET
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TenantResolutionProperties.class)
                            .jwtHmacSecret()).isEqualTo(VALID_SECRET);
                });
    }

    @Test
    void failsStartupWhenJwtHmacSecretIsBlank() {
        contextRunner
                .withPropertyValues("tenant.resolution.jwt-hmac-secret=   ")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class)
                            .hasStackTraceContaining("must not be blank");
                });
    }

    @Test
    void failsStartupWhenJwtHmacSecretContainsFewerThanThirtyTwoUtf8Bytes() {
        contextRunner
                .withPropertyValues(
                        "tenant.resolution.jwt-hmac-secret=too-short"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class)
                            .hasStackTraceContaining(
                                    "must contain at least 32 UTF-8 bytes"
                            );
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TenantResolutionProperties.class)
    static class TestConfiguration {
    }
}
