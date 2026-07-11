\set ON_ERROR_STOP on

-- Load deployment inputs from the process environment so role passwords never
-- appear in the psql command line or Docker's configured command.
\getenv database_name PGDATABASE
\getenv admin_role PGUSER
\getenv auth_role AUTH_DB_USER
\getenv auth_password AUTH_DB_PASSWORD
\getenv tenant_role TENANT_DB_USER
\getenv tenant_password TENANT_DB_PASSWORD
\getenv audit_role AUDIT_DB_USER
\getenv audit_password AUDIT_DB_PASSWORD
\getenv provisioner_role PROVISIONER_DB_USER
\getenv provisioner_password PROVISIONER_DB_PASSWORD

SELECT COUNT(DISTINCT configured_role) = 5 AS roles_are_distinct
FROM (
    VALUES
        (:'admin_role'),
        (:'auth_role'),
        (:'tenant_role'),
        (:'audit_role'),
        (:'provisioner_role')
) AS configured_roles(configured_role)
\gset

\if :roles_are_distinct
\else
    \echo 'Database admin, service, and provisioner roles must all be distinct.'
    \quit 3
\endif

WITH configured_roles(role_name, role_password) AS (
    VALUES
        (:'auth_role', :'auth_password'),
        (:'tenant_role', :'tenant_password'),
        (:'audit_role', :'audit_password'),
        (:'provisioner_role', :'provisioner_password')
)
SELECT format(
    'CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD %L',
    role_name,
    role_password
)
FROM configured_roles
WHERE NOT EXISTS (
    SELECT 1 FROM pg_roles WHERE rolname = role_name
)
\gexec

WITH configured_roles(role_name, role_password) AS (
    VALUES
        (:'auth_role', :'auth_password'),
        (:'tenant_role', :'tenant_password'),
        (:'audit_role', :'audit_password'),
        (:'provisioner_role', :'provisioner_password')
)
SELECT format(
    'ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD %L',
    role_name,
    role_password
)
FROM configured_roles
\gexec

SELECT format(
    'REVOKE CONNECT, CREATE, TEMPORARY ON DATABASE %I FROM PUBLIC',
    :'database_name'
)
\gexec

WITH service_roles(role_name) AS (
    VALUES (:'auth_role'), (:'tenant_role'), (:'audit_role')
)
SELECT format(
    'GRANT CONNECT ON DATABASE %I TO %I',
    :'database_name',
    role_name
)
FROM service_roles
\gexec

SELECT format(
    'GRANT CONNECT, CREATE ON DATABASE %I TO %I',
    :'database_name',
    :'provisioner_role'
)
\gexec

REVOKE CREATE ON SCHEMA public FROM PUBLIC;

WITH service_roles(role_name) AS (
    VALUES (:'auth_role'), (:'tenant_role'), (:'audit_role')
)
SELECT format('REVOKE ALL ON SCHEMA public FROM %I', role_name)
FROM service_roles
\gexec

WITH service_roles(role_name) AS (
    VALUES (:'auth_role'), (:'tenant_role'), (:'audit_role')
)
SELECT format(
    'REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM %I',
    role_name
)
FROM service_roles
\gexec

WITH service_roles(role_name) AS (
    VALUES (:'auth_role'), (:'tenant_role'), (:'audit_role')
)
SELECT format(
    'REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM %I',
    role_name
)
FROM service_roles
\gexec

SELECT format('GRANT USAGE ON SCHEMA public TO %I', role_name)
FROM (VALUES (:'auth_role'), (:'tenant_role'), (:'audit_role'))
        AS service_roles(role_name)
\gexec

SELECT format('GRANT SELECT ON public.tenants TO %I', :'auth_role')
\gexec
SELECT format('GRANT INSERT ON public.audit_logs TO %I', :'auth_role')
\gexec
SELECT format(
    'GRANT USAGE, SELECT ON SEQUENCE public.audit_logs_id_seq TO %I',
    :'auth_role'
)
\gexec

SELECT format(
    'GRANT SELECT, INSERT, UPDATE, DELETE ON public.tenants, public.tenant_notification_outbox TO %I',
    :'tenant_role'
)
\gexec

SELECT format('GRANT SELECT ON public.tenants TO %I', :'audit_role')
\gexec
SELECT format('GRANT INSERT ON public.audit_logs TO %I', :'audit_role')
\gexec
SELECT format(
    'GRANT USAGE, SELECT ON SEQUENCE public.audit_logs_id_seq TO %I',
    :'audit_role'
)
\gexec

-- Older installations created tenant objects as the bootstrap admin. Transfer
-- them once so future tenant migrations do not need a superuser connection.
SELECT format(
    CASE object.relkind
        WHEN 'v' THEN 'ALTER VIEW %I.%I OWNER TO %I'
        WHEN 'm' THEN 'ALTER MATERIALIZED VIEW %I.%I OWNER TO %I'
        WHEN 'f' THEN 'ALTER FOREIGN TABLE %I.%I OWNER TO %I'
        ELSE 'ALTER TABLE %I.%I OWNER TO %I'
    END,
    namespace.nspname,
    object.relname,
    :'provisioner_role'
)
FROM pg_class object
JOIN pg_namespace namespace ON namespace.oid = object.relnamespace
WHERE namespace.nspname ~ '^tenant_[a-z][a-z0-9_]{0,55}$'
  AND object.relkind IN ('r', 'p', 'v', 'm', 'f')
ORDER BY namespace.nspname, object.relkind, object.relname
\gexec

SELECT format(
    'ALTER %s %I.%I(%s) OWNER TO %I',
    CASE routine.prokind WHEN 'p' THEN 'PROCEDURE' ELSE 'FUNCTION' END,
    namespace.nspname,
    routine.proname,
    pg_get_function_identity_arguments(routine.oid),
    :'provisioner_role'
)
FROM pg_proc routine
JOIN pg_namespace namespace ON namespace.oid = routine.pronamespace
WHERE namespace.nspname ~ '^tenant_[a-z][a-z0-9_]{0,55}$'
  AND routine.prokind IN ('f', 'p')
ORDER BY namespace.nspname, routine.proname
\gexec

SELECT format(
    'ALTER SCHEMA %I OWNER TO %I',
    namespace.nspname,
    :'provisioner_role'
)
FROM pg_namespace namespace
WHERE namespace.nspname ~ '^tenant_[a-z][a-z0-9_]{0,55}$'
ORDER BY namespace.nspname
\gexec

SELECT format(
    'ALTER DEFAULT PRIVILEGES FOR ROLE %I REVOKE USAGE ON SCHEMAS FROM %I',
    :'provisioner_role',
    :'auth_role'
)
\gexec
SELECT format(
    'ALTER DEFAULT PRIVILEGES FOR ROLE %I REVOKE SELECT, INSERT, UPDATE, DELETE ON TABLES FROM %I',
    :'provisioner_role',
    :'auth_role'
)
\gexec
SELECT format(
    'ALTER DEFAULT PRIVILEGES FOR ROLE %I REVOKE USAGE, SELECT ON SEQUENCES FROM %I',
    :'provisioner_role',
    :'auth_role'
)
\gexec
SELECT format(
    'ALTER DEFAULT PRIVILEGES FOR ROLE %I REVOKE EXECUTE ON FUNCTIONS FROM %I',
    :'provisioner_role',
    :'auth_role'
)
\gexec

SELECT format('GRANT USAGE ON SCHEMA %I TO %I', namespace.nspname, :'auth_role')
FROM pg_namespace namespace
WHERE namespace.nspname ~ '^tenant_[a-z][a-z0-9_]{0,55}$'
ORDER BY namespace.nspname
\gexec

SELECT format(
    'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO %I',
    namespace.nspname,
    :'auth_role'
)
FROM pg_namespace namespace
WHERE namespace.nspname ~ '^tenant_[a-z][a-z0-9_]{0,55}$'
ORDER BY namespace.nspname
\gexec

SELECT format(
    'REVOKE INSERT, UPDATE, DELETE ON %I.flyway_schema_history FROM %I',
    namespace.nspname,
    :'auth_role'
)
FROM pg_namespace namespace
WHERE namespace.nspname ~ '^tenant_[a-z][a-z0-9_]{0,55}$'
ORDER BY namespace.nspname
\gexec

SELECT format(
    'GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO %I',
    namespace.nspname,
    :'auth_role'
)
FROM pg_namespace namespace
WHERE namespace.nspname ~ '^tenant_[a-z][a-z0-9_]{0,55}$'
ORDER BY namespace.nspname
\gexec

SELECT format(
    'REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA %I FROM PUBLIC',
    namespace.nspname
)
FROM pg_namespace namespace
WHERE namespace.nspname ~ '^tenant_[a-z][a-z0-9_]{0,55}$'
ORDER BY namespace.nspname
\gexec

SELECT format(
    'GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA %I TO %I',
    namespace.nspname,
    :'auth_role'
)
FROM pg_namespace namespace
WHERE namespace.nspname ~ '^tenant_[a-z][a-z0-9_]{0,55}$'
ORDER BY namespace.nspname
\gexec
