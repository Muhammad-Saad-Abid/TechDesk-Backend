package com.techdesksystem.techdesk.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

import java.util.List;

@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false"
})
class GatewayRouteConfigurationTests {

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @Test
    void exposesExplicitPublicApiRoutesWithoutDiscoveryPrefixRoutes() {
        List<RouteDefinition> routes = routeDefinitionLocator
                .getRouteDefinitions()
                .collectList()
                .block();

        assertThat(routes).isNotNull();
        assertThat(routes)
                .extracting(RouteDefinition::getId)
                .contains("auth-service-api", "tenant-service-api")
                .doesNotContain("ReactiveCompositeDiscoveryClient_AUTH-SERVICE");

        RouteDefinition authRoute = routeById(routes, "auth-service-api");
        assertThat(authRoute.getUri()).hasToString("lb://auth-service");
        assertThat(authRoute.getPredicates())
                .anySatisfy(predicate -> assertThat(predicate.toString())
                        .contains("/api/auth/**")
                        .contains("/api/users/**")
                        .contains("/api/departments/**")
                        .contains("/api/roles/**")
                        .contains("/api/permissions"));

        RouteDefinition tenantRoute = routeById(routes, "tenant-service-api");
        assertThat(tenantRoute.getUri()).hasToString("lb://tenant-service");
        assertThat(tenantRoute.getPredicates())
                .anySatisfy(predicate -> assertThat(predicate.toString())
                        .contains("/api/tenants/**"));
    }

    private RouteDefinition routeById(
            List<RouteDefinition> routes,
            String id
    ) {
        return routes.stream()
                .filter(route -> id.equals(route.getId()))
                .findFirst()
                .orElseThrow();
    }
}
