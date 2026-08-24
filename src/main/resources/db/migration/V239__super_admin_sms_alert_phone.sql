-- Super-admin phone for platform ops SMS alerts (tenant adoptions: Kiosk Pay, custom domains).
ALTER TABLE super_admins ADD COLUMN phone VARCHAR(32) NULL;
