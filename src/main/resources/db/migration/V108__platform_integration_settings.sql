CREATE TABLE platform_integration_settings (
    id CHAR(36) NOT NULL PRIMARY KEY,
    deepseek_api_key_enc TEXT NULL,
    deepseek_host VARCHAR(255) NULL,
    deepseek_url VARCHAR(512) NULL,
    deepseek_model VARCHAR(128) NULL,
    rapidapi_whatsapp_key_enc TEXT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
);

INSERT INTO platform_integration_settings (id)
VALUES ('00000000-0000-0000-0000-000000000001');
