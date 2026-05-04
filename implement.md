# Kiosk POS — Java Rebuild Blueprint

> A supplier-first, multi-tenant Point-of-Sale / Inventory / Accounts platform,
> rebuilt from the current Next.js + Turso (SQLite) implementation into a
> modular Java (Spring Boot 3 + PostgreSQL) system designed for real grocery /
> retail / wholesale businesses.

This document is the **single source of truth** for the rebuild. It is derived
from a full audit of the existing TypeScript codebase (`app/`, `lib/`,
`components/`, `scripts/`) and is intended to be handed to a developer and
executed phase-by-phase.

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Audit of the Existing System](#2-audit-of-the-existing-system-what-we-are-replacing)
3. [Core Modules and Their Functions](#3-core-modules-and-their-functions)
4. [Supplier-Based Architecture](#4-supplier-based-architecture-the-central-thesis)
5. [Database Structure (PostgreSQL)](#5-database-structure-postgresql)
6. [User Roles and Permissions](#6-user-roles-and-permissions)
7. [Stock Management Workflow](#7-stock-management-workflow)
8. [Sales and Purchase Flow](#8-sales-and-purchase-flow)
9. [Reporting and Analytics](#9-reporting-and-analytics)
10. [Admin Controls and Configurations](#10-admin-controls-and-configurations)
11. [Java Project Structure](#11-java-project-structure-spring-boot-3-multi-module)
12. [Development Roadmap (Phase 1 → GA)](#12-development-roadmap-phase-1--ga)
13. [Improvements Over the Current System](#13-improvements-over-the-current-system)
14. [Risks, Edge Cases & Non-Negotiables](#14-risks-edge-cases--non-negotiables)
15. [Local / Offline-First Deployment](#15-local--offline-first-deployment)
16. [Appendix A — API Surface](#appendix-a--rest-api-surface-v1)
17. [Appendix B — Domain Events](#appendix-b--domain-events)

---

## 1. System Overview

### 1.1 What the product is

A cloud-hosted POS and back-office platform for small-to-medium shops
(kiosks, mini-marts, groceries, wholesalers). A single instance serves many
tenants (businesses), each with multiple users, optional multiple branches,
and custom domains.

Primary user journeys:

- A **cashier** opens a shift, rings up sales at the POS, accepts cash /
  M-Pesa / credit (tab) / wallet / split payments, and closes the shift with
  a denomination-level cash count.
- An **admin / owner** manages products, suppliers, purchases, stock, pricing,
  users, credits, and reports.
- A **supplier** (external, read-only via share link — planned) can see what
  is owed and invoice history.
- A **super-admin** (platform operator) manages tenants, domains, and
  subscription state.

### 1.2 What is different in this rebuild

The existing system treats **suppliers as a bolted-on directory** — an item
can exist with no supplier, purchases can be recorded with a free-text
supplier name, and restocking can bypass the supplier link entirely.

The Java rebuild inverts this: **the supplier is a first-class root aggregate**.
Every item has at least one supplier; every inventory batch carries the
supplier that provided it; every cost price is attributed to a supplier; and
every cash outflow for goods posts against a supplier account. The supplier
ledger (accounts payable) is built-in, not an afterthought.

### 1.3 High-level architecture

```
                    ┌─────────────────────────────────────────┐
                    │      Web / PWA (cashier + admin)        │
                    │   Next.js 16 or Vite-React frontend     │
                    └───────────────┬─────────────────────────┘
                                    │ HTTPS (JWT / cookie)
                    ┌───────────────▼─────────────────────────┐
                    │           API Gateway (Spring)           │
                    │  Auth · Rate-limit · Tenant resolver     │
                    └───────────────┬─────────────────────────┘
         ┌────────────┬─────────────┼───────────────┬─────────────┐
         │            │             │               │             │
    ┌────▼────┐  ┌────▼────┐   ┌────▼────┐    ┌─────▼─────┐  ┌────▼─────┐
    │ Catalog │  │Inventory│   │  Sales  │    │ Suppliers │  │ Finance  │
    │ service │  │ service │   │ service │    │  service  │  │ service  │
    └────┬────┘  └────┬────┘   └────┬────┘    └─────┬─────┘  └────┬─────┘
         │            │             │               │             │
         └────────────┴─────────────┼───────────────┴─────────────┘
                                    │
                       ┌────────────▼────────────┐
                       │ PostgreSQL 16 + Flyway   │
                       │ Redis (cache / rate-lim) │
                       │ MinIO/S3 (images, PDFs)  │
                       │ Kafka (async events)     │
                       └──────────────────────────┘
```

The **same codebase ships in three deployment modes** (see §15 for details):

1. **Cloud** — the diagram above, hosted, multi-tenant. Default for new tenants.
2. **Local (on-prem single-PC / LAN)** — the shop runs its own server binary on
   a PC in the back office; cashier terminals connect over LAN. No internet
   required for any core workflow (sales, purchases, stock, reports, printing,
   scanning). Internet-only features (M-Pesa STK push, SMS, email, cloud
   backup) degrade gracefully and sync when connectivity returns.
3. **Hybrid** — local-first install that mirrors to the cloud over a durable
   outbox whenever the internet is up, so the owner can see reports from home
   and a lost-PC scenario is recoverable.

Initial delivery is a **Spring Boot modular monolith** with clear bounded
contexts (module-per-context). Only extract services when scale demands it.

### 1.4 Target stack

The dialect and APIs stay identical across deployment modes — only the
**adapter implementation** swaps via Spring profiles (`cloud` / `local` /
`hybrid`). Every component has a first-class local drop-in so the system
can run with zero outbound network traffic.

| Layer            | Cloud default                                        | Local drop-in (`local` profile)                          |
|------------------|------------------------------------------------------|----------------------------------------------------------|
| Language         | Java 21 (records, sealed types, pattern matching)    | same                                                     |
| Framework        | Spring Boot 3.3.x                                    | same, repackaged via `jpackage`                          |
| Build            | Gradle 8 (Kotlin DSL) multi-module                   | same                                                     |
| DB               | PostgreSQL 16 (managed)                              | PostgreSQL 16 **bundled** (portable binaries + Flyway)   |
| Migrations       | Flyway                                               | same — identical migrations, no dialect fork             |
| Persistence      | Spring Data JPA (Hibernate) + jOOQ for reports       | same                                                     |
| Security         | Spring Security + JWT (access) + refresh tokens      | same + local activation key for offline licensing        |
| Cache / queues   | Redis 7                                              | **Caffeine** in-process cache; no external Redis needed  |
| Message bus      | Kafka 3 (or Redis Streams for v1)                    | DB-backed outbox + Spring `@EventListener` (in-JVM)      |
| Object storage   | S3 / MinIO (receipts, images, backups)               | **Local filesystem** under `$DATA_DIR/storage/` (same `StorageAdapter` interface) |
| Search           | PostgreSQL FTS (`pg_trgm`, `tsvector`), Meilisearch  | PostgreSQL FTS only (Meilisearch dropped — FTS is enough for a single shop) |
| Validation       | Jakarta Validation + custom domain assertions        | same                                                     |
| Mapping          | MapStruct                                            | same                                                     |
| Tests            | JUnit 5 + Testcontainers + RestAssured + ArchUnit    | same                                                     |
| Observability    | Micrometer + OpenTelemetry + Loki/Tempo/Grafana      | Micrometer → rolling JSON log files + a local `/metrics` page |
| Payments         | M-Pesa Daraja / Pesapal / Stripe (pluggable)         | **Manual-reference** gateway online when offline; Daraja/Pesapal queued via outbox |
| Mail             | Amazon SES (swap-in for Resend)                      | Outbox queue; optional SMTP relay (e.g. local Postfix) when online |
| SMS              | Africa's Talking / Twilio                            | Queue in outbox; optional USB GSM modem (gammu-smsd) for truly offline SMS |
| PDF / prints     | OpenPDF or Apache PDFBox, ESC/POS via javapos        | same — printing is LAN/USB/Bluetooth, needs no internet  |
| Containerisation | Docker + docker-compose dev, ECS/Fargate or K8s prod | `jpackage` → Windows `.msi` / macOS `.pkg` / Linux `.deb`/`.rpm`; optional docker-compose for LAN install |
| CI               | GitHub Actions (test, container build, scan, deploy) | same + extra matrix job producing the signed installers  |

---

## 2. Audit of the Existing System (what we are replacing)

Derived directly from the source.

### 2.1 Tables present today (`lib/db/sql/schema.sql` + migrations)

```
businesses, super_admins, domains, users, password_reset_tokens,
categories, aisles, items (with variants, bundle pricing, packaging),
selling_prices, buying_prices,
suppliers, supplier_products, supplier_bills,
purchases, purchase_items, purchase_breakdowns, inventory_batches,
sales, sale_items, sale_payments,
shifts (+ denomination columns), stock_adjustments, stock_approval_requests,
balance_approval_requests,
credit_accounts, credit_transactions, wallet_transactions,
loyalty_transactions, public_credit_pesapal_pending,
expenses, external_api_keys, activity_log,
out_of_stock_requests, items_fts (FTS5 virtual table)
```

### 2.2 Confirmed workflows

- **Grocery purchase flow** (truth-emerges pattern):
  `purchase → purchase_items (raw “2 crates tomatoes”) → purchase_breakdowns (usable + wastage + unit cost) → inventory_batches → items.current_stock += usable_qty`.
  Seen in `app/api/purchases/[id]/breakdown/route.ts`.
- **Retail supplier-bill flow**:
  `supplier_bill → inventory_batches directly → items.current_stock += qty`,
  with `buying_prices` history written. Seen in `app/api/supplier-bills/route.ts`.
- **Sale flow (FIFO)**:
  `sale → for each line: getBatchesForSale() picks oldest active batches → sale_items rows with profit locked → decrement batch.quantity_remaining → items.current_stock -= qty → update shifts.expected_closing_cash for cash portion → credit / wallet / loyalty side-effects`.
  Seen in `lib/utils/fifo.ts` and `app/api/sales/route.ts`.
- **Stock adjustments with approvals**: cashier creates `stock_approval_requests`, admin approves. Reasons: `restock | spoilage | theft | counting_error | damage | other`.
- **Shift lifecycle**: open with opening-cash denominations → sales auto-update `expected_closing_cash` → close with actual-cash denominations → `cash_difference`.
- **Credit + wallet + loyalty** tracked on one `credit_accounts` row per customer-phone.
- **Public payment claims**: a customer can self-report a payment from a share link (`/c/...`); admin reviews/approves before the ledger moves.

### 2.3 Real weaknesses observed (fix in the rebuild)

| #  | Weakness in current system                                                    | Source of evidence                                    |
|----|-------------------------------------------------------------------------------|-------------------------------------------------------|
| 1  | Supplier is optional on items, purchases, batches                             | `supplier_id TEXT` nullable, `supplier_name` free-text |
| 2  | No DB transactions around multi-row operations (sales write 8+ statements)    | `app/api/sales/route.ts` has no BEGIN/COMMIT          |
| 3  | Self-healing `ALTER TABLE` inside request handlers                            | `ensureSupplierColumns`, `ensureSupplierBillsPaymentColumns` |
| 4  | Cost fallback cascade done at query time (join subqueries)                    | `buyPriceFallback` SQL in profit/dashboard routes     |
| 5  | `current_stock` denormalized on `items` drifts from sum of batches            | Decrement in sales + wastage branch both mutate item.current_stock |
| 6  | No accounts-payable / accounts-receivable ledger; money state spread across tables | `supplier_bills`, `credit_transactions`, `wallet_transactions` are siloed |
| 7  | No purchase-order → goods-receipt → invoice workflow; it jumps straight to ad-hoc notes | `purchases`, `purchase_items` are after-the-fact    |
| 8  | `product_types` stored as JSON inside `businesses.settings`                   | `lib/types/product-types.ts`                          |
| 9  | FIFO ignores `expiry_date` (should be FEFO for groceries)                     | `lib/utils/fifo.ts` orders by `received_at ASC` only  |
| 10 | Phones stored sometimes as string, sometimes as JSON array in same column     | `customer_phone` column in `credit_accounts`          |
| 11 | Permissions are a hardcoded map, not data                                     | `lib/auth/permissions.ts`                             |
| 12 | No idempotency keys on sale POSTs (retrying duplicates a sale)                | `app/api/sales/route.ts`                              |
| 13 | No multi-branch / multi-warehouse concept; stock is per-business              | `items.current_stock` scalar                          |
| 14 | Reports re-compute everything per request                                     | `reports/profit`, `dashboard`, `daily-summary`        |
| 15 | Activity log is write-only, never surfaced consistently                       | `activity_log` has UI in only a few places            |

These drive the improvements in §13.

---

## 3. Core Modules and Their Functions

Each module is a Gradle sub-project with its own package root
`ke.kiosk.<module>` and its own Flyway migration folder. Cross-module
communication happens only through:

- Explicit **application services** (no reaching into another module's DAO).
- Published **domain events** (`module.entity.verb`) on the event bus.

| # | Module          | Root package               | Owns                                                                                   |
|---|-----------------|----------------------------|----------------------------------------------------------------------------------------|
| 1 | `identity`      | `ke.kiosk.identity`        | Businesses, users, roles, permissions, sessions, API keys, password reset.             |
| 2 | `tenancy`       | `ke.kiosk.tenancy`         | Domains → business resolution, subscription state, super-admin operations.             |
| 3 | `catalog`       | `ke.kiosk.catalog`         | Categories, aisles, items (with variants), bundle pricing, barcode, images, FTS index. |
| 4 | `suppliers`     | `ke.kiosk.suppliers`       | Suppliers, supplier contacts, supplier types, supplier ↔ item links, supplier PDFs.    |
| 5 | `purchasing`    | `ke.kiosk.purchasing`      | Purchase orders, goods receipts, breakdowns, raw purchase notes, supplier invoices.    |
| 6 | `inventory`     | `ke.kiosk.inventory`       | Inventory batches (lots), stock ledger (append-only), FEFO/FIFO pickers, adjustments.  |
| 7 | `pricing`       | `ke.kiosk.pricing`         | Selling-price history, cost-price history, margin rules, auto-markup policies.         |
| 8 | `sales`         | `ke.kiosk.sales`           | Shifts, POS cart, sales, sale_items, refunds/voids, receipts, tax engine hook.         |
| 9 | `payments`      | `ke.kiosk.payments`        | Payment methods, split payments, M-Pesa/Pesapal/Stripe gateways, STK push state.       |
|10 | `credits`       | `ke.kiosk.credits`         | Customer accounts, tab (debt), wallet (prepaid), loyalty points, public-claim review.  |
|11 | `finance`       | `ke.kiosk.finance`         | Accounts payable (to suppliers), accounts receivable (from customers), expenses, cash drawer. |
|12 | `reporting`     | `ke.kiosk.reporting`       | Materialized-view refreshers, daily/weekly snapshots, profit by X, supplier comparison. |
|13 | `auditing`      | `ke.kiosk.auditing`        | Activity log, change-data-capture to event bus, data-retention policy.                 |
|14 | `integrations`  | `ke.kiosk.integrations`    | S3, email (SES), SMS, webhook dispatcher, external-API-key gateway.                    |
|15 | `notifications` | `ke.kiosk.notifications`   | Low-stock alerts, expiry alerts, overdue bills, shift variance alerts (push + email).  |
|16 | `exports`       | `ke.kiosk.exports`         | PDF receipts, sticker sheets, CSV/Excel, end-of-day reports, tax-return exports.       |

Each module exposes a `XxxApi` facade interface; the REST controller depends
only on that interface. Inter-module calls also go through facades.

---

## 4. Supplier-Based Architecture (the central thesis)

### 4.1 Invariants

These are enforced at the **DB layer (NOT NULL / CHECK / triggers)** and at
the **service layer (domain assertions)**. Violating any of them is a bug.

1. **Every sellable item has ≥ 1 supplier link at all times.**
   - A `Supplier` → `SupplierProduct` (M:N) → `Item` graph.
   - One of the links must be marked `is_primary = true`.
   - Deactivating the last supplier of a sellable item automatically marks the item `inactive` (cannot be sold), with a reason logged.
2. **Every inventory batch names its supplier** (`inventory_batches.supplier_id NOT NULL`).
3. **Every cost price names its supplier** (`buying_prices.supplier_id NOT NULL`).
4. **Every purchase has a supplier** (`purchases.supplier_id NOT NULL`). Free-text supplier names are disallowed; an “ad-hoc” supplier record is created instead (and flagged so owner can merge it later).
5. **Every supplier payment** (cash or M-Pesa) posts against `supplier_invoices` rows. An unmatched cash outflow to a supplier creates a credit on the supplier account, not a floating cash event.

### 4.2 Supplier aggregate

```
Supplier
├─ id, business_id, name (unique per business), type (farmer|wholesaler|distributor|service|utility|...)
├─ contacts[]            (phones, emails, addresses, contact persons)
├─ payment_profile       (preferred method: cash|mpesa|bank, account details)
├─ credit_terms          (net_days, credit_limit_kes)
├─ tax_profile           (VAT pin, withholding applicable, rate)
├─ status                (active|inactive|blocked)
├─ rating                (computed: on-time %, quality score, price competitiveness)
└─ audit                 (created_at, created_by, updated_at)
```

### 4.3 Supplier ↔ Item link

```
SupplierProduct
├─ id, supplier_id, item_id (unique pair)
├─ is_primary               (exactly one per item must be true)
├─ supplier_sku             (supplier's own code)
├─ default_cost_price       (fallback when last invoice is missing)
├─ pack_size, pack_unit     (e.g. 1 carton = 18 packets)
├─ lead_time_days
├─ min_order_qty
├─ last_cost_price          (auto-maintained by trigger on buying_prices insert)
├─ last_purchase_at
└─ active
```

A DB trigger keeps `last_cost_price` and `last_purchase_at` in sync — no ad-hoc
subqueries at read time.

### 4.4 Supplier lifecycle

```
Draft ── owner fills name only ──▶ Pending
Pending ── first purchase made ──▶ Active
Active ── 90d without activity ──▶ Dormant
Dormant ── purchase made ─────────▶ Active
Any ── owner disables ────────────▶ Blocked (cannot be selected)
```

A nightly job recomputes dormancy.

### 4.5 Why this matters

Because every stock unit carries its supplier, the system can answer
unambiguously:

- How much do I owe supplier X right now, broken down by invoice age?
- Who provided the 12 tomato crates I sold last week, and at what margin?
- Which supplier is cheapest for this SKU over the last N purchases (not last single purchase)?
- If supplier X’s onions caused spoilage, what % of wastage is attributable to them vs others?
- If I lose supplier X, which SKUs become single-sourced (supply risk)?

None of these are reliably answerable today.

---

## 5. Database Structure (PostgreSQL)

This is the **target schema**, not a migration of the current one. A one-shot
migration script reads the existing Turso DB and writes it into this schema.

Conventions:

- Primary keys are `UUID v7` (time-ordered, indexable) unless noted.
- Money is `NUMERIC(14,2)`; quantities that allow fractions are `NUMERIC(14,4)`.
- Timestamps are `TIMESTAMPTZ`; server timezone UTC; display timezone per-business.
- Every table has: `id`, `business_id`, `created_at`, `updated_at`, `created_by`, `updated_by`. (Except dictionary tables.)
- Soft delete: `deleted_at TIMESTAMPTZ NULL` + partial indexes `WHERE deleted_at IS NULL`.
- Row-level security: `business_id = current_setting('app.business_id')::uuid` enforced via RLS policy on every tenant-scoped table. Service sets the session variable after JWT validation.

### 5.1 Identity & tenancy

```sql
businesses (id, name, slug, currency, timezone, country_code, active,
            subscription_tier, subscription_renews_at, settings_jsonb,
            loyalty_points_per_currency_unit)
branches   (id, business_id, name, address, active)              -- NEW
domains    (id, business_id, domain, is_primary, active)
users      (id, business_id, branch_id NULL, email, phone, name,
            password_hash, pin_hash, status, role_id,
            last_login_at, failed_attempts, locked_until)
roles      (id, business_id NULL, key, name, description, is_system)
permissions(id, key, description)                                -- dictionary
role_permissions(role_id, permission_id)                         -- M:N
user_sessions  (id, user_id, access_token_jti, refresh_token_hash,
                user_agent, ip, issued_at, expires_at, revoked_at)
api_keys   (id, business_id, user_id, label, token_hash,
            token_prefix, scopes jsonb, active, last_used_at)
super_admins(id, email, name, password_hash, mfa_secret, active)
password_reset_tokens(id, user_id, token_hash, expires_at, used_at)
```

### 5.2 Catalog

```sql
categories (id, business_id, name, slug, position, icon, parent_id NULL, active)
aisles     (id, business_id, name, code, sort_order, active)
item_types (id, business_id, key, label, icon, color, sort_order, active)  -- replaces settings JSON

items (id, business_id, sku, barcode, name, description, variant_of_item_id NULL,
       variant_name, category_id, aisle_id NULL, item_type_id,
       unit_type, is_weighed, is_sellable, is_stocked,
       tax_rate_id NULL, brand_id NULL,
       packaging_unit_name, packaging_unit_qty,
       bundle_qty, bundle_price, bundle_name,
       min_stock_level, reorder_level, reorder_qty,
       expires_after_days, has_expiry,
       image_key, active)

item_images (id, item_id, s3_key, width, height, sort_order)
item_tags   (item_id, tag)                                       -- free-form labels
```

### 5.3 Suppliers (see §4)

```sql
suppliers          (id, business_id, name, code, supplier_type,
                    vat_pin, is_tax_exempt, credit_terms_days,
                    credit_limit, rating, status, notes,
                    payment_method_preferred, payment_details)
supplier_contacts  (id, supplier_id, name, role, phone, email, is_primary)
supplier_products  (id, supplier_id, item_id, is_primary, supplier_sku,
                    default_cost_price, pack_size, pack_unit,
                    lead_time_days, min_order_qty,
                    last_cost_price, last_purchase_at, active,
                    UNIQUE(supplier_id, item_id))
```

Trigger: `exactly_one_primary_supplier_per_item()` — after insert/update on
`supplier_products`, if the item has 0 primaries, promote the newest; if it
has >1, demote all except the newest. Raise if delete leaves item orphaned
and item is `is_sellable=true` and `is_stocked=true`.

### 5.4 Purchasing

```sql
purchase_orders (id, business_id, branch_id, supplier_id, po_number,
                 status: draft|sent|partially_received|received|cancelled,
                 expected_date, total_estimated, notes, created_by)
purchase_order_lines (id, po_id, item_id, supplier_product_id,
                      qty_ordered, qty_received, unit_cost_estimated, tax_rate_id)

goods_receipts  (id, business_id, branch_id, supplier_id, po_id NULL,
                 grn_number, received_date, received_by, notes,
                 status: draft|posted|reversed)
goods_receipt_lines (id, grn_id, item_id, qty_received, qty_wastage,
                     unit_cost_actual, expiry_date, batch_ref,
                     resulting_inventory_batch_id)

supplier_invoices (id, business_id, supplier_id, grn_id NULL,
                   invoice_number, invoice_date, due_date,
                   subtotal, tax_total, grand_total,
                   status: draft|pending|partial|paid|overdue|cancelled,
                   payment_terms, notes)
supplier_invoice_lines (id, invoice_id, description, item_id NULL,
                        qty, unit_cost, tax_rate_id, line_total)

supplier_payments (id, business_id, supplier_id, invoice_id NULL,
                   paid_at, amount, method, reference,
                   shift_id NULL, recorded_by, notes)
```

A `supplier_payment` can be allocated to 0..N invoices via
`supplier_payment_allocations(payment_id, invoice_id, amount)`. Unallocated
amount sits as a credit (negative balance) on the supplier account.

### 5.5 Inventory (append-only ledger)

```sql
inventory_batches (id, business_id, branch_id, item_id, supplier_id,
                   batch_number, source_type: grn|opening|adjustment|transfer,
                   source_id, initial_quantity, quantity_remaining,
                   unit_cost, received_at, expiry_date, status)

stock_movements (id, business_id, branch_id, item_id, batch_id NULL,
                 movement_type: receipt|sale|refund|wastage|adjustment|transfer_in|transfer_out|opening,
                 reference_type, reference_id, quantity_delta,
                 unit_cost, running_balance, created_at, created_by,
                 reason, notes)

stock_adjustments        (id, business_id, branch_id, item_id, system_qty, actual_qty,
                          difference, reason, notes, adjusted_by, approved_by NULL)
stock_adjustment_requests(id, business_id, branch_id, item_id, adjustment_type,
                          quantity, reason, notes, requested_by, status, decided_by, decided_at)

stock_transfers      (id, business_id, from_branch_id, to_branch_id, status,
                      initiated_by, received_by, initiated_at, received_at)
stock_transfer_lines (id, transfer_id, item_id, batch_id, quantity)
```

**Invariant:** `items.current_stock` is a **projection**, not a source of
truth. A view `v_item_stock_current` sums `inventory_batches.quantity_remaining`
per item per branch. The denormalised column, if kept for hot-path speed, is
updated only by a trigger that fires on `stock_movements` insert, and is
reconciled nightly.

### 5.6 Pricing

```sql
selling_prices (id, item_id, branch_id NULL, price, effective_from, effective_to NULL, set_by)
buying_prices  (id, item_id, supplier_id, unit_cost, effective_from, effective_to NULL, set_by, notes, source_type)
price_rules    (id, business_id, name, rule_type, params_jsonb, active)   -- future: promos, bulk discounts
tax_rates      (id, business_id, name, rate_percent, inclusive)
```

### 5.7 Sales

```sql
shifts (id, business_id, branch_id, user_id, opening_cash, expected_closing_cash,
        actual_closing_cash, cash_difference, started_at, ended_at, status,
        opening_denominations_jsonb, closing_denominations_jsonb)

sales (id, business_id, branch_id, shift_id NULL, user_id,
       code (human-readable), subtotal, discount_total, tax_total, grand_total,
       payment_method, status: draft|completed|voided|refunded,
       customer_id NULL, idempotency_key UNIQUE NULL,
       voided_reason, voided_by, sale_at)

sale_items (id, sale_id, item_id, batch_id NULL,
            description_snapshot, item_type_snapshot,
            quantity, unit_price, discount, tax, line_total,
            unit_cost, cost_total, profit)

sale_payments (id, sale_id, method, amount, reference,
               customer_id NULL, gateway_txn_id NULL, status)

refunds      (id, sale_id, refund_code, refunded_at, refunded_by,
              total_refunded, reason, status)
refund_lines (id, refund_id, sale_item_id, quantity, amount)
```

### 5.8 Customers, credit, wallet, loyalty

```sql
customers (id, business_id, name, email, notes, created_at)
customer_phones (id, customer_id, phone, is_primary)

credit_accounts (id, business_id, customer_id, balance_owed, wallet_balance,
                 loyalty_points, credit_limit, last_activity_at)

credit_transactions (id, credit_account_id, sale_id NULL,
                     type: debt|payment|adjustment,
                     amount, method NULL, notes, recorded_by,
                     public_claim_status, claim_reviewed_at, claim_reviewed_by,
                     debt_line_items_snapshot_jsonb)

wallet_transactions (id, credit_account_id, sale_id NULL,
                     type: credit|debit|adjustment,
                     amount, method NULL, reference, notes, recorded_by,
                     public_claim_status)

loyalty_transactions (id, credit_account_id, sale_id NULL,
                      type: earn|redeem|adjust|expire,
                      points, notes, recorded_by)
```

### 5.9 Finance / AP / AR glue

A light double-entry layer for reliable cash reconciliation without a full
accounting package:

```sql
ledger_accounts (id, business_id, code, name, type: asset|liability|revenue|expense|equity, parent_id)
journal_entries (id, business_id, entry_date, source_type, source_id, memo)
journal_lines   (id, entry_id, ledger_account_id, debit, credit)
```

Seed per business: `1000 Cash in Drawer`, `1010 M-Pesa Till`, `1020 Bank`,
`1100 AR – Customers`, `1200 Inventory`, `2100 AP – Suppliers`, `4000 Sales Revenue`,
`5000 COGS`, `6000 Operating Expenses`, `3000 Owner Equity`.

Every domain event (sale, payment, adjustment, expense, supplier_payment,
stock_movement-wastage) emits a journal entry. This is what lets the owner
say “show me cash position at 3pm on Tuesday” and trust it.

### 5.10 Operations

```sql
expenses (id, business_id, branch_id NULL, name, category_type: fixed|variable,
          amount, frequency, start_date, end_date NULL, active,
          include_in_cash_drawer, payment_method, receipt_s3_key, created_by)

activity_log (id, business_id, action, entity_type, entity_id,
              entity_name_snapshot, details_jsonb, performed_by,
              ip, user_agent, created_at)

out_of_stock_requests (id, business_id, item_id, requested_by_customer,
                       status, notes, created_at, fulfilled_at)

notifications (id, business_id, user_id NULL, type, payload_jsonb,
               read_at, created_at)
```

### 5.11 Indexing rules (non-obvious)

- Partial index `items (business_id, barcode) WHERE barcode IS NOT NULL AND deleted_at IS NULL`.
- GIN index on `items.search_tsv` (populated by trigger from name + variant + sku + barcode + supplier SKU).
- BRIN on `stock_movements.created_at` (append-only, huge).
- Partial index `supplier_invoices (business_id, due_date) WHERE status IN ('pending','partial','overdue')` — powers the “what do I owe?” dashboard without scans.
- Partial index `inventory_batches (item_id, received_at, expiry_date) WHERE status='active'` — powers FEFO picker.

---

## 6. User Roles and Permissions

The current system ships roles `owner | admin | cashier | superadmin` as a
code map. The rebuild promotes roles and permissions to data so tenants can
create custom roles (e.g. “stock clerk”, “manager”, “accountant”) without a
code change.

### 6.1 Default roles (seeded per business)

| Role              | Summary                                                        |
|-------------------|----------------------------------------------------------------|
| `owner`           | All permissions, only role that can manage billing + owners.   |
| `manager`         | Everything except billing and adding other managers/owners.    |
| `admin`           | Catalog + stock + purchasing + reporting (no user mgmt).       |
| `accountant`      | Read-only catalog + full AP/AR + expenses + reports + exports. |
| `stock_clerk`     | Receive stock, adjust with approval, stock-take, view items.   |
| `cashier`         | Open/close own shift, sell, request stock adjustments.         |
| `viewer`          | Read-only (auditor / investor view).                           |
| `super_admin`     | Platform role, manages tenants.                                |

### 6.2 Permission catalogue (keys, grouped)

```
auth.*          : login, change_password, reset_password
business.*      : manage_settings, manage_domains, manage_subscription
users.*         : list, create, update, deactivate, assign_role
roles.*         : list, create, update, delete
catalog.*       : items.read, items.write, categories.write, barcode.print
suppliers.*     : read, create, update, link_items, view_payables
purchasing.*    : po.create, po.send, grn.create, invoice.record, invoice.approve
inventory.*     : batches.read, adjust.request, adjust.approve, stocktake.run,
                  transfer.create, transfer.receive
pricing.*       : sell_price.set, cost_price.set, promo.manage
sales.*         : sell, void.own, void.any, refund.create, refund.approve,
                  view.own, view.all, edit.any
payments.*      : record_cash, process_mpesa, record_bank
credits.*       : account.create, debt.record, payment.record, wallet.topup,
                  loyalty.adjust, public_claim.review
finance.*       : expenses.write, supplier_payment.record, ledger.view,
                  cash_drawer.count, cash_drawer.approve
reports.*       : sales, profit, suppliers, stock, cash, tax, export
activity.*      : log.read
integrations.*  : api_keys.manage, webhooks.manage
superadmin.*    : tenants.*, domains.*, impersonate
```

A `PermissionEvaluator` bean answers `hasPermission(user, "sales.void.any")`.
REST controllers use `@PreAuthorize("hasPermission('sales.void.any')")`.

### 6.3 Hierarchical rules

- `own` vs `all` scoping (e.g. `sales.view.own` auto-filters by `user_id = current_user_id`).
- Approvals: any permission ending in `.approve` cannot be granted to the same user who holds the corresponding `.request`. The UI prevents self-approval; the service re-checks.
- Branch scoping: users can be restricted to one or more `branch_id`s. Queries are auto-filtered.

---

## 7. Stock Management Workflow

### 7.1 Inbound stock paths

**Path A — Formal PO (for structured retail and wholesale):**

```
Draft PO  ─▶ Send PO (email/PDF to supplier)
          ─▶ GRN on arrival (may be partial)
          ─▶ Record supplier invoice (can differ from PO)
          ─▶ Allocate supplier payment(s) (cash / M-Pesa / bank)
```

Each GRN line creates one `inventory_batches` row and one
`stock_movements` row of type `receipt`. `selling_prices` is suggested (auto
from margin rule) but must be confirmed by a user with `pricing.sell_price.set`.

**Path B — Raw purchase note (for groceries / open-air-market trips):**

```
Purchase trip recorded
  ─▶ Raw note: "2 crates tomatoes – 4,800 KES, 1 bag onions – 3,000 KES"
  ─▶ Breakdown (later, on arrival at shop):
       • 2 crates = 30 kg usable + 2 kg wastage, unit cost = 4,800 / 32 = 150 KES/kg
       • 1 bag    = 48 kg usable + 0,  unit cost = 62.50 KES/kg
  ─▶ Breakdown creates batches + wastage movement + AP invoice against the supplier
```

This preserves today’s `purchase → purchase_items → purchase_breakdowns`
pattern but wires it into the AP ledger and requires a supplier (no more
`supplier_name` free-text orphans).

**Path C — Opening balances / data import:**

One-off `source_type = 'opening'` batches with synthetic supplier “Opening
Balance” and zero AP posting.

### 7.2 Outbound stock paths

| Path              | Movement type    | Triggers                                   |
|-------------------|------------------|--------------------------------------------|
| Sale              | `sale`           | Batch picker (FEFO > FIFO > LIFO per item) |
| Refund            | `refund`         | Must map back to the original batch if possible |
| Wastage           | `wastage`        | Dedicated reason codes: spoilage, breakage, theft, sample, personal_use |
| Transfer to branch| `transfer_out/in`| Two movements, one per branch              |
| Adjustment        | `adjustment`     | Stock-take reconciliation                  |

### 7.3 Batch picker policy (per item)

```
pick_policy:
  1. FEFO if has_expiry = true AND any batch has expiry_date within 30 days
  2. FIFO otherwise
  3. LIFO only if business setting "cost_method = LIFO" (rare, for some wholesalers)
```

Each `SaleItem` records the batch it drew from. If a cashier manually
selects a batch at POS (e.g. damaged crate), it overrides the picker.

### 7.4 Stock-take (physical count)

```
StockTakeSession
├─ id, business_id, branch_id, started_at, started_by, status
├─ scope (whole-store | category | aisle | custom list)
├─ lines: (item_id, system_qty_snapshot, counted_qty, note, counted_by)
└─ on close:
     for each line with counted ≠ system:
       create stock_adjustment with reason=counting_error and variance details
```

Sessions are resumable and shiftable across cashiers. A partial count does
not close the store.

### 7.5 Expiry management

- Nightly job queries active batches with `expiry_date <= now() + 30 days` and emits `stock.batch.expiring_soon` events.
- Notification service raises dashboard badges and (optionally) email/WhatsApp.
- A batch with `expiry_date < now()` is auto-deactivated and its remaining qty is moved to `wastage` with reason `expired`, **only after owner confirms** (no silent write-off).

### 7.6 Low-stock triggers

- `items.min_stock_level` threshold raises a `stock.item.low_stock` event.
- If item has a primary supplier with `min_order_qty`, the system drafts a PO for that supplier (not auto-sent).

---

## 8. Sales and Purchase Flow

### 8.1 POS sale — end-to-end sequence

```
1. Cashier has open shift (else: prompt to open — with opening denominations).
2. Scan / type / search item ─▶ add to cart (client-side draft).
3. Apply modifiers: quantity, discount (permissioned), bundle pricing.
4. Press "Pay":
     a. Cashier sends POST /sales with Idempotency-Key header.
     b. Server begins DB transaction. Looks up (sale_id by idempotency_key) → if found, returns that sale (no duplicate).
     c. For each line:
        • Resolve batch via picker (or cashier-chosen batch).
        • Write sale_item (lock profit = (sell - cost) * qty).
        • Append stock_movement(sale, -qty).
        • Decrement batch.quantity_remaining; mark depleted if 0.
     d. Handle payment mix:
        • cash       → AR stays at 0, cash drawer +amount, shift.expected_closing_cash +amount.
        • mpesa      → call gateway; on confirm, post.
        • credit     → credit_transactions(debt) +amount.
        • wallet     → wallet_transactions(debit) -amount.
        • split      → each portion handled as above.
     e. Loyalty: if customer linked AND business earn_rate > 0, loyalty_transactions(earn) based on grand_total.
     f. Emit sale.completed event. Post journal entry:
        Dr 1000 Cash (or 1100 AR / 1010 Mpesa) = amount
        Cr 4000 Sales Revenue = amount
        Dr 5000 COGS = cost_total
        Cr 1200 Inventory = cost_total
     g. Commit transaction. Return receipt payload.
5. Print / share receipt (PDF + ESC/POS).
```

### 8.2 Void vs refund

- **Void** happens *same shift* before banking; reverses the sale in full. Permissioned by `sales.void.own` / `sales.void.any`.
- **Refund** happens later; creates a `refunds` row + stock movements back into the original batches where possible, and posts a reversal journal entry. Always permissioned.

### 8.3 Shift close

```
1. Compute expected_closing_cash (from sales.cash portion + opening - cash_expenses - supplier_payments_from_drawer).
2. Cashier counts and enters denominations.
3. cash_difference = actual - expected.
4. If |cash_difference| > threshold (business setting, e.g. 100 KES),
   require admin approval (balance_approval_requests).
5. Post journal: any overage → 3900 Over/Short; any shortage → 3900 Over/Short.
6. Shift marked closed. Cashier cannot sell until new shift opened.
```

### 8.4 Purchase flow (formal)

```
PO draft ─▶ Approve (if > threshold) ─▶ Send to supplier
   │                                         │
   │                                         ▼
   │                                  Goods Receipt(s) (1..N, partial allowed)
   │                                         │
   │                                         ▼
   │                                  Supplier Invoice (captured from supplier's doc)
   │                                         │
   │                                         ▼
   │                                  AP entry posted
   │                                         │
   └───────── reconcile PO vs GRN vs Invoice (3-way match) ──┘
```

A strict 3-way match (PO qty = GRN qty = Invoice qty, within tolerance) is
a business setting; default is `warn`, can be set to `block`.

### 8.5 Supplier payment flow

```
User picks supplier ─▶ sees outstanding invoices (aged buckets: 0-30 / 31-60 / 61-90 / 90+)
─▶ enters amount + method (cash from drawer / M-Pesa till / bank)
─▶ allocates to one or more invoices (or leaves as on-account credit)
─▶ posts payment + journal entry:
     Dr 2100 AP – Supplier X = amount
     Cr 1000 Cash (or 1010 Mpesa) = amount
─▶ if paid from cash drawer during shift: shift.expected_closing_cash -= amount
─▶ supplier balance and invoice status recomputed atomically
```

---

## 9. Reporting and Analytics

### 9.1 Dashboard (real-time, sub-second target)

- **Today at a glance**: sales count, revenue, gross profit, margin %, open shifts.
- **Cash position**: drawer balance (= opening + cash sales − cash expenses − supplier_cash_payments), M-Pesa balance, bank balance, AR balance, AP balance.
- **Inventory alerts**: low-stock count, expiring-30d count, deactivated-batches count.
- **Supplier alerts**: overdue bills total, due-this-week total.
- **Top 5 items (qty + revenue)** and **bottom 5 SKUs (slow-movers)**.
- **Approval inbox**: stock adjustments, void requests, public-claim reviews, balance variances.

### 9.2 Sales reports

- **Sales register**: by date range, cashier, branch, payment method, item type, category, hour-of-day, day-of-week.
- **Profit report**: by item, category, supplier, branch, batch. Drill-down path: Type → Category → Item → Variant → Batch → Transaction.
- **Basket analysis**: most frequent 2-item, 3-item combos.
- **Returns & voids**: rate, top offenders, top cashiers.
- **Tax summary**: output VAT by rate band, input VAT (from supplier invoices), net payable.

### 9.3 Supplier reports (a first-class section — currently weak)

- **Spend by supplier**: period-over-period, split by item type.
- **On-time delivery %**: (GRNs within lead_time_days) / total GRNs.
- **Quality score**: wastage qty / received qty per supplier per period.
- **Price competitiveness**: per SKU, rank of each supplier by average unit cost over last 90 days (upgrade of today’s “last price only” comparison).
- **Payables ageing**: 0–30 / 31–60 / 61–90 / 90+.
- **Single-source risk**: items with exactly one active primary supplier.
- **Supplier P&L**: revenue generated by SKUs originally sourced from supplier X, minus COGS from supplier X.

### 9.4 Stock reports

- Current stock by branch, by category, with valuation (FIFO cost).
- Stock movement history per item (append-only ledger view).
- Stock-take variances (all sessions, with reason breakdown).
- Expiry pipeline: 7d / 30d / 90d windows.
- Shrinkage: wastage + theft + counting_error as % of receipts.

### 9.5 Financial reports

- Daily cash summary (auto-generated at shift close).
- P&L statement (simple) by period.
- Balance sheet (simple) as of date.
- Expense breakdown by category and by payment method.
- M-Pesa statement reconciliation (imported statement vs system-recorded mpesa txns).

### 9.6 Implementation rules

- **No report re-computes raw sale_items on the fly past 90 days.** Precompute into:
  - `mv_sales_daily(business_id, branch_id, day, item_id, qty, revenue, cost, profit)`
  - `mv_supplier_monthly(business_id, supplier_id, month, spend, qty, invoice_count, wastage_qty)`
  - `mv_inventory_snapshot(business_id, branch_id, item_id, qty, value, captured_at)` (daily at 00:05 local)
- Materialized views are refreshed by a Spring `@Scheduled` job; “live” dashboard falls back to a transactional query on today only.
- All report endpoints accept `?format=json|csv|xlsx|pdf` and return with a short-lived S3 URL for large exports.
- Heavy reports go through a job queue (Redis + a worker) and email the user when ready.

---

## 10. Admin Controls and Configurations

### 10.1 Business settings (UI-editable)

```
Identity:        name, logo, currency, timezone, country, tax PIN
Receipts:        header lines, footer lines, print width (58mm/80mm), footer QR
Numbering:       receipt prefix, invoice prefix, next numbers
Pricing:         default margin %, rounding rule (0.01 / 0.50 / 1.00)
Tax:             tax rates, default rate, prices include tax? yes/no
Shifts:          require balance approval over variance threshold (KES)
Stock:           cost method (FIFO|LIFO|WAC), allow negative stock (per item type),
                 low-stock email to whom, stocktake requires approval
Suppliers:       default credit terms, require PO over threshold,
                 require 3-way match (off|warn|block)
Credit:          credit limit per customer default, SMS reminder schedule
Loyalty:         points per currency unit, minimum redemption, expiry days
Integrations:    M-Pesa shortcodes, Pesapal keys, SMS gateway keys, email SMTP
Security:        session length, password policy, 2FA required for roles
Branding:        primary colour, dark-mode default
Branches:        list of branches, default branch
Product Types:   user-defined (e.g. grocery, retail, service, rental)
```

### 10.2 Super-admin controls

- Tenant CRUD (suspend / restore / delete with 30-day soft grace period).
- Domain CRUD + DNS verification (TXT record challenge).
- Subscription management (tier, renewal date, grace).
- Impersonation (audit-logged, time-boxed token, banner in UI).
- Platform-wide feature flags.
- Global super-admin activity feed.

### 10.3 Feature flags (per business)

`multi_branch`, `expiry_tracking`, `loyalty`, `mpesa_stk`, `purchase_orders_required`,
`split_payments`, `customer_wallet`, `public_credit_link`, `sticker_printing`,
`barcode_scanner`, `tax_engine`, `offline_pos`.

Roll these out per tenant to avoid forcing complexity on small shops.

---

## 11. Java Project Structure (Spring Boot 3, multi-module)

Gradle root with settings:

```
kiosk/
├─ settings.gradle.kts
├─ build.gradle.kts
├─ buildSrc/                              -- common build logic
├─ config/                                -- checkstyle, spotless, errorprone
├─ docker/                                -- dev compose, Dockerfiles
├─ docs/                                  -- ADRs, diagrams, OpenAPI
└─ modules/
   ├─ platform-bom/                       -- dep versions catalog
   ├─ platform-core/                      -- shared errors, ids, tenant context, money, result types
   ├─ platform-web/                       -- error handlers, OpenAPI config, CORS, ratelimit
   ├─ platform-security/                  -- JWT, PasswordEncoder, AuditorAware, RLS setter
   ├─ platform-persistence/               -- base entities, UUIDv7, soft-delete, auditing
   ├─ platform-events/                    -- outbox pattern, Kafka/Redis adapters
   ├─ platform-storage/                   -- S3 wrapper, signed URLs
   ├─ platform-pdf/                       -- OpenPDF thin wrapper, receipt templates
   ├─ identity/
   │   ├─ identity-api/                   -- public interfaces, DTOs, events
   │   ├─ identity-domain/                -- aggregates, domain services
   │   ├─ identity-infra/                 -- JPA entities, repositories, adapters
   │   └─ identity-web/                   -- @RestController (REST)
   ├─ tenancy/         (same 4-fold split)
   ├─ catalog/
   ├─ suppliers/
   ├─ purchasing/
   ├─ inventory/
   ├─ pricing/
   ├─ sales/
   ├─ payments/
   ├─ credits/
   ├─ finance/
   ├─ reporting/
   ├─ auditing/
   ├─ integrations/
   ├─ notifications/
   ├─ exports/
   ├─ sync/                               -- local↔cloud outbox sync, conflict policies (hybrid mode)
   ├─ platform-desktop/                   -- jpackage config, OS service wrappers, tray UI, self-updater
   └─ app-bootstrap/                      -- Spring Boot application main + wiring profile
```

### 11.1 Package layout within a module (identity example)

```
ke.kiosk.identity
├─ api/
│   ├─ UserApi.java                       -- façade other modules talk to
│   ├─ dto/...                            -- DTOs used on the API
│   └─ events/UserCreated.java            -- domain events
├─ domain/
│   ├─ model/User.java                    -- rich aggregate (no @Entity)
│   ├─ model/Role.java
│   ├─ service/AuthenticationService.java
│   └─ policy/PasswordPolicy.java
├─ application/
│   ├─ CreateUserCommand.java
│   ├─ CreateUserHandler.java
│   └─ UserApplicationService.java        -- implements UserApi
├─ infrastructure/
│   ├─ persistence/UserEntity.java        -- @Entity
│   ├─ persistence/UserRepository.java
│   ├─ persistence/UserMapper.java        -- MapStruct domain ↔ entity
│   └─ security/JwtAuthenticationFilter.java
└─ web/
    ├─ UserController.java                -- @RestController
    └─ mapper/UserDtoMapper.java
```

### 11.2 Cross-module rules (enforced with ArchUnit tests)

- `web` depends on `application` only (never `infrastructure`).
- `application` depends on `domain` and `api`, never on other modules' `infrastructure`.
- Inter-module: use only `XxxApi` façade + DTOs in `api`.
- `domain` has no framework imports (pure Java).
- Migrations live in the module that owns the table (`modules/suppliers/suppliers-infra/src/main/resources/db/migration/V3__supplier_products.sql`). Flyway scans all locations.

### 11.3 Configuration profiles

Profiles are orthogonal: pick one **environment** profile and one **mode** profile.

- Environment: `dev`, `test` (Testcontainers), `staging`, `prod`.
- Mode: `cloud` (default), `local` (on-prem, internet-independent), `hybrid`
  (local with cloud mirror).

Profiles swap *adapters* only — domain and application code are identical.
Environment variables only; no secrets in source. In `local` mode, secrets
live in an OS-protected config file (`%PROGRAMDATA%\Kiosk\config.yml` on
Windows, `/etc/kiosk/config.yml` on Linux, `~/Library/Application Support/Kiosk/`
on macOS) with filesystem ACLs restricting read access to the service user.

### 11.4 Testing strategy

- Unit tests on `domain` (pure, no DB).
- Slice tests on `infrastructure` (`@DataJpaTest` with Testcontainers Postgres).
- Integration tests on `web` (`@SpringBootTest` with RestAssured, full wiring).
- Contract tests for external adapters (M-Pesa, Pesapal) with WireMock.
- ArchUnit tests for layering + package naming.
- Mutation tests (PITest) on `domain` (target ≥ 70%).
- Load test the hot paths (sale POST, catalog search) with Gatling; budget 200ms p95.

### 11.5 Observability

- Structured JSON logs (Logstash encoder) with `business_id`, `user_id`, `request_id`.
- Metrics per endpoint + per domain event count.
- Tracing: every inbound request gets a trace id; OpenTelemetry propagates to Kafka + DB.
- Health: `/actuator/health` (liveness), `/actuator/ready` (readiness, incl. DB + migrations).

---

## 12. Development Roadmap (Phase 1 → GA)

Time estimates assume 1 senior full-stack Java dev + 1 frontend dev + 1
part-time designer/PM. Double for solo.

### Phase 0 — Foundations (Week 0–1)

- Repo, Gradle multi-module skeleton, CI pipeline (test + build + container).
- Platform modules: `core`, `web`, `security`, `persistence`, `events`, `storage`.
- Postgres + Flyway + Testcontainers running locally.
- ArchUnit and Spotless gates wired.
- Base JWT auth, tenant resolver, RLS skeleton with dummy business.

**Exit criteria:** `./gradlew check` passes; a `GET /health` returns 200 inside a Docker compose stack.

### Phase 1 — Identity, Tenancy, Catalog (Week 2–4)

- Businesses, branches, users, roles, permissions.
- Login (email+password + PIN), refresh tokens, API keys.
- Domains → business resolver; super-admin tenant CRUD.
- Items (+ variants), categories, aisles, item types, barcodes, images, FTS.
- Admin UI: business settings, user CRUD, product CRUD.

**Exit criteria:** a super-admin can create a tenant; an owner can create users, categories, and items; can search items by name or barcode.

### Phase 2 — Suppliers & Purchasing (Week 5–7)

- Supplier aggregate, contacts, payment profile, credit terms.
- Supplier ↔ item links with primary-supplier invariant.
- Path B (raw notes + breakdown) first — this is the kiosk-dominant case.
- Path A (formal PO → GRN → invoice) next.
- Supplier invoices + payments + AP aging + journal postings.
- Supplier reports: spend, competitiveness (90-day avg), single-source risk.

**Exit criteria:** Owner can onboard a supplier, receive goods, record an invoice, pay it partially, and see the AP age correctly.

### Phase 3 — Inventory + Pricing (Week 8–9)

- Inventory batches, `stock_movements` append-only ledger.
- FEFO/FIFO picker.
- Stock-take sessions with approvals.
- Selling-price history + cost-price history + auto-suggest sell price from cost + margin.
- Stock transfers between branches.

**Exit criteria:** Stock valuation matches sum(batch * unit_cost) across all flows; a wastage write-off reduces both stock and inventory asset.

### Phase 4 — POS Core (Week 10–12)

- Shifts (open/close with denominations, balance approvals).
- POS cart UI (PWA), barcode scan, quick-keys.
- Sale endpoint with idempotency, FIFO/FEFO integration, split payments.
- Receipts (PDF + ESC/POS print), email receipt.
- Void / refund flows with permission gating.
- Offline support: write sales to IndexedDB with outbox, sync when online (maintain order via idempotency key + monotonic client seq).

**Exit criteria:** a cashier can complete 100 sales/hour on a 4G connection; server-side dedupe proven via 10× retry test.

### Phase 5 — Customers, Credit, Wallet, Loyalty (Week 13–14)

- Customer accounts unified (today they live inside credit_accounts).
- Credit (debt), wallet (prepaid), loyalty (points) — three separate ledgers.
- M-Pesa STK push (Daraja and/or Pesapal), public credit link with claim review.
- SMS + email reminders for overdue credit.

**Exit criteria:** End-to-end: customer buys on credit → self-pays via STK → amount applies after admin approves claim → journal entries balance.

### Phase 6 — Expenses, Cash Drawer, Finance Reports (Week 15)

- Expenses with recurrence, payment method, include_in_drawer flag.
- Cash drawer daily summary auto-generated at close.
- P&L and simple balance-sheet views.

**Exit criteria:** Owner can answer “did I make money today?” in one click.

### Phase 7 — Reporting + Analytics (Week 16–18)

- Materialized views for daily sales, monthly supplier, daily inventory (scheduled full `REFRESH MATERIALIZED VIEW CONCURRENTLY` in v1; outbox-driven incremental refresh deferred until a transactional outbox ships — see `docs/PHASE_7_PLAN.md`).
- Dashboard query **p95 baseline** published at a documented reference seed; CI fail-on-regression deferred to Phase 11.
- Export engine: CSV/XLSX for the v1 report set; PDF for a locked subset (P&L, balance sheet, daily cash summary); async for large.
- Notification pipeline (overdue AP, overdue AR, shift variance required; low-stock and expiring batches as stretch / Phase 7.1 if upstream emitters not in place).

**Exit criteria:** Phase 6 close-out (pulse + simple P&L/BS) green, **plus** the **six** Phase 7 v1 canonical reports green in CI; the remaining **four** reports complete in Phase 7.1 in the same release or a stated follow-on milestone (see `docs/PHASE_7_PLAN.md` ADR).

### Phase 8 — Integrations & Hardening (Week 18–19)

- External HTTP API + API keys + scopes + rate limits.
- Webhooks out (sale.completed, invoice.overdue, stock.low_stock).
- Data import (CSV of items, suppliers, opening stock).
- Data export backup job (daily encrypted dump to S3).
- GDPR-style data export / deletion per tenant.

### Phase 9 — Multi-branch + Offline + PWA Polish (Week 20–22)

- Branch switcher, branch-scoped sales/inventory/reports.
- Stock transfers with in-transit state.
- Offline PWA cashier with conflict resolution (last-write loses if server has newer).
- Install-as-app, scanner via camera, ESC/POS Bluetooth printer support.

### Phase 10 — Local / On-Prem Deployment (Week 23–24)

Deliver the `local` and `hybrid` profiles end-to-end. See §15 for the full
design; this phase operationalises it.

- `platform-desktop` module: `jpackage` pipelines producing signed
  Windows `.msi`, macOS `.pkg`, Linux `.deb` + `.rpm`. CI matrix job.
- Bundled PostgreSQL (portable / initdb-on-first-run), Caffeine cache,
  filesystem storage adapter; Redis/Kafka/MinIO adapters behind profile guards.
- Local activation / licensing: keyed JWT good for N offline days, renew on
  first online ping; hard grace period with prominent UI banner.
- `sync` module: durable outbox → cloud replay (hybrid mode), conflict
  resolution policies (see §15.6), reconciliation reports for the owner.
- Installer wizard: data-directory choice, admin user bootstrap, printer
  detection, firewall-rule creation, LAN hostname + self-signed cert.
- System-tray UI (Windows/macOS) + `systemd` unit (Linux): start/stop,
  status, open admin URL, run backup, check for updates.
- Nightly local backup to configured path (USB drive or network share) +
  encrypted upload to cloud when online.
- Upgrade path: in-place update with rollback; manual USB update path for
  shops that never connect.

**Exit criteria:** a clean PC with the installer produces a working POS
reachable at `https://kiosk.local/` from another LAN device inside 10
minutes, with no internet connection. Yanking the network cable during a
busy hour does not affect sales, printing, or reports.

### Phase 11 — Beta, Perf, GA (Week 25–28)

- Load tests (Gatling): 100 concurrent shops × 1 sale/sec.
- Security review: OWASP ASVS L2 checklist.
- Penetration test (external).
- Final UAT with 3 pilot shops.
- Documentation: developer docs, API docs (OpenAPI), user guides per role.

**Exit criteria for GA:** Zero P1 bugs open for 14 days; p99 latency < 500 ms on hot paths; backup/restore rehearsed; a new tenant can self-onboard end-to-end in under 10 minutes.

---

## 13. Improvements Over the Current System

Each item ties directly to a weakness in §2.3.

1. **Suppliers become mandatory everywhere.** Fixes weakness #1.
2. **All multi-row operations wrapped in `@Transactional` with explicit isolation (`READ_COMMITTED`), and an outbox pattern for events.** Fixes #2.
3. **No runtime DDL.** Flyway migrations run at startup; failing migrations fail the container health check. Fixes #3.
4. **Cost of goods sold is the value written on the `sale_items` row at sale time (locked).** Fallback subqueries only on historic rows where the migration is still running. Fixes #4.
5. **`stock_movements` append-only ledger is the single source of truth.** `items.current_stock` is a cached projection, recomputable at any time. Fixes #5.
6. **Light double-entry ledger (journal_entries / journal_lines)** glues sales, expenses, supplier payments, wastage, and loyalty into a consistent financial story. Fixes #6.
7. **Proper PO → GRN → Invoice → Payment with 3-way match.** Raw-note path retained for market trips. Fixes #7.
8. **Dedicated `item_types` table per business**, not JSON in settings. Migrations become safer and reports can JOIN. Fixes #8.
9. **FEFO for items with expiry**, FIFO for the rest, LIFO only when business opts in. Wastage from expiry is explicit and requires user confirmation. Fixes #9.
10. **Canonical customer identity** via `customers` + `customer_phones`. Phones normalised at write-time. Fixes #10.
11. **Roles + permissions as data**, custom roles supported, `@PreAuthorize` everywhere. Fixes #11.
12. **Idempotency keys** on every mutating endpoint (`Idempotency-Key` HTTP header). Offline client generates client-side UUIDv7. Fixes #12.
13. **First-class branches** with scoped stock, scoped sales, scoped reports, branch-to-branch transfers. Fixes #13.
14. **Materialized views + scheduled refresh** for heavy reports, with on-demand fallback for today's data. Fixes #14.
15. **Activity log surfaced** as a tab on every entity detail page and as a global audit trail for owner/super-admin. Fixes #15.

Additional, non-parity improvements:

- **Strong typing everywhere.** Enums, `Money`, `Quantity`, `Percent` value objects in `platform-core`. No raw `double` for money.
- **Input validation at the edge.** Jakarta Validation on DTOs + domain assertions in aggregates.
- **Pluggable payment gateways** via a `PaymentGateway` interface (Daraja, Pesapal, Stripe, Manual).
- **Pluggable print drivers** (PDF / ESC-POS / HTML-to-printer) via `ReceiptRenderer` interface.
- **OpenAPI 3.1** generated from controllers with examples; stable public API under `/api/v1/...`.
- **Feature flags per tenant** so small shops aren't forced into complexity (purchase orders, multi-branch, tax engine).
- **Data retention policies**: old activity logs archived to S3 after 1 year; restorable on demand.
- **GDPR / Kenya DPA** tooling: export user/customer data; anonymise a customer's PII on request while keeping financial records.

---

## 14. Risks, Edge Cases & Non-Negotiables

Things that must not be missed. Items marked **BLOCKER** are pre-GA.

### 14.1 Concurrency and correctness — **BLOCKER**

- Two cashiers selling the same last 2 kg of tomatoes simultaneously must not both succeed. Use SELECT … FOR UPDATE on the batch row, or application-level optimistic locking with retry.
- A sale whose HTTP response never reached the cashier must not double when she retries. Idempotency key required on every POST /sales.
- A shift close racing against in-flight sale posts must wait: the sale endpoint checks the shift is still `open` inside the transaction.
- Transferring stock between branches must be atomic: both sides post in the same transaction.

### 14.2 Numeric precision — **BLOCKER**

- All money uses `BigDecimal` with scale 2 and `RoundingMode.HALF_UP`.
- All quantities use `BigDecimal` with scale up to 4.
- Rounding is done once at the line level (configurable), never ad-hoc in reports.
- Currency conversion is out of scope for v1 (single-currency per tenant).

### 14.3 Multi-tenant isolation — **BLOCKER**

- PostgreSQL Row-Level Security enabled on every tenant table. Policy: `business_id = current_setting('app.business_id')::uuid`.
- Every DB connection from the app sets the session variable from the JWT after authentication, before any query.
- Integration test: log in as tenant A, craft a request with tenant B's item ID — must 404, not 200.

### 14.4 Auth edges

- Account lockout after N failed logins; admin unlock flow.
- PIN must be unique per business (DB unique index).
- JWT access token 15 min; refresh token rotated on use; refresh stored hashed.
- Super-admin impersonation uses a short-lived (15 min) token with `impersonated_by` claim; all actions logged as impersonation.
- Password reset tokens single-use, 1-hour expiry, hashed in DB.

### 14.5 Inventory edges

- **Negative stock**: allowed per item_type setting (today's codebase permits it; must stay configurable).
- **Batch ran out mid-sale line**: the picker must split across multiple batches (today's FIFO logic does this; preserve).
- **Refund of a sale whose batch was since depleted/deactivated**: create a new batch at the refund's unit_cost, source_type=`refund_return`.
- **Wastage larger than batch remaining**: must block with a clear error.
- **Stock-take with an item that has no prior batches**: creates a synthetic opening batch at a user-supplied cost.

### 14.6 Purchasing edges

- **Supplier invoice before GRN** (“goods still coming”): allowed, but AP balance shows `pending_delivery` badge until matched.
- **GRN without invoice** (“invoice later”): AP accrues against an “unbilled receipts” account until invoice arrives.
- **Invoice amount ≠ GRN cost**: records the variance in a `purchase_price_variance` account; alerts owner.
- **Extra costs (transport, tips)**: prorate across lines by value by default, with override. This matches today's `purchases.extra_costs` but spread properly into unit costs.

### 14.7 Sales / POS edges

- **Scanned barcode matches multiple items**: prompt disambiguation instead of picking the first.
- **Bundle pricing**: price override per bundle_qty; show “2 left until next bundle tier” hint.
- **Weighed items**: UX must support scale input; stored as `unit_type=kg/g` with decimal qty.
- **Item deactivated mid-cart**: cart refresh shows a warning; cannot complete sale on inactive item.
- **Shift span over midnight / over date boundary**: reports key off `sale_at` not `shift.started_at`.
- **Refund to wallet**: supported; must credit wallet, not drawer.

### 14.8 Credits / wallets / loyalty edges

- Customer phone change: merges two accounts on admin approval only.
- Wallet balance can never go negative (constraint + check).
- Loyalty redemption uses integer points; display in a user-chosen currency equivalent.
- Public payment claim approved twice: second approval is a no-op due to idempotency.
- Overpayment at cashier: excess goes to customer wallet (already in today's system) — preserve.

### 14.9 Data edges

- Merging categories / suppliers / customers: update all FK usages in one transaction; leave activity-log snapshot rows alone (they hold the old name by design).
- Deleting: soft only for operational rows (items, suppliers, customers); hard only for draft POs and draft sales.
- Import: dry-run mode + error report CSV before commit.
- Timezone: all timestamps UTC in DB; presented in `business.timezone`; reports that group by “day” use business timezone.

### 14.10 Offline edges (PWA cashier)

- Clock skew between device and server: idempotency key preserves ordering; server rewrites `sale_at` to server time if delta > 1 hour and records client time in metadata.
- Stock displayed offline is a snapshot; server will reject sale if FEFO batches are out of stock at sync time → cashier must reconcile.
- Pricing offline is a cached `current_price`; a price change between cart and sync may shift profit slightly — acceptable and logged.

### 14.11 Security / compliance

- Rate-limit login (5/min/IP), POS sale (30/min/user), reports (10/min/user).
- CSRF protection for cookie-based admin sessions.
- CORS locked to configured domains + the wildcard for API-key callers.
- Backups: daily encrypted pg_dump to S3; restore drilled monthly.
- Secrets: AWS Secrets Manager / SSM in prod; `.env` for dev only.
- Audit log is append-only; DB role has no UPDATE/DELETE on it.
- PII: phone + name only; store hashed phone fingerprint for lookups to reduce leak blast radius (future).

### 14.12 UX non-negotiables

- Keyboard-first POS: every action a shortcut; works on a Bluetooth scanner with an Enter suffix.
- Receipts must render on 58 mm and 80 mm thermal printers, and as A6 PDF.
- Dark mode + large-font mode for cashier UI.
- English + Swahili labels for operator-facing screens.
- All money on the dashboard shows the currency symbol + 2 decimals.

### 14.13 Local-mode edges — **BLOCKER**

- **No NTP:** the server PC clock can drift by minutes/hours. On every boot
  and every 6 h, check OS clock against last-known-good timestamp in the DB
  (`MAX(sale_at)`). If it moves *backwards* more than 5 min, refuse to accept
  sales until an admin confirms — prevents accidental back-dating.
- **Power loss mid-sale:** PostgreSQL `fsync=on` (never weaken), WAL replays
  on startup; the idempotency key guarantees the cashier can safely retry.
  Reject unknown sale IDs on restart (they may be stuck in the outbox).
- **Disk full on the back-office PC:** monitoring task writes a marker file
  every minute; when free space < 2 GB, the admin UI shows a red banner and
  auto-disables image uploads + report PDF export (sales continue).
- **USB printer unplugged mid-receipt:** sale is already committed; receipt
  is queued for re-print; cashier sees a non-blocking toast.
- **Hybrid mode clock drift between shops:** each outbox event carries the
  server's monotonic sequence + server wall time; cloud orders by monotonic
  seq within business, not by wall time.
- **Licensing grace period expired while offline:** the POS enters a
  *read-only “grace-over” mode* — existing sales can still be refunded and
  reports still load, but new sales are blocked until an activation key is
  re-entered (manually, from a support email or SMS).

---

## 15. Local / Offline-First Deployment

The system is explicitly designed so a shop with **zero internet** can run
every core workflow on one PC. This section is the complete blueprint.

### 15.1 Why this matters

Most Kenyan kiosks and wholesalers have intermittent ISP service, data-bundle
outages, and frequent power cuts with mobile-tethered connectivity. A POS
that depends on the internet to open a shift, record a sale, or print a
receipt is unusable for them. The current TypeScript system fails this test
(it requires the cloud Turso DB to be reachable for every mutation).

### 15.2 Deployment modes

Exactly three, picked at install time and changeable later:

| Mode       | Server lives on          | Needs internet? | Sync to cloud?          | Who picks it |
|------------|--------------------------|-----------------|-------------------------|--------------|
| `cloud`    | managed Postgres + ECS   | yes (for admin) | n/a                     | SaaS tenants |
| `local`    | the shop's back-office PC | **no**          | no (manual export only) | traditional shops, unstable ISP, privacy-first owners |
| `hybrid`   | the shop's back-office PC | optional        | **yes** — outbox → cloud when online | owners who want remote dashboards + off-site backup |

All three ship from a single Git tag, single Gradle build, single JAR (plus
OS-specific installer in local/hybrid). A tenant can migrate `local → hybrid`
(enable cloud mirror) or `hybrid → cloud` (promote the cloud copy to
authoritative) without schema changes.

### 15.3 Local stack (what actually runs on the PC)

```
┌─────────────────────────── Shop PC (Windows/macOS/Linux) ───────────────────────────┐
│                                                                                      │
│   ┌─────────────────────────── Kiosk Service (single JAR) ─────────────────────────┐ │
│   │                                                                                │ │
│   │   Spring Boot (profile=local) ──┬── Caffeine in-process cache                  │ │
│   │       │                          ├── In-JVM event bus + DB outbox              │ │
│   │       │                          ├── LocalStorageAdapter → $DATA_DIR/storage   │ │
│   │       │                          └── ManualGateway (payments queued offline)   │ │
│   │       │                                                                        │ │
│   │       └── embedded Tomcat  →  https://0.0.0.0:8443  (self-signed LAN cert)     │ │
│   │                                                                                │ │
│   └────────────────────────────────────────────┬───────────────────────────────────┘ │
│                                                │ localhost / LAN                     │
│   ┌──────────── PostgreSQL 16 (bundled) ──────┴────────── $DATA_DIR/pgdata ────┐    │
│   │  fsync=on, WAL, Flyway migrations on service startup                        │    │
│   └──────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                      │
│   ┌─────────── PWA (admin + cashier, served as static assets from the JAR) ──────┐  │
│   │      https://kiosk.local/  (same-origin to the API)                          │  │
│   └───────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                      │
│   Peripherals: ESC/POS printer (USB / LAN / BT), USB barcode scanner (HID),          │
│                USB scale (serial), cash drawer (RJ-11 via printer).                  │
└──────────────────────────────────────────────────────────────────────────────────────┘

           ↑                                              LAN (no internet)
           │ cashier laptops / tablets browse to the PC's IP or `kiosk.local`
```

Key choices:

- **Bundled PostgreSQL, not SQLite / H2.** The schema in §5 uses RLS, partial
  indexes, `jsonb`, `TIMESTAMPTZ`, `pg_trgm`, materialized views, and
  partitioning. Forking to SQLite would be a second codebase. Instead, the
  installer drops `postgresql-16-portable` next to the JAR, runs `initdb` on
  first boot, and manages the cluster as a child process of the Kiosk
  service. A developer who does not care about portability can point `local`
  at any existing Postgres they already run.
- **Single JAR with embedded Tomcat**, served over HTTPS on `0.0.0.0:8443`
  (configurable). The installer adds a Windows Firewall / `ufw` rule.
- **mDNS hostname** (`kiosk.local`) advertised via JmDNS so cashier devices
  on the same Wi-Fi can reach it without knowing its IP.
- **PWA served from the JAR** as classpath static assets, so there is *no*
  separate `next dev` / frontend origin to configure. Same-origin ⇒ no CORS
  headaches.
- **No Redis, no Kafka, no MinIO**, no external search engine. Caffeine +
  in-JVM events + filesystem + Postgres FTS cover a single-shop load.

### 15.4 Online features → local fallbacks

| Feature                              | Needs net? | Local behaviour                                                                 |
|--------------------------------------|------------|---------------------------------------------------------------------------------|
| Sales / refunds / voids              | no         | All local; FIFO/FEFO runs against local batches.                                |
| Purchases / GRN / supplier invoices  | no         | All local.                                                                      |
| Receipts (thermal + PDF)             | no         | OpenPDF + ESC/POS; USB/LAN/BT printers.                                         |
| Barcode scan / weighing              | no         | HID / serial.                                                                   |
| Reports / dashboards                 | no         | Materialized views refresh on a local Quartz scheduler.                         |
| Admin UI / user management           | no         | Served from the PWA, same JAR.                                                  |
| M-Pesa STK push                      | yes        | Gateway enters *deferred* state; cashier uses **Manual-Reference** flow (enters M-Pesa receipt code from phone, admin reconciles later against the Daraja statement). When internet returns, pending STK intents retry via outbox. |
| SMS reminders / receipts             | yes        | Queued in `outbound_messages`; sent when online. Optional `gammu-smsd` + USB GSM dongle for truly offline SMS. |
| Email receipts / reports             | yes        | Queued in `outbound_messages`; delivered when online via SMTP.                  |
| Supplier share links (public URL)    | yes        | Short-lived token generated locally; works only when supplier can reach the box (LAN or tunneled cloud in hybrid). |
| Pesapal / Stripe webhooks in         | yes        | Not applicable in local mode; disabled on `local` profile.                      |
| Cloud backup                         | yes        | Nightly `pg_dump` always runs locally; *upload* step queued for when online.    |
| Auto-update                          | optional   | When online, fetches + verifies signed update; when offline, updates via USB.   |
| Maps / currency rates / tax tables   | yes        | Bundled snapshots shipped with the installer; admin can import newer snapshots. |

Core principle: **no business workflow blocks on the network**. Network
features are always queued through the outbox and retried.

### 15.5 Outbox and sync (hybrid mode)

The same `domain_events` outbox table already designed for the cloud build
(§11 `platform-events`) is what drives local → cloud replication:

```
┌────────────────┐  append   ┌──────────────┐   sync worker    ┌─────────────┐
│  write model   │──────────▶│ domain_events │─────────────────▶│  cloud API  │
│ (sale, PO, …)  │ in txn    │  (outbox)    │  HTTPS + JWT      │  /v1/sync   │
└────────────────┘           └──────────────┘  when online      └─────────────┘
                                      │
                                      │ ack / cursor
                                      ▼
                              business_sync_cursor
```

Rules:

- Events are written **inside the same DB transaction** as the business
  change — no dual-write. If the transaction rolls back, no event leaks out.
- The sync worker ships events in monotonic sequence order per business.
  The cloud endpoint is strictly idempotent keyed on `(business_id, event_id)`.
- Cursor lives in `business_sync_cursor(business_id, last_event_seq,
  last_acked_at, status)`. A single row per tenant-mode pair.
- Events carry a **schema version**; cloud tolerates old versions during
  rolling upgrades.
- Back-pressure: if cloud is down for > 7 days, admin UI shows a warning
  and the outbox auto-compacts non-critical event types older than 30 days
  (e.g. `pricing.viewed`) while preserving all financial events.

### 15.6 Conflict resolution (hybrid mode)

Local is **authoritative** for all write paths except three, which the cloud
owns:

1. **User admin** (create / disable users, change roles). Cloud wins to
   prevent a stolen PC from granting itself access.
2. **Licensing / subscription state.** Cloud wins.
3. **Global catalog** (if a tenant opts in to shared product templates).

For everything else:

- **Sales, stock movements, journal entries** — append-only, so there is no
  conflict. Cloud just replays.
- **Items, suppliers, customers, prices** — last-writer-wins keyed on
  `updated_at` and `event_seq`. If the cloud also edited the same row while
  the local box was offline, the cloud diff is flagged into a
  `sync_conflict` table for admin review; the local value stays active
  until the admin picks a resolution.
- **Deletes** use tombstones (`deleted_at`) + a cloud-acknowledged flag so a
  re-appearing offline box does not “resurrect” a deleted record.

### 15.7 Networking and discovery on the LAN

- Server binds on `0.0.0.0` by default; firewall opens only the configured
  port (`8443`).
- mDNS broadcast advertises `kiosk.local` (JmDNS + `ServiceInfo` for
  `_https._tcp`).
- Self-signed root CA generated at install time, exported to
  `$DATA_DIR/certs/kiosk-root.pem`. The installer prints a one-pager with
  steps to install this cert on cashier laptops / tablets (Android + iOS
  + Windows + macOS). Until then, browsers show the standard self-signed
  warning — acceptable on a private LAN.
- Optional: an admin with a domain name can point a LAN DNS entry (e.g.
  pfSense `host override`) to the PC and use Let's Encrypt DNS-01 to get a
  trusted certificate that works offline.
- Rate-limits and CSRF behave exactly as cloud; WebSocket subscriptions for
  real-time cashier sync work over the same LAN origin.

### 15.8 Licensing and activation (offline-friendly)

- At install time, the admin enters an **activation key** (signed JWT issued
  by the vendor). It carries: `tenant_id`, `issued_at`, `hard_expiry`,
  `offline_grace_days`, `feature_flags`.
- The service verifies the key against a bundled public key. No phone-home
  required.
- On *first* connection to the internet after install, the service does a
  single call to the vendor's activation endpoint to register the hardware
  fingerprint. Subsequent online pings (opportunistic, once per 24 h) refresh
  `offline_grace_days`. **Nothing in the POS blocks on this call.**
- If `now > hard_expiry`: read-only grace mode (see §14.13).
- Vendor support can issue a **rescue key** by email/SMS that the admin
  pastes in — unlocks for 30 extra days with no internet.

### 15.9 Backups and disaster recovery

- Nightly `pg_dump` → `$DATA_DIR/backups/YYYY-MM-DD.sql.zst` (zstd + AES-256
  with a tenant-supplied passphrase). Retention: 30 daily + 12 monthly.
- Configurable backup destination: local disk, attached USB drive, SMB/NAS
  path on the LAN. A watchdog verifies the last backup is < 30 h old and
  raises an admin banner otherwise.
- Restore is a single admin action: pick a backup → service stops accepting
  writes → `pg_restore` into a fresh cluster → service resumes. Integration
  test runs this on every release.
- Hybrid mode additionally streams WAL to the cloud via `pg_receivewal` over
  TLS, so cloud-side PITR is possible.
- Export to portable CSV/Excel/JSON is always available for owner-initiated
  migration to any other system.

### 15.10 Updates (online + offline paths)

- **Online update**: service fetches `https://updates.kiosk.ke/manifest.json`
  (signed), downloads the new JAR + PostgreSQL delta scripts, verifies
  signature, swaps the JAR, runs Flyway, restarts. Rollback: keeps the
  previous JAR + pg basebackup for 7 days.
- **USB update**: vendor ships a `.kioskpack` file (ZIP with signed
  manifest). Admin pastes in a support code + points the installer at the
  file. Same signature verification, same Flyway run, same rollback.
- Migrations that require a downtime window run under a maintenance banner
  with an estimated duration shown to the admin.

### 15.11 Hardware requirements (local single-PC)

| Shop size                 | Min spec                                                                 |
|---------------------------|--------------------------------------------------------------------------|
| Up to 3 cashiers, ≤ 2000 SKUs | Intel i3 / equivalent, 8 GB RAM, 256 GB SSD, Windows 10/11 or Ubuntu 22.04 |
| Up to 8 cashiers, ≤ 10 000 SKUs | i5, 16 GB RAM, 512 GB SSD, dedicated back-office PC (not the cashier's) |
| Wholesaler, ≤ 50 000 SKUs | i7 or Ryzen 7, 32 GB RAM, 1 TB NVMe, UPS, daily NAS backup              |

Additionally: a UPS of at least 15 min for the server PC is a non-negotiable
recommendation — Postgres tolerates crashes but cash-drawer state and
print jobs do not.

### 15.12 Security in local mode

- All admin/cashier access goes over HTTPS (self-signed LAN cert or imported
  proper cert).
- The Postgres port is bound to `127.0.0.1` only — never exposed on the LAN.
- Filesystem permissions: the data directory is owned by the service user
  (`kiosk`) with `0700`. Installer refuses to continue if a less-restrictive
  mode is forced.
- Backup archives are AES-256 encrypted with an admin-supplied passphrase;
  the passphrase is **not** stored in the config file — the service prompts
  for it on restore.
- Audit log append-only policy still applies (DB role without UPDATE/DELETE
  on `activity_log`).
- Anti-tamper: a nightly hash-chain over `journal_entries` is written to a
  signed receipts file; auditors can verify financial integrity even after
  a disk swap.

### 15.13 Observability offline

- Logs: rolling JSON files under `$DATA_DIR/logs/` (30 daily × 10 MB),
  same schema as cloud.
- Metrics: Micrometer backend is a small built-in TSDB (HyperLogLog counters
  persisted to Postgres); `/actuator/metrics` and a read-only `/internal/metrics`
  HTML page.
- In hybrid mode, metrics also batch-upload to the cloud observability
  stack every 15 min when online.
- The system-tray app shows: uptime, DB size, last backup, last sync,
  pending outbox count, printer status.

### 15.14 Installer UX

A single installer (`Kiosk-Setup-<version>.msi|.pkg|.deb`) runs a wizard:

1. Accept licence and enter activation key (or skip → 14-day trial).
2. Pick deployment mode: `local` or `hybrid` (can switch later).
3. Pick data directory (default: OS-appropriate).
4. Bootstrap first owner: name, email, password, 4-digit PIN.
5. Detect connected printers and run a test page.
6. Configure backup destination (local folder, USB drive, NAS path).
7. Configure LAN access (firewall rule, port, hostname, generate cert).
8. Start service, open the admin URL in the default browser.

Subsequent cashier devices are onboarded by scanning a QR code shown in the
admin UI; the QR encodes the server URL + a single-use cashier invite token.

### 15.15 Dev loop for the local profile

- `./gradlew :app-bootstrap:bootRun -Dspring.profiles.active=local` starts
  Kiosk against a fresh Postgres in `./build/local-pgdata/` (dev mode
  bootstraps the bundled Postgres automatically).
- `./gradlew :platform-desktop:jpackage` produces a signed installer for
  the host OS.
- CI matrix job runs the full integration suite on `local` profile too, so
  we catch adapter regressions early.

### 15.16 Things explicitly **out of scope** in local mode

- Multi-tenant on a single PC. A local install is **single-tenant** by
  design — simplifies RLS, backups, and upgrades. Multi-tenant remains a
  cloud-only feature.
- Automatic horizontal scaling. A single shop does not need it; if they
  outgrow one PC they migrate to hybrid or cloud.
- External webhook consumers. The local box does not expose itself to
  arbitrary internet callers.
- Pesapal/Stripe inbound callbacks. Out-of-band (cashier-initiated) flows only.

---

## Appendix A — REST API Surface (v1)

Only the most-used resources shown; all accept JSON, return `application/json`,
versioned under `/api/v1`. Mutating endpoints take `Idempotency-Key`.

```
POST   /auth/login
POST   /auth/login-pin
POST   /auth/refresh
POST   /auth/logout
POST   /auth/password/forgot
POST   /auth/password/reset

GET    /me
PATCH  /me

GET    /businesses/me
PATCH  /businesses/me                       (business.manage_settings)

GET    /users                               (users.list)
POST   /users                               (users.create)
PATCH  /users/{id}                          (users.update)
POST   /users/{id}/deactivate               (users.deactivate)

GET    /roles
POST   /roles
PATCH  /roles/{id}

GET    /suppliers
POST   /suppliers
GET    /suppliers/{id}
PATCH  /suppliers/{id}
GET    /suppliers/{id}/items
POST   /suppliers/{id}/items                (link items in bulk)
PATCH  /suppliers/{id}/items/{itemId}       (update default cost price, primary flag)
DELETE /suppliers/{id}/items/{itemId}
GET    /suppliers/{id}/payables
GET    /suppliers/{id}/statement            (?from=&to=)
POST   /suppliers/{id}/payments

GET    /items                               (search=, barcode=, categoryId=, supplierId=, noBarcode=, includeInactive=)
POST   /items
GET    /items/{id}
PATCH  /items/{id}
GET    /items/{id}/batches
GET    /items/{id}/cost-history             (?supplierId=)
GET    /items/{id}/sales                    (?from=&to=)
POST   /items/{id}/sell-price
GET    /items/{id}/suppliers

GET    /categories
POST   /categories
PATCH  /categories/{id}
POST   /categories/merge

GET    /purchase-orders
POST   /purchase-orders
POST   /purchase-orders/{id}/send
POST   /purchase-orders/{id}/cancel

POST   /goods-receipts                       (may include po_id or be adhoc)
POST   /goods-receipts/raw                   (raw note path; kiosk market trip)
POST   /goods-receipts/{id}/breakdown

GET    /supplier-invoices
POST   /supplier-invoices
POST   /supplier-invoices/{id}/approve
POST   /supplier-invoices/{id}/cancel

GET    /inventory/batches
GET    /inventory/movements                  (?itemId=&from=&to=)
POST   /inventory/adjustments
POST   /inventory/adjustments/{id}/approve
POST   /inventory/stock-takes
POST   /inventory/stock-takes/{id}/close
POST   /inventory/transfers
POST   /inventory/transfers/{id}/receive

GET    /shifts/current
POST   /shifts                               (open)
POST   /shifts/{id}/close

POST   /sales                                (Idempotency-Key)
GET    /sales                                (?cashier=&from=&to=&method=&status=)
GET    /sales/{id}
POST   /sales/{id}/void
POST   /sales/{id}/refund

GET    /customers                            (?phone=)
POST   /customers
PATCH  /customers/{id}
GET    /customers/{id}/credit
GET    /customers/{id}/wallet
GET    /customers/{id}/loyalty
POST   /customers/{id}/credit/payment
POST   /customers/{id}/wallet/topup
POST   /customers/{id}/loyalty/redeem

GET    /expenses
POST   /expenses
PATCH  /expenses/{id}

GET    /reports/dashboard                    (?date=)
GET    /reports/sales                        (?from=&to=&groupBy=day|hour|cashier)
GET    /reports/profit                       (?from=&to=&viewBy=item|category|batch|supplier)
GET    /reports/suppliers/spend              (?from=&to=)
GET    /reports/suppliers/price-comparison   (?categoryId=&minSuppliers=)
GET    /reports/inventory/valuation          (?branchId=)
GET    /reports/inventory/expiring           (?days=30)
GET    /reports/cash/daily-summary           (?date=)
GET    /reports/tax/summary                  (?from=&to=)
POST   /reports/{key}/export                 (?format=csv|xlsx|pdf) → returns jobId
GET    /reports/exports/{jobId}              → 202 pending / 200 with S3 URL

GET    /activity                             (entityType=&entityId=)
GET    /notifications
POST   /notifications/{id}/read

POST   /webhooks                              (superadmin)
GET    /api-keys
POST   /api-keys
DELETE /api-keys/{id}

# Super-admin
GET    /admin/tenants
POST   /admin/tenants
POST   /admin/tenants/{id}/suspend
POST   /admin/tenants/{id}/impersonate
GET    /admin/domains
POST   /admin/domains
```

---

## Appendix B — Domain Events

Published on an outbox table first, then relayed to Kafka/Redis Streams.
Subscribers inside the monolith use Spring's `ApplicationEventPublisher` in
phase 1; external subscribers use the message bus.

```
identity.user.created           {userId, businessId, role}
identity.user.role_changed      {userId, oldRoleId, newRoleId}
identity.login.succeeded        {userId, businessId, ip}
identity.login.failed           {email, ip, reason}

catalog.item.created            {itemId, businessId}
catalog.item.price_changed      {itemId, oldPrice, newPrice}
catalog.item.deactivated        {itemId, reason}

suppliers.supplier.created      {supplierId, businessId}
suppliers.item_linked           {supplierId, itemId, isPrimary}
suppliers.rating_changed        {supplierId, oldRating, newRating}

purchasing.po.created           {poId, supplierId, total}
purchasing.grn.posted           {grnId, supplierId, totalQty}
purchasing.invoice.posted       {invoiceId, supplierId, amount, dueDate}
purchasing.invoice.overdue      {invoiceId, daysOverdue, amount}

inventory.batch.created         {batchId, itemId, supplierId, qty, unitCost, expiry}
inventory.batch.depleted        {batchId, itemId}
inventory.batch.expiring_soon   {batchId, itemId, expiryDate}
inventory.batch.expired         {batchId, itemId, qtyWrittenOff}
inventory.stock.low_stock       {itemId, currentQty, minLevel}
inventory.adjustment.posted     {adjustmentId, itemId, difference, reason}
inventory.transfer.posted       {transferId, fromBranch, toBranch, lines}

sales.shift.opened              {shiftId, userId, openingCash}
sales.shift.closed              {shiftId, userId, variance}
sales.sale.completed            {saleId, total, profit, paymentMethod, customerId?}
sales.sale.voided               {saleId, reason, voidedBy}
sales.sale.refunded             {saleId, refundId, amount}

payments.payment.received       {paymentId, saleId, method, amount, reference}
payments.stk.initiated          {trackingId, amount, saleId}
payments.stk.succeeded          {trackingId, receipt}
payments.stk.failed             {trackingId, reason}

credits.account.created         {accountId, customerId}
credits.debt.recorded           {accountId, saleId?, amount, balanceAfter}
credits.payment.recorded        {accountId, amount, balanceAfter, method}
credits.wallet.topped_up        {accountId, amount, balanceAfter}
credits.loyalty.earned          {accountId, saleId, points, balanceAfter}
credits.public_claim.submitted  {accountId, txId, amount, kind}
credits.public_claim.reviewed   {accountId, txId, decision, reviewedBy}

finance.journal.posted          {entryId, sourceType, sourceId, totalDebit, totalCredit}
finance.cash_drawer.closed      {shiftId, expected, actual, difference}

auditing.activity.logged        {id, action, entityType, entityId, performedBy}
```

Event payloads should be small and reference-heavy (ids + business_id + user_id). Full
entity snapshots live in the DB and can be joined by subscribers when needed.

---

### Deliverables recap

When the rebuild is complete, the deliverables are:

1. A Git repository containing the Gradle multi-module Spring Boot application.
2. A container image published to a registry, deployable via a single `docker compose up` for dev and a Helm chart / ECS definition for prod.
3. A one-click migration tool that reads the existing Turso DB and writes the new PostgreSQL schema, with a `--dry-run` mode.
4. An OpenAPI 3.1 spec served at `/api/v1/openapi.json`, a hosted Swagger UI, and a Postman collection.
5. Developer docs under `docs/` (ADRs for every major decision in §11 and §14).
6. Operator docs for owners, admins, cashiers, and super-admins under `docs/ops/`.
7. A test suite covering: all domain invariants (§4.1), all edge cases (§14), and smoke-testing the 10 canonical reports (§9.6) — Phase 7 ships **six** as v1 acceptance and **four** as Phase 7.1 (see `docs/PHASE_7_PLAN.md`).
8. A pilot deployment at one real shop, run for 30 days in parallel with the existing system, with a documented migration playbook.

This is the whole blueprint. Build it in the order of §12 and stop at no
shortcut listed in §14.
