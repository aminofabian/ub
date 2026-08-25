-- Capture failure detail for Super Admin → Platform → Logs drawers.

ALTER TABLE platform_request_log
  ADD COLUMN error_title VARCHAR(255) NULL AFTER load_test_run_id,
  ADD COLUMN error_detail MEDIUMTEXT NULL AFTER error_title,
  ADD COLUMN error_type VARCHAR(255) NULL AFTER error_detail,
  ADD COLUMN exception_class VARCHAR(255) NULL AFTER error_type,
  ADD COLUMN exception_chain MEDIUMTEXT NULL AFTER exception_class,
  ADD COLUMN stack_summary MEDIUMTEXT NULL AFTER exception_chain,
  ADD COLUMN user_agent VARCHAR(512) NULL AFTER stack_summary,
  ADD COLUMN request_meta MEDIUMTEXT NULL AFTER user_agent;
