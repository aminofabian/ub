-- Per-advance repayment arrangement: full balance, % of original per pay, fixed per pay, or manual.

ALTER TABLE salary_advances
    ADD COLUMN repayment_mode VARCHAR(32) NOT NULL DEFAULT 'full_balance'
        COMMENT 'full_balance | percent_of_original | fixed_per_pay | manual',
    ADD COLUMN repayment_value DECIMAL(14, 2) NULL
        COMMENT 'Percent 0-100 or fixed KES per pay, depending on mode';
