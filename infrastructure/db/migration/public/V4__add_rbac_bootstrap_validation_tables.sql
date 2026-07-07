ALTER TABLE public.auth_users
    ADD COLUMN IF NOT EXISTS status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS department_id BIGINT;

ALTER TABLE public.auth_users
    ALTER COLUMN role TYPE VARCHAR(80),
    ALTER COLUMN role SET DEFAULT 'EMPLOYEE';

UPDATE public.auth_users
SET role = 'EMPLOYEE'
WHERE role = 'USER';

UPDATE public.auth_users
SET status = CASE
    WHEN enabled = TRUE THEN 'ACTIVE'
    ELSE 'INVITED'
END
WHERE status IS NULL;

CREATE TABLE IF NOT EXISTS public.departments (
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

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_auth_users_role'
          AND conrelid = 'public.auth_users'::regclass
    ) THEN
        ALTER TABLE public.auth_users
            ADD CONSTRAINT chk_auth_users_role CHECK (
                role = upper(role)
                AND role ~ '^[A-Z][A-Z0-9_]{2,79}$'
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_auth_users_status'
          AND conrelid = 'public.auth_users'::regclass
    ) THEN
        ALTER TABLE public.auth_users
            ADD CONSTRAINT chk_auth_users_status CHECK (
                status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'DISABLED')
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_auth_users_department'
          AND conrelid = 'public.auth_users'::regclass
    ) THEN
        ALTER TABLE public.auth_users
            ADD CONSTRAINT fk_auth_users_department
                FOREIGN KEY (department_id)
                REFERENCES public.departments(id)
                ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_auth_users_department_id
    ON public.auth_users(department_id);

CREATE INDEX IF NOT EXISTS idx_auth_users_status
    ON public.auth_users(status);

CREATE TABLE IF NOT EXISTS public.roles (
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

CREATE TABLE IF NOT EXISTS public.permissions (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(100) NOT NULL,
    category VARCHAR(50) NOT NULL,
    description VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_permissions_code UNIQUE (code),
    CONSTRAINT chk_permissions_code
        CHECK (code = lower(code) AND code ~ '^[a-z][a-z0-9_:.-]{2,99}$')
);

CREATE TABLE IF NOT EXISTS public.role_permissions (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role
        FOREIGN KEY (role_id)
        REFERENCES public.roles(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_role_permissions_permission
        FOREIGN KEY (permission_id)
        REFERENCES public.permissions(id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_role_permissions_permission_id
    ON public.role_permissions(permission_id);

CREATE TABLE IF NOT EXISTS public.user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    primary_role BOOLEAN NOT NULL DEFAULT FALSE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user
        FOREIGN KEY (user_id)
        REFERENCES public.auth_users(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role
        FOREIGN KEY (role_id)
        REFERENCES public.roles(id)
        ON DELETE RESTRICT
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_roles_one_primary
    ON public.user_roles(user_id)
    WHERE primary_role = TRUE;

CREATE INDEX IF NOT EXISTS idx_user_roles_role_id
    ON public.user_roles(role_id);

INSERT INTO public.roles (name, display_name, description, system_role)
VALUES
    ('SUPER_ADMIN', 'Super Admin', 'Platform owner role used for SaaS-level administration.', TRUE),
    ('COMPANY_ADMIN', 'Company Admin', 'Tenant administrator with user, department, role, and configuration access.', TRUE),
    ('IT_MANAGER', 'IT Manager', 'Tenant IT leader with ticket oversight, approvals, reports, and SLA access.', TRUE),
    ('IT_STAFF', 'IT Staff', 'Tenant support staff member who works assigned tickets and assets.', TRUE),
    ('EMPLOYEE', 'Employee', 'Tenant employee who creates tickets and gadget requests.', TRUE),
    ('AUDITOR', 'Auditor', 'Read-only compliance role for reports and audit trails.', TRUE)
ON CONFLICT (name) DO NOTHING;

INSERT INTO public.permissions (code, category, description)
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
    ('notifications:read', 'notifications', 'Read own notifications.')
ON CONFLICT (code) DO NOTHING;

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM public.roles role
CROSS JOIN public.permissions permission
WHERE role.name = 'SUPER_ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM public.roles role
JOIN public.permissions permission ON permission.code IN (
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
WHERE role.name = 'COMPANY_ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM public.roles role
JOIN public.permissions permission ON permission.code IN (
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
WHERE role.name = 'IT_MANAGER'
ON CONFLICT DO NOTHING;

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM public.roles role
JOIN public.permissions permission ON permission.code IN (
    'tickets:read:assigned',
    'tickets:update',
    'tickets:status:update',
    'tickets:comment',
    'assets:read',
    'notifications:read'
)
WHERE role.name = 'IT_STAFF'
ON CONFLICT DO NOTHING;

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM public.roles role
JOIN public.permissions permission ON permission.code IN (
    'tickets:create',
    'tickets:read:own',
    'tickets:comment',
    'gadget-requests:create',
    'gadget-requests:read:own',
    'notifications:read'
)
WHERE role.name = 'EMPLOYEE'
ON CONFLICT DO NOTHING;

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM public.roles role
JOIN public.permissions permission ON permission.code IN (
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
WHERE role.name = 'AUDITOR'
ON CONFLICT DO NOTHING;
