package com.techdesksystem.techdesk.auth.tenant;

import java.util.Optional;

/**
 * Holds the trusted tenant for the current request thread. Always use
 * {@link #open(String)} with try-with-resources or clear it in a finally block.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Scope open(String tenantIdentifier) {
        String previous = CURRENT_TENANT.get();
        CURRENT_TENANT.set(tenantIdentifier);
        return () -> restore(previous);
    }

    public static Optional<String> currentTenant() {
        return Optional.ofNullable(CURRENT_TENANT.get());
    }

    public static String requireTenant() {
        return currentTenant().orElseThrow(() ->
                new TenantIsolationException(
                        "No trusted tenant is bound to the current request."
                )
        );
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }

    private static void restore(String previous) {
        if (previous == null) {
            CURRENT_TENANT.remove();
        } else {
            CURRENT_TENANT.set(previous);
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
