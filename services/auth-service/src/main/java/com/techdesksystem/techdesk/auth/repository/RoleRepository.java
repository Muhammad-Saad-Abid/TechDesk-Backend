package com.techdesksystem.techdesk.auth.repository;

import com.techdesksystem.techdesk.auth.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RoleRepository
        extends JpaRepository<Role, Long>, JpaSpecificationExecutor<Role> {

    boolean existsByName(String name);

    Optional<Role> findByName(String name);

    @Query(
            value = """
                    SELECT p.code
                    FROM permissions p
                    JOIN role_permissions rp ON rp.permission_id = p.id
                    WHERE rp.role_id = :roleId
                    ORDER BY p.code
                    """,
            nativeQuery = true
    )
    List<String> findPermissionCodesByRoleId(@Param("roleId") Long roleId);

    @Query(
            value = "SELECT COUNT(*) FROM user_roles WHERE role_id = :roleId",
            nativeQuery = true
    )
    long countUserAssignments(@Param("roleId") Long roleId);

    @Modifying
    @Query(
            value = "DELETE FROM role_permissions WHERE role_id = :roleId",
            nativeQuery = true
    )
    void deletePermissionAssignments(@Param("roleId") Long roleId);

    @Modifying
    @Query(
            value = """
                    INSERT INTO role_permissions (role_id, permission_id)
                    SELECT :roleId, p.id
                    FROM permissions p
                    WHERE p.code = :permissionCode
                    ON CONFLICT DO NOTHING
                    """,
            nativeQuery = true
    )
    void grantPermission(
            @Param("roleId") Long roleId,
            @Param("permissionCode") String permissionCode
    );
}
