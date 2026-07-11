CREATE OR REPLACE FUNCTION techdesk_rbac_is_system()
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
AS $$
    SELECT COALESCE(current_setting('techdesk.rbac.system', TRUE), '') = 'true'
$$;

CREATE OR REPLACE FUNCTION techdesk_rbac_current_user_id()
RETURNS BIGINT
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    raw_user_id TEXT;
BEGIN
    raw_user_id := NULLIF(
        current_setting('techdesk.rbac.user_id', TRUE),
        ''
    );

    IF raw_user_id IS NULL THEN
        RETURN NULL;
    END IF;

    RETURN raw_user_id::BIGINT;
EXCEPTION
    WHEN invalid_text_representation THEN
        RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION techdesk_rbac_is_authenticated()
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
AS $$
    SELECT techdesk_rbac_current_user_id() IS NOT NULL
$$;

CREATE OR REPLACE FUNCTION techdesk_rbac_has_permission(required_permission TEXT)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
AS $$
    SELECT techdesk_rbac_is_system()
        OR required_permission = ANY (
            string_to_array(
                COALESCE(
                    NULLIF(current_setting('techdesk.rbac.permissions', TRUE), ''),
                    ''
                ),
                ','
            )
        )
$$;

CREATE POLICY auth_users_select_rbac
    ON auth_users
    FOR SELECT
    USING (
        techdesk_rbac_is_system()
        OR techdesk_rbac_has_permission('users:create')
        OR techdesk_rbac_has_permission('users:invite')
        OR techdesk_rbac_has_permission('users:read')
        OR techdesk_rbac_has_permission('users:permissions:read')
        OR id = techdesk_rbac_current_user_id()
    );

CREATE POLICY auth_users_insert_rbac
    ON auth_users
    FOR INSERT
    WITH CHECK (
        techdesk_rbac_is_system()
        OR techdesk_rbac_has_permission('users:create')
        OR techdesk_rbac_has_permission('users:invite')
    );

CREATE POLICY auth_users_update_rbac
    ON auth_users
    FOR UPDATE
    USING (
        techdesk_rbac_is_system()
        OR techdesk_rbac_has_permission('users:update')
        OR id = techdesk_rbac_current_user_id()
    )
    WITH CHECK (
        techdesk_rbac_is_system()
        OR techdesk_rbac_has_permission('users:update')
        OR id = techdesk_rbac_current_user_id()
    );

CREATE POLICY auth_users_delete_rbac
    ON auth_users
    FOR DELETE
    USING (
        techdesk_rbac_is_system()
        OR techdesk_rbac_has_permission('users:delete')
    );

CREATE POLICY departments_select_rbac
    ON departments
    FOR SELECT
    USING (
        techdesk_rbac_is_system()
        OR techdesk_rbac_is_authenticated()
        OR techdesk_rbac_has_permission('departments:read')
    );

CREATE POLICY departments_insert_rbac
    ON departments
    FOR INSERT
    WITH CHECK (
        techdesk_rbac_is_system()
        OR techdesk_rbac_has_permission('departments:create')
    );

CREATE POLICY departments_update_rbac
    ON departments
    FOR UPDATE
    USING (
        techdesk_rbac_is_system()
        OR techdesk_rbac_has_permission('departments:update')
    )
    WITH CHECK (
        techdesk_rbac_is_system()
        OR techdesk_rbac_has_permission('departments:update')
    );

CREATE POLICY departments_delete_rbac
    ON departments
    FOR DELETE
    USING (
        techdesk_rbac_is_system()
        OR techdesk_rbac_has_permission('departments:delete')
    );

CREATE POLICY roles_select_rbac
    ON roles
    FOR SELECT
    USING (
        techdesk_rbac_is_system()
        OR techdesk_rbac_is_authenticated()
        OR techdesk_rbac_has_permission('roles:read')
    );

CREATE POLICY roles_insert_rbac
    ON roles
    FOR INSERT
    WITH CHECK (
        techdesk_rbac_is_system()
        OR techdesk_rbac_has_permission('roles:create')
    );

CREATE POLICY roles_update_rbac
    ON roles
    FOR UPDATE
    USING (
        techdesk_rbac_is_system()
        OR techdesk_rbac_has_permission('roles:update')
    )
    WITH CHECK (
        techdesk_rbac_is_system()
        OR techdesk_rbac_has_permission('roles:update')
    );

CREATE POLICY roles_delete_rbac
    ON roles
    FOR DELETE
    USING (
        techdesk_rbac_is_system()
        OR techdesk_rbac_has_permission('roles:delete')
    );

CREATE POLICY permissions_select_rbac
    ON permissions
    FOR SELECT
    USING (
        techdesk_rbac_is_system()
        OR techdesk_rbac_is_authenticated()
        OR techdesk_rbac_has_permission('roles:read')
    );

CREATE POLICY role_permissions_select_rbac
    ON role_permissions
    FOR SELECT
    USING (
        techdesk_rbac_is_system()
        OR techdesk_rbac_is_authenticated()
        OR techdesk_rbac_has_permission('roles:read')
    );

CREATE POLICY role_permissions_insert_rbac
    ON role_permissions
    FOR INSERT
    WITH CHECK (
        techdesk_rbac_is_system()
        OR techdesk_rbac_has_permission('roles:assign')
        OR techdesk_rbac_has_permission('roles:update')
        OR techdesk_rbac_has_permission('roles:create')
    );

CREATE POLICY role_permissions_delete_rbac
    ON role_permissions
    FOR DELETE
    USING (
        techdesk_rbac_is_system()
        OR techdesk_rbac_has_permission('roles:assign')
        OR techdesk_rbac_has_permission('roles:update')
    );

CREATE POLICY user_roles_select_rbac
    ON user_roles
    FOR SELECT
    USING (
        techdesk_rbac_is_system()
        OR techdesk_rbac_has_permission('users:create')
        OR techdesk_rbac_has_permission('users:invite')
        OR techdesk_rbac_has_permission('users:read')
        OR techdesk_rbac_has_permission('roles:assign')
        OR techdesk_rbac_has_permission('roles:read')
        OR user_id = techdesk_rbac_current_user_id()
    );

CREATE POLICY user_roles_insert_rbac
    ON user_roles
    FOR INSERT
    WITH CHECK (
        techdesk_rbac_is_system()
        OR techdesk_rbac_has_permission('roles:assign')
        OR techdesk_rbac_has_permission('users:create')
        OR techdesk_rbac_has_permission('users:invite')
    );

CREATE POLICY user_roles_update_rbac
    ON user_roles
    FOR UPDATE
    USING (
        techdesk_rbac_is_system()
        OR techdesk_rbac_has_permission('roles:assign')
        OR techdesk_rbac_has_permission('users:create')
        OR techdesk_rbac_has_permission('users:invite')
    )
    WITH CHECK (
        techdesk_rbac_is_system()
        OR techdesk_rbac_has_permission('roles:assign')
        OR techdesk_rbac_has_permission('users:create')
        OR techdesk_rbac_has_permission('users:invite')
    );

CREATE POLICY user_roles_delete_rbac
    ON user_roles
    FOR DELETE
    USING (
        techdesk_rbac_is_system()
        OR techdesk_rbac_has_permission('roles:assign')
        OR techdesk_rbac_has_permission('users:create')
        OR techdesk_rbac_has_permission('users:invite')
    );

ALTER TABLE auth_users ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth_users FORCE ROW LEVEL SECURITY;

ALTER TABLE departments ENABLE ROW LEVEL SECURITY;
ALTER TABLE departments FORCE ROW LEVEL SECURITY;

ALTER TABLE roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE roles FORCE ROW LEVEL SECURITY;

ALTER TABLE permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE permissions FORCE ROW LEVEL SECURITY;

ALTER TABLE role_permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE role_permissions FORCE ROW LEVEL SECURITY;

ALTER TABLE user_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_roles FORCE ROW LEVEL SECURITY;
