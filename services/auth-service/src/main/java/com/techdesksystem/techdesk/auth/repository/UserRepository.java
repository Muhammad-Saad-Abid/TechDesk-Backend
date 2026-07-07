package com.techdesksystem.techdesk.auth.repository;

import com.techdesksystem.techdesk.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository
        extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query(
            value = """
                    SELECT ur.user_id AS userId, role.name AS roleName
                    FROM user_roles ur
                    JOIN roles role ON role.id = ur.role_id
                    WHERE ur.user_id IN (:userIds)
                    ORDER BY role.name
                    """,
            nativeQuery = true
    )
    List<UserRoleNameView> findRoleNamesByUserIds(
            @Param("userIds") Collection<Long> userIds
    );

    @Modifying
    @Query(
            value = "DELETE FROM user_roles WHERE user_id = :userId",
            nativeQuery = true
    )
    void deleteRoleAssignments(@Param("userId") Long userId);

    @Modifying
    @Query(
            value = """
                    INSERT INTO user_roles (user_id, role_id, primary_role)
                    VALUES (:userId, :roleId, :primaryRole)
                    ON CONFLICT (user_id, role_id)
                    DO UPDATE SET primary_role = EXCLUDED.primary_role
                    """,
            nativeQuery = true
    )
    void grantRole(
            @Param("userId") Long userId,
            @Param("roleId") Long roleId,
            @Param("primaryRole") boolean primaryRole
    );

    interface UserRoleNameView {
        Long getUserId();

        String getRoleName();
    }
}
