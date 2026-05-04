-- V42: tenant lifecycle status for the public host-resolve payload and the
-- DomainBusinessResolverFilter auth-time gate. ACTIVE is the only status that
-- lets a tenant log in or accept storefront traffic; SUSPENDED/INACTIVE render
-- branded status pages on the Next.js side and 423 on authenticated APIs.
ALTER TABLE businesses
  ADD COLUMN tenant_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE businesses
  ADD CONSTRAINT businesses_tenant_status_chk
  CHECK (tenant_status IN ('ACTIVE', 'SUSPENDED', 'INACTIVE'));

CREATE INDEX ix_businesses_tenant_status ON businesses (tenant_status);
