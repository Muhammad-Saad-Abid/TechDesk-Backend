package com.techdesksystem.techdesk.audit.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AuditSecurityPropertiesTests {

    private static final String THIRTY_TWO_BYTE_KEY =
            "0123456789abcdef0123456789abcdef";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class
            ))
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void acceptsInternalKeyContainingAtLeastThirtyTwoUtf8Bytes() {
        contextRunner
                .withPropertyValues("audit.internal-key=" + THIRTY_TWO_BYTE_KEY)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AuditSecurityProperties.class);
                    assertThat(context.getBean(AuditSecurityProperties.class).getInternalKey())
                            .isEqualTo(THIRTY_TWO_BYTE_KEY);
                });
    }

    @Test
    void acceptsMultibyteKeyBasedOnUtf8ByteLength() {
        String sixteenTwoByteCharacters = "\u00E9".repeat(16);

        contextRunner
                .withPropertyValues("audit.internal-key=" + sixteenTwoByteCharacters)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsBlankInternalKeyDuringConfigurationBinding() {
        contextRunner
                .withPropertyValues("audit.internal-key=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class)
                            .hasStackTraceContaining(
                                    "audit.internal-key must not be blank"
                            );
                });
    }

    @Test
    void rejectsInternalKeyShorterThanThirtyTwoUtf8BytesDuringConfigurationBinding() {
        contextRunner
                .withPropertyValues("audit.internal-key=0123456789abcdef0123456789abcde")
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
    @EnableConfigurationProperties(AuditSecurityProperties.class)
    static class TestConfiguration {
    }
}
