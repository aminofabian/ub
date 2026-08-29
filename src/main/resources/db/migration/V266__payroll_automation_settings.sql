-- Per-business payroll automation (auto pay or remind on schedule).

CREATE TABLE payroll_automation_settings (
    business_id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    enabled                  TINYINT(1)   NOT NULL DEFAULT 0,
    automation_mode          VARCHAR(32)  NOT NULL DEFAULT 'auto_pay'
        COMMENT 'auto_pay | remind',
    pay_day_of_month         INT          NOT NULL DEFAULT 28
        COMMENT '1-28; clamped to month length at runtime',
    auto_pay_times_json      VARCHAR(512) NULL
        COMMENT 'JSON array of HH:mm local times, e.g. ["09:00"]',
    auto_pay_last_run_slot   VARCHAR(32)  NULL
        COMMENT 'yyyy-MM-dd''T''HH:mm dedupe guard',
    apply_statutory          TINYINT(1)   NOT NULL DEFAULT 0,
    post_expense             TINYINT(1)   NOT NULL DEFAULT 1,
    payment_method           VARCHAR(32)  NOT NULL DEFAULT 'mpesa_manual',
    branch_id                VARCHAR(36)  NULL,
    created_at               TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at               TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);
