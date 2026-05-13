-- Phase 9: Multi-branch permissions (Slice 1-2)
-- branch.manage: create, edit, archive branches
-- reports.branch.all: HQ scope — view all branches in reports

INSERT IGNORE INTO permissions (id, permission_key, description, created_at, updated_at)
VALUES
  (UUID(), 'branch.manage', 'Create and manage branches', NOW(), NOW()),
  (UUID(), 'reports.branch.all', 'View reports across all branches (HQ scope)', NOW(), NOW());
