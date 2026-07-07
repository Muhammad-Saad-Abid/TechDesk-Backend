package com.techdesksystem.techdesk.auth.config;

import com.techdesksystem.techdesk.auth.tenant.TenantContextFilter;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableAspectJAutoProxy
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            TenantContextFilter tenantContextFilter
    ) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/auth/**",
                                "/actuator/health",
                                "/actuator/info"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> { }))
                .addFilterAfter(
                        tenantContextFilter,
                        BearerTokenAuthenticationFilter.class
                )
                .build();
    }

    @Bean
    public JwtDecoder jwtDecoder(JwtProperties jwtProperties) {
        byte[] secret = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
        return NimbusJwtDecoder.withSecretKey(
                        new SecretKeySpec(secret, "HmacSHA256")
                )
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    public FilterRegistrationBean<TenantContextFilter> tenantFilterRegistration(
            TenantContextFilter filter
    ) {
        FilterRegistrationBean<TenantContextFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
