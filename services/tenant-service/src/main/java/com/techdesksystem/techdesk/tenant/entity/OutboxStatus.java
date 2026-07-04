package com.techdesksystem.techdesk.tenant.entity;

public enum OutboxStatus {
    PENDING,
    PROCESSING,
    FAILED,
    SENT
}
