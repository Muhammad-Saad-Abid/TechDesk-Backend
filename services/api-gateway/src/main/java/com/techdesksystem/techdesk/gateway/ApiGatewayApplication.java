package com.techdesksystem.techdesk.gateway;

import com.techdesksystem.techdesk.gateway.tenant.TenantResolutionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableConfigurationProperties(TenantResolutionProperties.class)
@SpringBootApplication
public class ApiGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(ApiGatewayApplication.class, args);
	}

}
