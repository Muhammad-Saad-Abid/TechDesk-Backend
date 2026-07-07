package com.techdesksystem.techdesk.auth.repository;

import com.techdesksystem.techdesk.auth.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PermissionRepository
        extends JpaRepository<Permission, Long>,
        JpaSpecificationExecutor<Permission> {

    long countByCodeIn(Collection<String> codes);

    List<Permission> findByCodeIn(Collection<String> codes);

    @Query(
            value = """
                    SELECT DISTINCT permission.code
                    FROM user_roles user_role
                    JOIN role_permissions role_permission
                      ON role_permission.role_id = user_role.role_id
                    JOIN permissions permission
                      ON permission.id = role_permission.permission_id
                    WHERE user_role.user_id = :userId
                    ORDER BY permission.code
                    """,
            nativeQuery = true
    )
    List<String> findEffectivePermissionCodesByUserId(
            @Param("userId") Long userId
    );
}
