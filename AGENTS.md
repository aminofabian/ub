# Backend (Kiosk API) — agent instructions

Spring Boot 4.0.5 · Java 21 toolchain · Gradle 8.14 (`./gradlew`) · JPA/Hibernate · Flyway · Spring Security + JWT · Redis · WebSocket · Actuator/Prometheus · springdoc (OpenAPI 3.1 at `/api/v1/openapi`, UI at `/swagger-ui`). Read `../AGENTS.md` (workspace rules) and `CONTEXT.md` (ledger vocabulary) first.

## Layout
- Package root `zelisline.ub`, one package per module (`ls src/main/java/zelisline/ub` — about 44, e.g. `sales`, `finance`, `payments`, `tenancy`, `identity`, `storefront`, `sync`). Inside a module: `api` (controllers, DTOs), `application` (services, ports), `domain`, `repository`, `infrastructure`, `scheduler` — only the layers that module needs.
- Call another module through its `application` types or ports, not its repositories or entities (example: money goes through `finance.application.LedgerPostingPort`).
- Errors: reuse `platform/web/GlobalExceptionHandler`; do not invent a new error shape.
- One JAR, three deployment modes (`cloud`, `local`, `hybrid`) plus the `desktop` profile with bundled MariaDB 10.11 — see `docs/adr/` (ADR 0011 describes the modes). Check that new code works in every mode, or is explicitly cloud-only.
- **Boundaries are mostly unenforced.** The only ArchUnit tests are `IdentityDomainArchTest` and `TenancyDomainArchTest` (those `domain` packages must not import `org.springframework..`). ADR 0011 says other boundaries are "policed by ArchUnit"; no such test exists. When you rely on a boundary, check imports by hand; when you add one, add the ArchUnit test.

## Database and migrations — highest-risk area
- Migrations live in `src/main/resources/db/migration/V<n>__snake_case_description.sql`, MySQL dialect (MariaDB 10.11 runs them for desktop). Use the next free number: `ls src/main/resources/db/migration | sort -V | tail`.
- **Never edit or rename an applied migration.** `configuration/FlywayConfig` runs `flyway.repair()` before every migrate, and `out-of-order` and `baseline-on-migrate` are on, so an edited file does not fail: Flyway silently rewrites the stored checksum and the real schema drifts from the files. Fix forward with a new migration.
- Entities and migrations change together. Production runs `ddl-auto=validate`; a mismatch only shows up when the app boots against MySQL.
- **`./gradlew test` cannot catch migration errors.** The test profile is H2 in MySQL mode with Flyway disabled; each DB test builds its schema from the entities (`@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")`, because the base test profile sets `ddl-auto=none`). Verify a new migration by running it on a throwaway MySQL and a throwaway MariaDB 10.11 — never a shared or production database — and report exactly what you ran.
- Tenant-scoped tables carry `business_id`; index it and include it in the access paths you add. For high-volume tables (sales, ledger, stock) avoid long-locking `ALTER`s: expand, backfill in batches, then contract. State the forward-fix plan in the PR/notes (no down-migrations are configured).
- No `DROP`, `TRUNCATE` or unbounded `UPDATE`/`DELETE` in a migration or script without the user's explicit OK.

## Tenancy, security, integrations
- Tenant = business. It is resolved from the request host and headers in `tenancy` (`DomainBusinessResolverFilter`, `PublicHostBusinessResolver`, `TenantRequestIds`). Never let a request body decide which business's data is read or written; every repository query on tenant data filters by `business_id`; add a test that a second business is denied.
- Every endpoint has explicit authorization. Public endpoints (storefront, supplier portal, webhooks, M-Pesa callbacks) are untrusted input: validate, bound sizes, make them idempotent, and keep them out of logs when they carry PII.
- Money-moving endpoints honour `Idempotency-Key` (see `payments`: `MpesaStkIntentApiController`, `KioskPayTenantController`, `GatewayStkPushService`); follow that pattern for new ones.
- External services (Daraja, SMS, WhatsApp/Meta, AI providers, Vercel/HostAfrica/Coolify domain APIs) sit behind an interface, with timeouts and failure handling. Secrets come from environment variables only; variable names are in `.env.example`. Do not open `.env`.

## Tests
- Tests mirror the main package tree under `src/test/java/zelisline/ub/<module>/…`; JUnit Platform, no Testcontainers. Integration tests (`*IT.java`: `@SpringBootTest`, `@ActiveProfiles("test")`, the `create-drop` property above) run in `./gradlew test` too.
- Run the narrow set first: `./gradlew test --tests 'zelisline.ub.<module>.*'`, then the full suite. Test JVMs run with `-Xmx2g`; `Dockerfile` is tuned for ~2 GB builders, so do not add heavy build steps.
- Needs JDK 21. If it is missing, say the tests were not run — do not report them as passing.
- New behaviour needs a test for the failure path as well as the happy path (invalid input, duplicate request, other tenant, downstream failure).
