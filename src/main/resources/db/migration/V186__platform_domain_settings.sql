-- Custom domain integrations (HostAfrica + Vercel) — Super Admin singleton settings.

CREATE TABLE platform_domain_settings (
    id                                  CHAR(36)     NOT NULL PRIMARY KEY,

    hostafrica_api_key_enc              TEXT         NULL,
    hostafrica_api_base_url             VARCHAR(512) NULL,
    hostafrica_currency                 VARCHAR(16)  NULL,
    hostafrica_kenyan_tlds              VARCHAR(255) NULL,
    hostafrica_billing_stub_enabled     TINYINT(1)   NOT NULL DEFAULT 1,

    vercel_token_enc                    TEXT         NULL,
    vercel_team_id                      VARCHAR(128) NULL,
    vercel_project_id                   VARCHAR(128) NULL,
    vercel_api_base_url                 VARCHAR(512) NULL,

    domain_order_sync_enabled           TINYINT(1)   NOT NULL DEFAULT 0,
    domain_order_sync_fixed_delay_ms    INT          NOT NULL DEFAULT 60000,
    domain_order_sync_initial_delay_ms  INT          NOT NULL DEFAULT 20000,

    updated_at                          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6)
);

INSERT INTO platform_domain_settings (id) VALUES ('00000000-0000-0000-0000-000000000001');
