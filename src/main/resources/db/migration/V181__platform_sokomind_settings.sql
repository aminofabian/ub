-- SokoMind platform settings (singleton): Guide / Brain / Eye kill switches + LLM provider env.

CREATE TABLE platform_sokomind_settings (
    id                              CHAR(36)     NOT NULL PRIMARY KEY,

    -- Master + face toggles
    sokomind_enabled                TINYINT(1)   NOT NULL DEFAULT 0,
    guide_enabled                   TINYINT(1)   NOT NULL DEFAULT 1,
    brain_enabled                   TINYINT(1)   NOT NULL DEFAULT 0,
    eye_enabled                     TINYINT(1)   NOT NULL DEFAULT 0,

    -- Provider: openai | anthropic | deepseek | rapidapi_deepseek
    primary_provider                VARCHAR(32)  NOT NULL DEFAULT 'openai',
    default_locale                  VARCHAR(16)  NOT NULL DEFAULT 'en-KE',

    -- OpenAI
    openai_api_key_enc              TEXT         NULL,
    openai_base_url                 VARCHAR(512) NULL,
    openai_mini_model               VARCHAR(128) NULL,
    openai_smart_model              VARCHAR(128) NULL,
    openai_vision_model             VARCHAR(128) NULL,

    -- Anthropic
    anthropic_api_key_enc           TEXT         NULL,
    anthropic_base_url              VARCHAR(512) NULL,
    anthropic_mini_model            VARCHAR(128) NULL,
    anthropic_smart_model           VARCHAR(128) NULL,

    -- DeepSeek (direct or RapidAPI-style host)
    deepseek_api_key_enc            TEXT         NULL,
    deepseek_base_url               VARCHAR(512) NULL,
    deepseek_host                   VARCHAR(255) NULL,
    deepseek_model                  VARCHAR(128) NULL,

    -- Guardrails
    industry_compare_enabled        TINYINT(1)   NOT NULL DEFAULT 0,
    industry_compare_min_twins      INT          NOT NULL DEFAULT 8,
    daily_token_budget_per_tenant   INT          NULL,
    max_tool_calls_per_request      INT          NOT NULL DEFAULT 8,
    system_prompt_extra             TEXT         NULL,

    updated_at                      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6)
);

INSERT INTO platform_sokomind_settings (id) VALUES ('00000000-0000-0000-0000-000000000001');
