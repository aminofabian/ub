-- Platform-wide Supplier Portal settings (singleton) + issued claim invites (Path C).

CREATE TABLE platform_supplier_portal_settings (
    id                                  CHAR(36)     NOT NULL PRIMARY KEY,
    portal_enabled                      TINYINT(1)   NOT NULL DEFAULT 1,
    allow_self_claim                    TINYINT(1)   NOT NULL DEFAULT 1,
    allow_profile_edits                 TINYINT(1)   NOT NULL DEFAULT 1,
    allow_payment_detail_edits          TINYINT(1)   NOT NULL DEFAULT 1,
    allow_product_edits                 TINYINT(1)   NOT NULL DEFAULT 1,
    require_store_approval_product_edits TINYINT(1)  NOT NULL DEFAULT 0,
    allow_invoice_downloads             TINYINT(1)   NOT NULL DEFAULT 1,
    allow_statement_downloads           TINYINT(1)   NOT NULL DEFAULT 1,
    portal_public_url                   VARCHAR(512) NOT NULL DEFAULT 'https://kiosk.ke/supplier-portal',
    claim_enabled                       TINYINT(1)   NOT NULL DEFAULT 1,
    claim_method                        VARCHAR(32)  NOT NULL DEFAULT 'phone_code',
    code_length                         INT          NOT NULL DEFAULT 6,
    code_expiry_minutes                 INT          NOT NULL DEFAULT 30,
    max_attempts                        INT          NOT NULL DEFAULT 5,
    lock_duration_minutes               INT          NOT NULL DEFAULT 15,
    resend_cooldown_seconds             INT          NOT NULL DEFAULT 60,
    auto_login_after_setup              TINYINT(1)   NOT NULL DEFAULT 1,
    password_min_length                 INT          NOT NULL DEFAULT 8,
    password_require_number             TINYINT(1)   NOT NULL DEFAULT 0,
    password_require_uppercase          TINYINT(1)   NOT NULL DEFAULT 0,
    password_require_special            TINYINT(1)   NOT NULL DEFAULT 0,
    invitation_message_template         TEXT         NULL,
    sms_template                        TEXT         NULL,
    email_subject_template              VARCHAR(255) NULL,
    email_body_template                 TEXT         NULL,
    support_phone                       VARCHAR(64)  NULL,
    support_email                       VARCHAR(191) NULL,
    updated_at                          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
);

INSERT INTO platform_supplier_portal_settings (
    id,
    invitation_message_template,
    sms_template,
    email_subject_template,
    email_body_template
) VALUES (
    '00000000-0000-0000-0000-000000000001',
    'Hello {{supplier_name}}

{{shop_name}} has invited you to manage your supplies using Palmart Supplier Portal.

Your verification code is:

{{claim_code}}

Open:

{{portal_url}}

Enter the code to activate your account.

Code expires in {{expiry_minutes}} minutes.',
    'Palmart Supplier Portal

Verification Code:

{{claim_code}}

Visit:

{{portal_url}}

Expires in {{expiry_minutes}} mins.',
    'Activate your Supplier Portal',
    'Hello {{supplier_name}},

Use code {{claim_code}} to activate your Supplier Portal account.

Open {{portal_url}}

Code expires in {{expiry_minutes}} minutes.'
);

CREATE TABLE supplier_portal_claim_invites (
    id                      CHAR(36)     NOT NULL PRIMARY KEY,
    marketplace_supplier_id CHAR(36)     NOT NULL,
    code_hash               VARCHAR(64)  NOT NULL,
    phone                   VARCHAR(32)  NULL,
    expires_at              TIMESTAMP    NOT NULL,
    attempts                INT          NOT NULL DEFAULT 0,
    max_attempts            INT          NOT NULL DEFAULT 5,
    locked_until            TIMESTAMP    NULL,
    consumed_at             TIMESTAMP    NULL,
    verified_at             TIMESTAMP    NULL,
    setup_token_hash        VARCHAR(64)  NULL,
    setup_token_expires_at  TIMESTAMP    NULL,
    created_by_actor_id     CHAR(36)     NULL,
    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_sent_at            TIMESTAMP    NULL,
    INDEX idx_supplier_portal_claim_invites_code (code_hash),
    INDEX idx_supplier_portal_claim_invites_supplier (marketplace_supplier_id),
    INDEX idx_supplier_portal_claim_invites_setup (setup_token_hash),
    CONSTRAINT fk_sp_claim_invites_marketplace
        FOREIGN KEY (marketplace_supplier_id) REFERENCES marketplace_suppliers (id)
);

ALTER TABLE supplier_phone_verifications
    ADD COLUMN locked_until TIMESTAMP NULL AFTER max_attempts;
