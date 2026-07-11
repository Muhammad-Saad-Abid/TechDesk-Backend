DO $$
DECLARE
    tenant_schema TEXT := current_schema();
BEGIN
    EXECUTE format(
        'ALTER FUNCTION %I.techdesk_rbac_is_system() SET search_path TO %I, pg_catalog',
        tenant_schema,
        tenant_schema
    );
    EXECUTE format(
        'ALTER FUNCTION %I.techdesk_rbac_current_user_id() SET search_path TO %I, pg_catalog',
        tenant_schema,
        tenant_schema
    );
    EXECUTE format(
        'ALTER FUNCTION %I.techdesk_rbac_is_authenticated() SET search_path TO %I, pg_catalog',
        tenant_schema,
        tenant_schema
    );
    EXECUTE format(
        'ALTER FUNCTION %I.techdesk_rbac_has_permission(TEXT) SET search_path TO %I, pg_catalog',
        tenant_schema,
        tenant_schema
    );

    EXECUTE format(
        'REVOKE ALL ON FUNCTION %I.techdesk_rbac_is_system() FROM PUBLIC',
        tenant_schema
    );
    EXECUTE format(
        'REVOKE ALL ON FUNCTION %I.techdesk_rbac_current_user_id() FROM PUBLIC',
        tenant_schema
    );
    EXECUTE format(
        'REVOKE ALL ON FUNCTION %I.techdesk_rbac_is_authenticated() FROM PUBLIC',
        tenant_schema
    );
    EXECUTE format(
        'REVOKE ALL ON FUNCTION %I.techdesk_rbac_has_permission(TEXT) FROM PUBLIC',
        tenant_schema
    );
END
$$;
