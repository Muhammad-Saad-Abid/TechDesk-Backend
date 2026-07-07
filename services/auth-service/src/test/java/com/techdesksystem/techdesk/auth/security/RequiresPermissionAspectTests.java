package com.techdesksystem.techdesk.auth.security;

import com.techdesksystem.techdesk.auth.exception.AuthException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequiresPermissionAspectTests {

    @Test
    void proceedsWhenCurrentUserHasPermission() throws Throwable {
        PermissionService permissionService = mock(PermissionService.class);
        RequiresPermission annotation = mock(RequiresPermission.class);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        RequiresPermissionAspect aspect =
                new RequiresPermissionAspect(permissionService);

        when(annotation.value()).thenReturn("users:read");
        when(permissionService.currentUserHasPermission("users:read"))
                .thenReturn(true);
        when(joinPoint.proceed()).thenReturn("allowed");

        assertThat(aspect.enforcePermission(joinPoint, annotation))
                .isEqualTo("allowed");

        verify(joinPoint).proceed();
    }

    @Test
    void rejectsWhenCurrentUserLacksPermission() throws Throwable {
        PermissionService permissionService = mock(PermissionService.class);
        RequiresPermission annotation = mock(RequiresPermission.class);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        RequiresPermissionAspect aspect =
                new RequiresPermissionAspect(permissionService);

        when(annotation.value()).thenReturn("roles:delete");
        when(permissionService.currentUserHasPermission("roles:delete"))
                .thenReturn(false);

        assertThatThrownBy(() -> aspect.enforcePermission(joinPoint, annotation))
                .isInstanceOfSatisfying(AuthException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo("PERMISSION_DENIED"));

        verify(joinPoint, never()).proceed();
    }
}
