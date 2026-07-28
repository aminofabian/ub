-- Staff profiles + monthly payroll MVP.
-- Payroll FKs point at staff_profiles (not users) so non-login workers can split later.

CREATE TABLE staff_profiles (
  id                        CHAR(36)      PRIMARY KEY,
  business_id               CHAR(36)      NOT NULL,
  user_id                   CHAR(36)      NOT NULL,
  display_name              VARCHAR(255)  NULL,
  title                     VARCHAR(128)  NULL,
  photo_url                 VARCHAR(500)  NULL,
  start_date                DATE          NULL,
  employment_status         VARCHAR(32)   NOT NULL DEFAULT 'active',
  phone                     VARCHAR(50)   NULL,
  address                   VARCHAR(500)  NULL,
  national_id               VARCHAR(64)   NULL,
  employee_code             VARCHAR(64)   NULL,
  emergency_contact_name    VARCHAR(255)  NULL,
  emergency_contact_phone   VARCHAR(50)   NULL,
  bank_details              JSON          NULL,
  notes                     TEXT          NULL,
  created_at                TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_staff_profiles_user (user_id),
  UNIQUE KEY uq_staff_profiles_business_user (business_id, user_id),
  CONSTRAINT fk_staff_profiles_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_staff_profiles_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_staff_profiles_business_status ON staff_profiles (business_id, employment_status);

CREATE TABLE salaries (
  id                  CHAR(36)      PRIMARY KEY,
  business_id         CHAR(36)      NOT NULL,
  staff_profile_id    CHAR(36)      NOT NULL,
  amount              DECIMAL(14,2) NOT NULL,
  effective_from      DATE          NOT NULL,
  created_by          CHAR(36)      NOT NULL,
  created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_salaries_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_salaries_profile FOREIGN KEY (staff_profile_id) REFERENCES staff_profiles (id),
  CONSTRAINT fk_salaries_created_by FOREIGN KEY (created_by) REFERENCES users (id)
);

CREATE INDEX idx_salaries_profile_effective ON salaries (staff_profile_id, effective_from);

CREATE TABLE payslips (
  id                    CHAR(36)      PRIMARY KEY,
  business_id           CHAR(36)      NOT NULL,
  staff_profile_id      CHAR(36)      NOT NULL,
  period_year           SMALLINT      NOT NULL,
  period_month          TINYINT       NOT NULL,
  base_salary           DECIMAL(14,2) NOT NULL,
  advances_deducted     DECIMAL(14,2) NOT NULL DEFAULT 0,
  other_deductions      DECIMAL(14,2) NOT NULL DEFAULT 0,
  net_paid              DECIMAL(14,2) NOT NULL,
  paid_at               TIMESTAMP     NOT NULL,
  note                  VARCHAR(500)  NULL,
  expense_id            CHAR(36)      NULL,
  created_by            CHAR(36)      NOT NULL,
  created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_payslips_profile_period (staff_profile_id, period_year, period_month),
  CONSTRAINT fk_payslips_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_payslips_profile FOREIGN KEY (staff_profile_id) REFERENCES staff_profiles (id),
  CONSTRAINT fk_payslips_created_by FOREIGN KEY (created_by) REFERENCES users (id)
);

CREATE INDEX idx_payslips_business_period ON payslips (business_id, period_year, period_month);

CREATE TABLE salary_advances (
  id                    CHAR(36)      PRIMARY KEY,
  business_id           CHAR(36)      NOT NULL,
  staff_profile_id      CHAR(36)      NOT NULL,
  amount                DECIMAL(14,2) NOT NULL,
  advanced_on           DATE          NOT NULL,
  note                  VARCHAR(500)  NULL,
  status                VARCHAR(32)   NOT NULL DEFAULT 'outstanding',
  repaid_in_payslip_id  CHAR(36)      NULL,
  created_by            CHAR(36)      NOT NULL,
  created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_salary_advances_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_salary_advances_profile FOREIGN KEY (staff_profile_id) REFERENCES staff_profiles (id),
  CONSTRAINT fk_salary_advances_payslip FOREIGN KEY (repaid_in_payslip_id) REFERENCES payslips (id),
  CONSTRAINT fk_salary_advances_created_by FOREIGN KEY (created_by) REFERENCES users (id)
);

CREATE INDEX idx_salary_advances_profile_status ON salary_advances (staff_profile_id, status);
CREATE INDEX idx_salary_advances_business ON salary_advances (business_id, status);

-- Permissions (block 600+ to avoid collisions with marketplace/catalog ids).
INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000600', 'staff.profile.read',
   'View public staff profile fields (name, title, photo, status, branch).'),
  ('11111111-0000-0000-0000-000000000601', 'staff.hr.read',
   'View private HR fields (ID, address, bank details, notes).'),
  ('11111111-0000-0000-0000-000000000602', 'staff.hr.update',
   'Edit public and private staff profile fields.'),
  ('11111111-0000-0000-0000-000000000603', 'payroll.view',
   'View salaries, advances, and payslip history.'),
  ('11111111-0000-0000-0000-000000000604', 'payroll.manage',
   'Set salary history and log salary advances.'),
  ('11111111-0000-0000-0000-000000000605', 'payroll.run',
   'Run monthly payroll and mark payslips paid.');

-- Public profiles: all working roles except buyer.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE p.permission_key = 'staff.profile.read'
  AND r.business_id IS NULL
  AND r.role_key IN (
    'owner', 'admin', 'manager', 'cashier', 'viewer',
    'stock_manager', 'grocery_clerk', 'butcher_cashier'
  )
  AND NOT EXISTS (
    SELECT 1 FROM role_permissions rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- Private HR + payroll: owner / admin / manager only.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE p.permission_key IN (
    'staff.hr.read', 'staff.hr.update',
    'payroll.view', 'payroll.manage', 'payroll.run'
  )
  AND r.business_id IS NULL
  AND r.role_key IN ('owner', 'admin', 'manager')
  AND NOT EXISTS (
    SELECT 1 FROM role_permissions rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
