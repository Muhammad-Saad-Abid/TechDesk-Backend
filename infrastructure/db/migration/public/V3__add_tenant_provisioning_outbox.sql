ALTER TABLE public.tenants
    ADD COLUMN provisioning_status VARCHAR(20) NOT NULL DEFAULT 'READY';

ALTER TABLE public.tenants
    ADD CONSTRAINT chk_tenant_provisioning_status
        CHECK (provisioning_status IN ('PROVISIONING', 'READY'));

CREATE TABLE public.tenant_notification_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    recipient_email VARCHAR(255) NOT NULL,
    recipient_name VARCHAR(201) NOT NULL,
    tenant_name VARCHAR(150) NOT NULL,
    tenant_slug VARCHAR(80) NOT NULL,
    schema_name VARCHAR(63) NOT NULL,
    admin_user_id BIGINT NOT NULL,
    invitation_jti VARCHAR(64) NOT NULL,
    invitation_expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_until TIMESTAMPTZ,
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMPTZ,

    CONSTRAINT fk_tenant_notification_outbox_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES public.tenants(id)
        ON DELETE CASCADE,
    CONSTRAINT chk_tenant_notification_outbox_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'FAILED', 'SENT'))
);

CREATE INDEX idx_tenant_outbox_delivery
    ON public.tenant_notification_outbox(status, next_attempt_at);
