package com.techdesksystem.techdesk.auth.security;

import com.techdesksystem.techdesk.auth.exception.AuthException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class RequiresPermissionAspect {

    private final PermissionService permissionService;

    public RequiresPermissionAspect(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /**
     * Blocks execution unless the current tenant user has the declared
     * permission in the tenant-local RBAC tables.
     */
    @Around("@within(requiresPermission) || @annotation(requiresPermission)")
    public Object enforcePermission(
            ProceedingJoinPoint joinPoint,
            RequiresPermission requiresPermission
    ) throws Throwable {
        String permission = requiresPermission.value();

        if (!permissionService.currentUserHasPermission(permission)) {
            throw AuthException.forbidden(
                    "PERMISSION_DENIED",
                    "Missing required permission: " + permission
            );
        }

        return joinPoint.proceed();
    }
}
