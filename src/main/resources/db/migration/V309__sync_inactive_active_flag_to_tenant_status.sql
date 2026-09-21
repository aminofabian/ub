-- V309: Align SA active=false with the auth gate (tenant_status).
-- Historically Super Admin "Active" only flipped businesses.active, while
-- DomainBusinessResolverFilter / login gate on tenant_status. Backfill so
-- already-inactive shops (e.g. catalog active=0 with tenant_status=ACTIVE)
-- cannot keep logging in.
UPDATE businesses
SET tenant_status = 'INACTIVE',
    suspension_reason = 'MANUAL_SUPPORT',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE deleted_at IS NULL
  AND active = FALSE
  AND tenant_status <> 'INACTIVE';

-- Kick any leftover sessions for SA-inactive shops (same as TenancyService deactivate).
UPDATE user_sessions s
INNER JOIN businesses b ON b.id = s.business_id
SET s.revoked_at = CURRENT_TIMESTAMP(6)
WHERE b.deleted_at IS NULL
  AND b.active = FALSE
  AND s.revoked_at IS NULL;
