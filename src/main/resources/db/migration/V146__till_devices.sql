-- V146: Trusted till devices — register browsers/webviews for a branch.
-- Phase 3b: inventory + revoke. Unlock-must-be-registered policy is deferred.

CREATE TABLE till_devices (
    id              CHAR(36)      NOT NULL PRIMARY KEY,
    business_id     CHAR(36)      NOT NULL,
    branch_id       CHAR(36)      NOT NULL,
    device_key      VARCHAR(64)   NOT NULL,
    label           VARCHAR(80)   NOT NULL,
    registered_by   CHAR(36)      NOT NULL,
    registered_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at      TIMESTAMP     NULL,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uq_till_devices_branch_key (business_id, branch_id, device_key),
    KEY idx_till_devices_branch_active (business_id, branch_id, revoked_at),

    CONSTRAINT fk_till_devices_business FOREIGN KEY (business_id) REFERENCES businesses(id),
    CONSTRAINT fk_till_devices_branch   FOREIGN KEY (branch_id)   REFERENCES branches(id),
    CONSTRAINT fk_till_devices_user     FOREIGN KEY (registered_by) REFERENCES users(id)
);
