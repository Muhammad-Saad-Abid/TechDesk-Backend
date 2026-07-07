ALTER TABLE auth_users
    DROP CONSTRAINT IF EXISTS chk_auth_users_role;

ALTER TABLE auth_users
    ALTER COLUMN first_name DROP NOT NULL,
    ALTER COLUMN last_name DROP NOT NULL,
    ALTER COLUMN role TYPE VARCHAR(80),
    ADD CONSTRAINT chk_auth_users_role CHECK (
        role = upper(role)
        AND role ~ '^[A-Z][A-Z0-9_]{2,79}$'
    );

ALTER TABLE auth_users
    ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN department_id BIGINT;

UPDATE auth_users
SET status = CASE
    WHEN enabled = TRUE THEN 'ACTIVE'
    ELSE 'INVITED'
END;

ALTER TABLE auth_users
    ADD CONSTRAINT chk_auth_users_status CHECK (
        status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'DISABLED')
    );

CREATE TABLE departments (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    code VARCHAR(40) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_departments_code UNIQUE (code),
    CONSTRAINT chk_departments_code
        CHECK (code = lower(code) AND code ~ '^[a-z][a-z0-9_]{0,39}$')
);

ALTER TABLE auth_users
    ADD CONSTRAINT fk_auth_users_department
        FOREIGN KEY (department_id)
        REFERENCES departments(id)
        ON DELETE SET NULL;

CREATE INDEX idx_auth_users_department_id
    ON auth_users(department_id);

CREATE INDEX idx_auth_users_status
    ON auth_users(status);

CREATE TABLE roles (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    description VARCHAR(255) NOT NULL,
    system_role BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_roles_name UNIQUE (name),
    CONSTRAINT chk_roles_name
        CHECK (name = upper(name) AND name ~ '^[A-Z][A-Z0-9_]{2,79}$')
);

CREATE TABLE permissions (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(100) NOT NULL,
    category VARCHAR(50) NOT NULL,
    description VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_permissions_code UNIQUE (code),
    CONSTRAINT chk_permissions_code
        CHECK (code = lower(code) AND code ~ '^[a-z][a-z0-9_:.-]{2,99}$')
);

CREATE TABLE role_permissions (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role
        FOREIGN KEY (role_id)
        REFERENCES roles(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_role_permissions_permission
        FOREIGN KEY (permission_id)
        REFERENCES permissions(id)
        ON DELETE CASCADE
);

CREATE INDEX idx_role_permissions_permission_id
    ON role_permissions(permission_id);

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    primary_role BOOLEAN NOT NULL DEFAULT FALSE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user
        FOREIGN KEY (user_id)
        REFERENCES auth_users(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role
        FOREIGN KEY (role_id)
        REFERENCES roles(id)
        ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uk_user_roles_one_primary
    ON user_roles(user_id)
    WHERE primary_role = TRUE;

CREATE INDEX idx_user_roles_role_id
    ON user_roles(role_id);

INSERT INTO roles (name, display_name, description, system_role)
VALUES
    ('SUPER_ADMIN', 'Super Admin', 'Platform owner role used for SaaS-level administration.', TRUE),
    ('COMPANY_ADMIN', 'Company Admin', 'Tenant administrator with user, department, role, and configuration access.', TRUE),
    ('IT_MANAGER', 'IT Manager', 'Tenant IT leader with ticket oversight, approvals, reports, and SLA access.', TRUE),
    ('IT_STAFF', 'IT Staff', 'Tenant support staff member who works assigned tickets and assets.', TRUE),
    ('EMPLOYEE', 'Employee', 'Tenant employee who creates tickets and gadget requests.', TRUE),
    ('AUDITOR', 'Auditor', 'Read-only compliance role for reports and audit trails.', TRUE);

INSERT INTO permissions (code, category, description)
VALUES
    ('users:create', 'users', 'Create tenant users.'),
    ('users:read', 'users', 'Read tenant users.'),
    ('users:update', 'users', 'Update tenant users.'),
    ('users:delete', 'users', 'Disable or delete tenant users.'),
    ('users:invite', 'users', 'Invite users to the tenant.'),
    ('users:permissions:read', 'users', 'Read flattened effective user permissions.'),
    ('departments:create', 'departments', 'Create departments.'),
    ('departments:read', 'departments', 'Read departments.'),
    ('departments:update', 'departments', 'Update departments.'),
    ('departments:delete', 'departments', 'Disable or delete departments.'),
    ('roles:create', 'roles', 'Create custom roles.'),
    ('roles:read', 'roles', 'Read roles.'),
    ('roles:update', 'roles', 'Update roles.'),
    ('roles:delete', 'roles', 'Delete non-system roles.'),
    ('roles:assign', 'roles', 'Assign roles and permissions to users.'),
    ('tickets:create', 'tickets', 'Create support tickets.'),
    ('tickets:read:own', 'tickets', 'Read tickets created by the current user.'),
    ('tickets:read:assigned', 'tickets', 'Read tickets assigned to the current staff member.'),
    ('tickets:read:all', 'tickets', 'Read all tenant tickets.'),
    ('tickets:update', 'tickets', 'Update ticket details.'),
    ('tickets:status:update', 'tickets', 'Change ticket status.'),
    ('tickets:assign', 'tickets', 'Assign or reassign tickets.'),
    ('tickets:comment', 'tickets', 'Add ticket comments.'),
    ('assets:read', 'assets', 'Read tenant assets.'),
    ('assets:manage', 'assets', 'Create, update, assign, return, and retire assets.'),
    ('gadget-requests:create', 'gadget_requests', 'Create gadget requests.'),
    ('gadget-requests:read:own', 'gadget_requests', 'Read own gadget requests.'),
    ('gadget-requests:read:all', 'gadget_requests', 'Read all tenant gadget requests.'),
    ('gadget-requests:approve', 'gadget_requests', 'Approve or reject gadget requests.'),
    ('reports:read', 'reports', 'Read tenant reports.'),
    ('audit-logs:read', 'audit_logs', 'Read tenant audit logs.'),
    ('notifications:read', 'notifications', 'Read own notifications.');

INSERT INTO role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM roles role
CROSS JOIN permissions permission
WHERE role.name = 'SUPER_ADMIN';

INSERT INTO role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM roles role
JOIN permissions permission ON permission.code IN (
    'users:create',
    'users:read',
    'users:update',
    'users:delete',
    'users:invite',
    'users:permissions:read',
    'departments:create',
    'departments:read',
    'departments:update',
    'departments:delete',
    'roles:create',
    'roles:read',
    'roles:update',
    'roles:delete',
    'roles:assign',
    'tickets:read:all',
    'tickets:update',
    'tickets:status:update',
    'tickets:assign',
    'tickets:comment',
    'assets:read',
    'assets:manage',
    'gadget-requests:read:all',
    'gadget-requests:approve',
    'reports:read',
    'audit-logs:read',
    'notifications:read'
)
WHERE role.name = 'COMPANY_ADMIN';

INSERT INTO role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM roles role
JOIN permissions permission ON permission.code IN (
    'users:read',
    'users:permissions:read',
    'departments:read',
    'roles:read',
    'tickets:read:all',
    'tickets:update',
    'tickets:status:update',
    'tickets:assign',
    'tickets:comment',
    'assets:read',
    'assets:manage',
    'gadget-requests:read:all',
    'gadget-requests:approve',
    'reports:read',
    'audit-logs:read',
    'notifications:read'
)
WHERE role.name = 'IT_MANAGER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM roles role
JOIN permissions permission ON permission.code IN (
    'tickets:read:assigned',
    'tickets:update',
    'tickets:status:update',
    'tickets:comment',
    'assets:read',
    'notifications:read'
)
WHERE role.name = 'IT_STAFF';

INSERT INTO role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM roles role
JOIN permissions permission ON permission.code IN (
    'tickets:create',
    'tickets:read:own',
    'tickets:comment',
    'gadget-requests:create',
    'gadget-requests:read:own',
    'notifications:read'
)
WHERE role.name = 'EMPLOYEE';

INSERT INTO role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM roles role
JOIN permissions permission ON permission.code IN (
    'users:read',
    'users:permissions:read',
    'departments:read',
    'roles:read',
    'tickets:read:all',
    'assets:read',
    'gadget-requests:read:all',
    'reports:read',
    'audit-logs:read',
    'notifications:read'
)
WHERE role.name = 'AUDITOR';

CREATE OR REPLACE FUNCTION sync_auth_user_primary_role()
RETURNS TRIGGER AS $rbac$
DECLARE
    resolved_role_id BIGINT;
BEGIN
    EXECUTE format(
        'SELECT id FROM %I.roles WHERE name = $1',
        TG_TABLE_SCHEMA
    )
    INTO resolved_role_id
    USING NEW.role;

    IF resolved_role_id IS NULL THEN
        RAISE EXCEPTION 'Unknown RBAC role: %', NEW.role;
    END IF;

    EXECUTE format(
        'DELETE FROM %I.user_roles
         WHERE user_id = $1
           AND primary_role = TRUE
           AND role_id <> $2',
        TG_TABLE_SCHEMA
    )
    USING NEW.id, resolved_role_id;

    EXECUTE format(
        'INSERT INTO %I.user_roles (user_id, role_id, primary_role)
         VALUES ($1, $2, TRUE)
         ON CONFLICT (user_id, role_id)
         DO UPDATE SET primary_role = TRUE',
        TG_TABLE_SCHEMA
    )
    USING NEW.id, resolved_role_id;

    RETURN NEW;
END;
$rbac$ LANGUAGE plpgsql;

CREATE TRIGGER trg_auth_users_sync_primary_role
AFTER INSERT OR UPDATE OF role ON auth_users
FOR EACH ROW
EXECUTE FUNCTION sync_auth_user_primary_role();

INSERT INTO user_roles (user_id, role_id, primary_role)
SELECT user_account.id, role.id, TRUE
FROM auth_users user_account
JOIN roles role ON role.name = user_account.role
ON CONFLICT (user_id, role_id)
DO UPDATE SET primary_role = TRUE;
