package com.techdesksystem.techdesk.auth.tenant;

public interface TenantIsolationViolationReporter {

    void report(TenantIsolationViolation violation);
}
