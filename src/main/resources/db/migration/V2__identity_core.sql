-- Slice 2 — Identity primitives (PHASE_1_PLAN.md §2.1)
-- Tables: permissions, roles, role_permissions, users, super_admins.
-- MySQL path: tenant isolation enforced in application queries (no row-level security).

CREATE TABLE permissions (
  id             CHAR(36) PRIMARY KEY,
  permission_key VARCHAR(191) NOT NULL UNIQUE,
  description    VARCHAR(500) NOT NULL,
  created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE roles (
  id          CHAR(36) PRIMARY KEY,
  business_id CHAR(36) NULL,
  role_key    VARCHAR(191) NOT NULL,
  name        VARCHAR(255) NOT NULL,
  description VARCHAR(500) NULL,
  is_system   BOOLEAN NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by  CHAR(36) NULL,
  updated_by  CHAR(36) NULL,
  deleted_at  TIMESTAMP NULL,
  CONSTRAINT fk_roles_business FOREIGN KEY (business_id) REFERENCES businesses(id)
);

-- (business_id, role_key) is the desired uniqueness contract, but MySQL treats
-- multiple NULLs as distinct so two system roles can share a key. Use a
-- generated column to project NULL business_id to a sentinel string for the
-- system-role uniqueness branch only.
ALTER TABLE roles
  ADD COLUMN business_scope CHAR(36) GENERATED ALWAYS AS (
    COALESCE(business_id, '00000000-0000-0000-0000-000000000000')
  ) STORED;

CREATE UNIQUE INDEX uq_roles_scope_key ON roles (business_scope, role_key);

CREATE TABLE role_permissions (
  role_id       CHAR(36) NOT NULL,
  permission_id CHAR(36) NOT NULL,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (role_id, permission_id),
  CONSTRAINT fk_rp_role       FOREIGN KEY (role_id)       REFERENCES roles(id) ON DELETE CASCADE,
  CONSTRAINT fk_rp_permission FOREIGN KEY (permission_id) REFERENCES permissions(id)
);

CREATE TABLE users (
  id              CHAR(36) PRIMARY KEY,
  business_id     CHAR(36) NOT NULL,
  branch_id       CHAR(36) NULL,
  email           VARCHAR(191) NOT NULL,
  phone           VARCHAR(50) NULL,
  name            VARCHAR(255) NOT NULL,
  password_hash   VARCHAR(255) NULL,
  pin_hash        VARCHAR(255) NULL,
  status          VARCHAR(32) NOT NULL DEFAULT 'active',
  role_id         CHAR(36) NOT NULL,
  last_login_at   TIMESTAMP NULL,
  failed_attempts INT NOT NULL DEFAULT 0,
  locked_until    TIMESTAMP NULL,
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by      CHAR(36) NULL,
  updated_by      CHAR(36) NULL,
  deleted_at      TIMESTAMP NULL,
  UNIQUE KEY uq_users_business_email (business_id, email),
  CONSTRAINT fk_users_business FOREIGN KEY (business_id) REFERENCES businesses(id),
  CONSTRAINT fk_users_branch   FOREIGN KEY (branch_id)   REFERENCES branches(id),
  CONSTRAINT fk_users_role     FOREIGN KEY (role_id)     REFERENCES roles(id),
  CONSTRAINT chk_users_credentials CHECK (password_hash IS NOT NULL OR pin_hash IS NOT NULL)
);

CREATE INDEX idx_users_role     ON users (role_id);
CREATE INDEX idx_users_branch   ON users (branch_id);
CREATE INDEX idx_users_status   ON users (business_id, status);

CREATE TABLE super_admins (
  id              CHAR(36) PRIMARY KEY,
  email           VARCHAR(191) NOT NULL UNIQUE,
  name            VARCHAR(255) NOT NULL,
  password_hash   VARCHAR(255) NOT NULL,
  mfa_secret      VARCHAR(255) NULL,
  active          BOOLEAN NOT NULL DEFAULT TRUE,
  last_login_at   TIMESTAMP NULL,
  failed_attempts INT NOT NULL DEFAULT 0,
  locked_until    TIMESTAMP NULL,
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
