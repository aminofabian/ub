# Multi-tenant resolution and global tenant context

This doc captures how an incoming HTTP request (any tenant subdomain or
custom domain) is mapped to a tenant configuration that drives auth flows,
branding, and feature gates on the Next.js frontend.

## Flow

```
Browser → Next.js (RSC layout) → backend GET /api/v1/public/host/resolve?host=<host>
                                ↘ Next data cache (revalidate: 60s)
                                ↘ HTTP Cache-Control: max-age=60, public; Vary: host
TenantContext → TenantProvider (React context) → useTenant() in client components
```

One backend call per (host × 60s) per Next process. With CDN/edge caching in
front, the round trip drops to ~1/min/host across the entire fleet.

## Response contract

`GET /api/v1/public/host/resolve?host=acme.palmart.co.ke`

`200 OK`, `Cache-Control: max-age=60, public`, `Vary: host`:

```json
{
  "tenantId": "9f1c7c11-…",
  "tenantName": "Acme Coffee",
  "slug": "acme",
  "status": "ACTIVE",
  "branding": {
    "displayName": "Acme Coffee",
    "logoUrl": "https://res.cloudinary.com/acme/logo.png",
    "faviconUrl": null,
    "primaryColor": "#0F766E",
    "accentColor": "#F59E0B"
  },
  "authConfig": {
    "methods": ["password"],
    "ssoProviders": [],
    "passwordPolicy": { "minLength": 8, "requireNumber": false, "requireSymbol": false }
  },
  "featureFlags": { "shop": true, "loyalty": false },
  "storefrontEnabled": true,
  "resolvedAt": "2026-05-04T05:11:23Z"
}
```

`status` is one of `ACTIVE`, `SUSPENDED`, `INACTIVE`. The frontend renders a
branded status page when the value is anything other than `ACTIVE`.

`404` is returned only for unmapped hosts, so the platform apex
(`palmart.co.ke`) without a `domains` row does not leak tenant existence.

## Database schema

Today the source of truth is two tables:

```
businesses (
  id            CHAR(36) PK,
  name          VARCHAR(255),
  slug          VARCHAR(191) UNIQUE,
  tenant_status VARCHAR(16),    -- ACTIVE | SUSPENDED | INACTIVE
  settings      JSON,           -- branding/authConfig/featureFlags namespaces
  …
)

domains (
  id          CHAR(36) PK,
  business_id CHAR(36) FK businesses(id),
  domain      VARCHAR(255) UNIQUE,
  is_primary  BOOLEAN,
  primary_business_id CHAR(36) GENERATED ALWAYS,
  verified_at TIMESTAMP NULL,
  deleted_at  TIMESTAMP NULL,
  UNIQUE (primary_business_id) -- one primary per tenant
)
```

When the platform grows out of `settings` JSON, the suggested split is:

| Table                | Cardinality | Columns |
| -------------------- | ----------- | ------- |
| `tenants`            | 1:1 with `businesses` (rename) | id, slug, name, status, created_at |
| `tenant_branding`    | 1:1         | tenant_id, logo_url, primary_color, accent_color, favicon |
| `tenant_auth_config` | 1:1         | tenant_id, password_policy_json, methods_json, sso_providers_json |
| `tenant_feature_flags` | many       | tenant_id, key, enabled |
| `tenant_domains`     | many        | (existing `domains` table) |

The frontend contract above is forward-compatible with that split — only the
backend assembly in `PublicHostResolverService` changes.

## Caching layers

1. **Next data cache** — `fetch(url, { next: { revalidate: 60 } })` in the
   server resolver. Per-process per-host coalescing.
2. **HTTP cache headers** — `Cache-Control: max-age=60, public, Vary: host`
   on the resolve response. Any CDN/edge in front of the Next app will cache
   keyed on `Host` and serve at sub-millisecond latency.
3. **Future Redis tier** — if the system needs to survive cold starts of all
   Next processes simultaneously (e.g. blue/green deploys), add a Redis
   fallback in `fetchTenantContext` keyed on `host` with the same TTL. Not
   required at <10k req/s.

## Edge cases

| Scenario | Behaviour |
| -------- | --------- |
| Unknown host (no `domains` row, not `*.localhost`) | Backend 404; frontend resolver returns `null`; `<TenantProvider>` not mounted; admin/login pages render with platform defaults. |
| `*.localhost` in dev with no DB row | Frontend synthesises a minimal `TenantContext` so the dev workflow keeps working without seeded domain mappings. See `frontend/lib/storefront-slug.ts`. |
| Tenant `SUSPENDED`/`INACTIVE` | Backend returns 200 with the status field. Frontend root layout swaps children for `<TenantStatusPage>` (still wrapped in `TenantProvider` so the page is tenant-branded). The same status drives 423 `Locked` from `DomainBusinessResolverFilter` for authenticated APIs. |
| Platform apex (`palmart.co.ke`) with explicit `X-Tenant-Id` | `DomainBusinessResolverFilter` lets the request through; controllers resolve from header. Used for the platform login form. |
| `Host` rewritten by reverse proxy | Spring app must run with `server.forward-headers-strategy=NATIVE` and the proxy must set `X-Forwarded-Host`. Otherwise host-based resolution silently fails. |

## Security

- 404 on unmapped hosts prevents tenant enumeration.
- The resolve endpoint sits inside `PublicStorefrontRateLimitFilter`, which
  applies per-IP and per-host buckets.
- The browser cannot spoof `tenantId` to bypass auth — the backend re-derives
  the tenant from `Host`/`X-Tenant-Id` on every request and validates the
  authenticated principal against it.
- Custom domains require explicit ownership verification before they are
  attached to a tenant (see "Custom domain onboarding" below).

## Frontend integration

```ts
// Server component
const tenant = await resolveTenantContext();
return <TenantProvider value={tenant!}>{children}</TenantProvider>;

// Client component
const tenant = useTenant();           // throws if no provider
const tenant = useOptionalTenant();   // null when outside scope
const enabled = useFeatureFlag("shop"); // sugar
```

## Bonus: Next.js middleware integration sketch

The current implementation runs entirely in server components (no edge
runtime needed). For deployments that prefer pushing the resolve into
middleware (e.g. to redirect suspended tenants without rendering the layout),
the equivalent looks like:

```ts
// frontend/middleware.ts
import { NextResponse, type NextRequest } from "next/server";

const TTL_MS = 60_000;
const cache = new Map<string, { value: TenantContext | null; expires: number }>();

async function resolve(host: string): Promise<TenantContext | null> {
  const hit = cache.get(host);
  if (hit && hit.expires > Date.now()) return hit.value;
  const res = await fetch(
    `${process.env.BACKEND_ORIGIN}/api/v1/public/host/resolve?host=${host}`,
    { next: { revalidate: 60 } },
  );
  const value = res.ok ? ((await res.json()) as TenantContext) : null;
  cache.set(host, { value, expires: Date.now() + TTL_MS });
  return value;
}

export async function middleware(req: NextRequest) {
  const host = req.headers.get("host") ?? "";
  const ctx = await resolve(host);
  if (!ctx) return NextResponse.rewrite(new URL("/_status/unknown", req.url));
  if (ctx.status !== "ACTIVE") {
    return NextResponse.rewrite(
      new URL(`/_status/${ctx.status.toLowerCase()}`, req.url),
    );
  }
  const res = NextResponse.next();
  res.headers.set("x-tenant-id", ctx.tenantId);
  return res;
}

export const config = {
  matcher: ["/((?!_next|_status|api/health|favicon).*)"],
};
```

### Express equivalent

```ts
import express from "express";
const app = express();

app.use(async (req, res, next) => {
  const url = `${process.env.BACKEND_ORIGIN}/api/v1/public/host/resolve?host=${req.hostname}`;
  const ctx = await fetch(url).then(r => (r.ok ? r.json() : null));
  if (!ctx) return res.status(404).send("Unknown tenant");
  if (ctx.status !== "ACTIVE") return res.redirect(`/_status/${ctx.status.toLowerCase()}`);
  req.tenant = ctx;
  next();
});
```

## Wildcard subdomain provisioning

Already implemented: when the super-admin creates a business without a
`primaryDomain`, `TenancyService.createBusiness` mints
`<slug>.<APP_TENANCY_SLUG_DOMAIN_SUFFIX>` as the verified primary domain.

In production, set:

```
APP_TENANCY_SLUG_DOMAIN_SUFFIX=palmart.co.ke
```

The default in `application.properties` is `localhost` for dev; without the
override, prod businesses end up with `<slug>.localhost` rows that don't
match any real prod host. DNS for the parent zone needs a wildcard `A`
record (`*.palmart.co.ke` → load balancer) and TLS via wildcard cert or an
ACME-managed wildcard.

## Custom domain onboarding

Tenants self-service their domains through `MyDomainsController`
(`/api/v1/businesses/me/domains`). Recommended onboarding flow:

1. **Add domain** — `POST /api/v1/businesses/me/domains { "domain": "shop.acme.com" }`
   - Backend stores the domain with `verified_at = NULL`.
   - Backend returns a TXT challenge token (e.g. `_palmart-verify=<token>`).
2. **DNS verification** — tenant adds the TXT record. A scheduled job
   (suggested follow-up) polls until the TXT record matches and stamps
   `verified_at`.
3. **Set primary** — `POST /api/v1/businesses/me/domains/{id}/primary` once
   verified.
4. **Certificates** — either Cloudflare-for-SaaS (preferred for managed
   TLS), or backend-side ACME with HTTP-01/DNS-01 challenges.
5. **Soft delete** — `DELETE /api/v1/businesses/me/domains/{id}` (the
   primary cannot be deleted; pick a new primary first).

The same flow drives `PublicHostResolveController`: as soon as
`verified_at` is non-null and `deleted_at` is null, the host resolves.

## Related files

- Backend
  - `backend/src/main/java/zelisline/ub/tenancy/api/PublicHostResolveController.java`
  - `backend/src/main/java/zelisline/ub/tenancy/application/PublicHostResolverService.java`
  - `backend/src/main/java/zelisline/ub/tenancy/application/StorefrontSettingsService.java` (`readTenantConfig`)
  - `backend/src/main/java/zelisline/ub/tenancy/infrastructure/DomainBusinessResolverFilter.java`
  - `backend/src/main/resources/db/migration/V42__tenant_status.sql`
- Frontend
  - `frontend/lib/public-storefront.ts` (`fetchTenantContext`, `TenantContext`)
  - `frontend/lib/storefront-slug.ts` (`resolveTenantContext`)
  - `frontend/components/providers/tenant-provider.tsx`
  - `frontend/app/layout.tsx`
  - `frontend/components/storefront/tenant-status-page.tsx`
  - `frontend/app/_status/suspended/page.tsx`, `frontend/app/_status/inactive/page.tsx`
