USE studyagent;

CREATE TABLE billing_entitlement_fulfillments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    payment_key VARCHAR(191) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id VARCHAR(191) NOT NULL,
    source_event_id VARCHAR(191) NULL,
    recharge_order_id BIGINT NULL,
    purchase_type VARCHAR(32) NOT NULL,
    product_code VARCHAR(64) NOT NULL,
    payment_status VARCHAR(16) NOT NULL DEFAULT 'pending',
    fulfillment_status VARCHAR(16) NOT NULL DEFAULT 'not_required',
    payment_accepted_at DATETIME(6) NULL,
    fulfillment_started_at DATETIME(6) NULL,
    fulfilled_at DATETIME(6) NULL,
    last_error_code VARCHAR(64) NULL,
    last_error_message VARCHAR(2000) NULL,
    last_error_at DATETIME(6) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_billing_fulfillment_payment_key (payment_key),
    KEY idx_billing_fulfillment_source_event (source_event_id),
    KEY idx_billing_fulfillment_open (
        payment_status,
        fulfillment_status,
        payment_accepted_at,
        id
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
