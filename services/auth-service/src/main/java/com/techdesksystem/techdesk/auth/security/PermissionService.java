package com.techdesksystem.techdesk.auth.security;

import com.techdesksystem.techdesk.auth.exception.AuthException;
import com.techdesksystem.techdesk.auth.repository.PermissionRepository;
import com.techdesksystem.techdesk.auth.tenant.TenantContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PermissionService {

    private final PermissionRepository permissionRepository;

    public PermissionService(PermissionRepository permissionRepository) {
        this.permissionRepository = permissionRepository;
    }

    /**
     * Checks the current authenticated user against tenant-local role and
     * permission assignments in the active tenant schema.
     */
    @Transactional(readOnly = true)
    public boolean currentUserHasPermission(String permissionCode) {
        TenantContext.requireTenant();
        return effectivePermissionsForCurrentUser().contains(permissionCode);
    }

    /**
     * Returns the current user's flattened effective permissions for the active
     * tenant.
     */
    @Transactional(readOnly = true)
    public List<String> effectivePermissionsForCurrentUser() {
        return effectivePermissionsForUser(currentUserId());
    }

    /**
     * Returns flattened effective permissions for a tenant-local user.
     */
    @Transactional(readOnly = true)
    public List<String> effectivePermissionsForUser(Long userId) {
        TenantContext.requireTenant();
        return permissionRepository.findEffectivePermissionCodesByUserId(userId);
    }

    /**
     * Extracts the authenticated user id from the verified access token.
     */
    public Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext()
                .getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw AuthException.unauthorized(
                    "AUTHENTICATION_REQUIRED",
                    "A valid access token is required."
            );
        }

        Object claim = jwt.getClaim("userId");
        if (claim instanceof Number number) {
            return number.longValue();
        }
        if (claim instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException exception) {
                throw AuthException.unauthorized(
                        "INVALID_TOKEN",
                        "The access token contains an invalid user id."
                );
            }
        }

        throw AuthException.unauthorized(
                "INVALID_TOKEN",
                "The access token does not contain a user id."
        );
    }
}
