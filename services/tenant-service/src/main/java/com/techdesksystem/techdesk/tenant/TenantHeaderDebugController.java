package com.techdesksystem.techdesk.tenant;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal")
public class TenantHeaderDebugController {

    @GetMapping("/tenant-context")
    public Map<String, String> tenantContext(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestHeader(HttpHeaders.HOST) String host
    ) {
        return Map.of(
                "receivedTenantId", tenantId == null ? "NOT_RESOLVED" : tenantId,
                "host", host
        );
    }
}