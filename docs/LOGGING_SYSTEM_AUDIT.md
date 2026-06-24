# Logging & Activity Tracking System Audit

**Project:** PalMart POS / E-commerce Backend  
**Date:** 2026-06-14  
**Scope:** Application logging, audit trails, activity tracking, error logging, and operational event logs from a POS, retail, e-commerce, accounting, and audit perspective.

---

## 1. Executive Summary

The current logging and activity-tracking layer is **fragmented, incomplete, and operationally risky** for a multi-tenant POS/e-commerce platform. The system relies almost entirely on:

1. **Spring Boot default console logging** with no file, JSON, or centralized aggregation configuration.
2. **Domain-specific state tables** (`sales`, `refunds`, `stock_movements`, `shifts`) that act as implicit logs but are not designed as audit trails.
3. **Three small, siloed audit tables** (`shift_audit_logs`, `pos_draft_audit_log`, `grocery_draft_audit_log`), of which only `shift_audit_logs` is exposed to users — and even then only embedded inside a single shift response.
4. **No centralized security audit log** for authentication, authorization, password changes, role changes, or API-key usage.
5. **No master-data audit trail** for products, prices, customers, suppliers, or business settings.
6. **No order lifecycle history** for web orders.
7. **No correlation IDs / MDC**, making distributed troubleshooting extremely difficult.

The result is a system where **fraud detection, operational debugging, financial reconciliation, and regulatory audit are all harder than they should be**. This document proposes a redesigned, unified logging architecture tuned for retail/POS/e-commerce operations.

### Bottom-line judgment

| Dimension | Current Grade | Target Grade |
|-----------|---------------|--------------|
| Fraud prevention | D | A |
| Auditability / accounting | C- | A |
| Operational troubleshooting | D+ | A- |
| User experience (finding logs) | D | A- |
| Scalability of log storage | C | A- |
| Regulatory / tax readiness | C- | A |

---

## 2. Current State Analysis by Domain

### 2.1 Application logging infrastructure

| Aspect | Finding |
|--------|---------|
| Framework | Spring Boot default Logback (console only) |
| Configuration files | **None** — no `logback-spring.xml`, `logback.xml`, or `logging.*` properties |
| Output | Console only; no file appender, no JSON, no centralized shipping |
| MDC / correlation IDs | **None** |
| Log volume | ~240 `log.*` calls across 71 files in a codebase of 1,000+ files |
| Actuator exposure | Only `/actuator/health` and `/actuator/info`; `/loggers` not exposed |

**Verdict:** The project is flying blind in production. Console logs are lost on container restart, cannot be searched, and cannot be correlated across requests.

### 2.2 User, staff, and security logs

| Aspect | Finding |
|--------|---------|
| Central security audit log | **Does not exist** |
| Login/logout audit | `UserSession` stores IP/UA, but no audit rows are written for success/failure |
| Failed login attempts | Only mutable counters on `users.failed_attempts` / `locked_until` |
| Password changes/resets | No audit trail |
| Role/permission changes | No audit trail; `roles.created_by`/`updated_by` columns exist but are unpopulated |
| API-key usage | `last_used_at` touched once per 2 minutes; no endpoint-level audit |
| User CRUD | No audit rows |
| Account lockout | Counters only; no persistent security event log |
| Super-admin actions | No audit trail |

**Verdict:** A compromised account, malicious insider, or privilege escalation would be extremely difficult to reconstruct forensically.

### 2.3 Inventory, sales, payments, refunds, orders

| Aspect | Finding |
|--------|---------|
| Stock movements | Strong `stock_movements` table; append-only with type, reference, cost, reason, created_by |
| Inventory adjustments / stock takes | Trail exists across `stock_take_lines` + `stock_adjustment_requests` + `stock_movements`, but no unified view |
| Sales transactions | `sales` + `sale_items` + `sale_payments`; idempotency key; journal entry link. No `sale_audit_log` for status changes |
| Payments | `sale_payments` itemises methods; gateway webhooks stored raw in `payment_webhook_events`; STK pushes tracked in `gateway_stk_pushes`. No unified payment audit log |
| Refunds / voids | `refunds` + `refund_lines` + `refund_payments`; stock and journal reversed. No dedicated refund audit log |
| Web order lifecycle | `web_orders` stores only current `status` and `fulfillment_status`; no status history table |
| POS/grocery draft carts | Dedicated audit tables with old/new JSON values exist, but **are not exposed via any API** |
| Product / price changes | No `product_audit_log`; old price must be inferred from prior `selling_prices` row |
| Customer activity | Scattered across `sales`, `credit_transactions`, `wallet_transactions`, `loyalty_transactions` |

**Verdict:** Transactional immutability is good, but lifecycle traceability and master-data change tracking are weak. The user cannot easily answer "what happened to this order?" or "who changed this price?"

### 2.4 Cash drawer, shift, supplier, purchase, system logs

| Aspect | Finding |
|--------|---------|
| Shift audit log | `shift_audit_logs` exists and is exposed; records only `SHIFT_OPENED`/`SHIFT_CLOSED` in practice; many declared event types (`PAID_IN`, `PAID_OUT`, `CASH_DROP`, etc.) are not emitted |
| Cash drawer mutations | Sale/void/refund drawer impacts are only visible in DB state; no audit rows |
| Drawouts | Lifecycle events are recorded in `shift_audit_logs` |
| Supplier activity | No audit log for supplier create/update/delete/payout settings |
| Purchase orders / GRNs / supplier payments | No audit trail for status transitions or postings |
| System events / errors | No `system_log` or `error_log` table; `GlobalExceptionHandler` only logs two exception types |
| Scheduled jobs | Most schedulers log failures to console, but there is no execution history table |
| Webhook deliveries | `webhook_deliveries` table tracks status (`pending`/`sent`/`dead`) but no list/replay UI beyond a single replay endpoint |

**Verdict:** Cash-handling audit coverage is dangerously thin given the fraud risk. Supplier/purchase audit is non-existent. System observability is minimal.

---

## 3. Current Issues Identified

### 3.1 Critical issues

1. **No centralized activity audit log.** Every domain invents its own trail (or none at all). This makes cross-domain investigations impossible.
2. **No security audit log.** Login success/failure, password changes, role changes, API-key usage, and account lockouts are not durably recorded.
3. **No master-data audit trail.** Product, customer, supplier, price, tax, and business-setting changes cannot be reconstructed.
4. **POS/grocery draft audit logs are write-only.** The data exists but users cannot see it, making it useless for troubleshooting.
5. **Web orders have no lifecycle history.** Support staff cannot see when an order moved from `awaiting_confirmation` to `dispatched` to `completed`.
6. **Cash drawer mutations are not audited.** Sales, voids, refunds, paid-in/paid-out, and cash drops change the drawer but do not write immutable audit rows.
7. **No log shipping or retention strategy.** Console logs are ephemeral and cannot support incident response or compliance.
8. **Plaintext secrets in `application.properties`.** Database password and JWT secret are committed in plaintext, visible in any log dump.

### 3.2 Important issues

9. **No correlation IDs / MDC.** Cannot trace a single request across services, schedulers, and WebSocket events.
10. **No dedicated audit permissions.** Logs inherit permissions from parent resources (e.g., `shifts.read`). There is no `audit.read` or `logs.read` permission model.
11. **`GlobalExceptionHandler` swallows most exceptions.** Only `InvalidDataAccessResourceUsageException` and the catch-all `Exception` are logged; validation, access-denied, and conflict exceptions are silent.
12. **`shift_audit_logs.ip_address` is always null.** The field exists but is not populated.
13. **Loose references.** `stock_movements.reference_id` and `sale_payments.gateway_txn_id` are strings, not foreign keys or indexed association keys.
14. **No UI for searching/filtering logs.** Only shift audit logs are surfaced, and only as a child list.
15. **`created_by` / `updated_by` audit columns are unpopulated.** JPA auditing is not configured.
16. **In-memory rate limiters.** Security events are not persisted and limits do not span JVM instances.

### 3.3 Nice-to-have issues

17. **No request access-log filter.** There is no record of incoming HTTP requests (method, path, actor, tenant, status, duration).
18. **No structured JSON logging.** Parsing console logs for alerting is painful.
19. **No scheduler execution history table.** Cannot audit historical job success/failure.
20. **No real-time log streaming for admins.** Cannot watch live POS activity for fraud/ops monitoring.

---

## 4. Design Principles for the Redesigned Logging Layer

We judge every logging decision against these principles:

1. **Immutability.** Audit logs are append-only. No UPDATE or DELETE. If a log is wrong, a compensating entry is appended.
2. **Non-repudiation.** Every entry carries actor, timestamp, IP, user-agent, and a tamper-evident checksum or signature where legally required.
3. **Traceability.** Every business event has a `correlation_id` and links to parent/child events (sale → payment → stock movement → journal entry).
4. **Least privilege.** Log visibility is permission-controlled. Cashiers see their own shift logs; managers see branch logs; owners/admins see business logs; super-admins see platform logs.
5. **Human-readable terminology.** Event names are verbs in plain language: "Sale completed", "Cash counted", "Price changed", not `AUDIT_SALE_COMPLETED`.
6. **Operational utility.** Logs answer real questions fast: "Who voided this sale?", "Why is the drawer short?", "When did this price change?"
7. **Scalability.** Hot logs (transactions) are queryable for 90 days; warm logs archived to object storage; cold logs retained for years.
8. **Privacy by design.** PII in logs is minimised, tokenised, or encrypted; GDPR erasure does not delete audit logs but anonymises references.

---

## 5. Proposed Logging Architecture

### 5.1 Unified log categories

A modern POS/e-commerce platform needs **ten log categories**.

| # | Category | What it tracks | Primary audience |
|---|----------|----------------|------------------|
| 1 | **Security & Access** | Logins, logouts, password/reset/PIN changes, MFA, lockouts, API-key usage, permission/role changes, session events | Admins, auditors, security ops |
| 2 | **Staff & User Management** | User/Staff created, updated, deactivated, role assigned, item-type permissions changed | Admins, HR/operations |
| 3 | **Sales & Payments** | Sale completed, voided, refunded, payment split, tender details, payment gateway events, STK pushes | Cashiers, managers, finance |
| 4 | **Cash Drawer & Shifts** | Shift open/close, float adjust, paid-in, paid-out, cash drop, count/declaration, variance approval, drawouts | Cashiers, managers, accountants |
| 5 | **Inventory & Stock** | Stock receipts, adjustments, transfers, wastage, batch clearance, stock takes, count submissions | Stock managers, buyers, operations |
| 6 | **Orders & Fulfillment** | Web order created, paid, confirmed, dispatched, completed, cancelled, returned; POS draft cart changes | Support, fulfillment, customers (limited) |
| 7 | **Customers & Loyalty** | Profile changes, credit limit changes, credit transactions, wallet transactions, loyalty transactions | Support, finance, compliance |
| 8 | **Products & Pricing** | Item created/updated/deleted, selling/buying price changes, tax/category changes, barcode changes | Managers, buyers, finance |
| 9 | **Suppliers** | Supplier created/updated, contact added/updated/deleted, payout settings changed | Managers, buyers, finance |
| 10 | **System & Integrations** | Scheduled job runs, webhook deliveries, import jobs, backup runs, exceptions, notification deliveries, supplier integrations | Developers, DevOps, admins |

### 5.2 Event taxonomy per category

#### 1. Security & Access

| Event | Severity | Immutable | Notes |
|-------|----------|-----------|-------|
| Login succeeded | INFO | Yes | Store IP, UA, method (password/PIN), session ID |
| Login failed | WARN | Yes | Store IP, UA, reason (bad credentials / locked / disabled) |
| Logout | INFO | Yes | Session closed |
| Logout all sessions | INFO | Yes | Triggered by password change or admin action |
| Password changed | INFO | Yes | Who, when, IP |
| Password reset requested | INFO | Yes | Token issued, masked email |
| Password reset used | INFO | Yes | Token consumed |
| PIN changed | INFO | Yes | Who, when |
| Account locked (soft) | WARN | Yes | Threshold crossed |
| Account locked (hard) | CRITICAL | Yes | Requires admin review |
| Account unlocked | CRITICAL | Yes | By whom |
| API key created | INFO | Yes | Scopes, label, creator |
| API key revoked | INFO | Yes | By whom |
| API key used | DEBUG | Yes | For high-volume analytics; sampled |
| Role created/updated/deleted | INFO | Yes | Permission diff |
| User assigned/demoted role | INFO | Yes | Old role → new role |
| Permission denied (suspicious) | WARN | Yes | Repeated denied access to sensitive endpoints |

#### 2. Staff & User Management

| Event | Notes |
|-------|-------|
| Staff invited | By whom, role, branch |
| Staff activated/deactivated | By whom, reason |
| Staff profile updated | Field-level diff (name, phone, branch) |
| Staff item-type permissions changed | Which item types |
| Customer profile created/updated/anonymised | GDPR-relevant |

#### 3. Sales & Payments

| Event | Notes |
|-------|-------|
| Sale completed | Sale ID, total, payments, shift, cashier, terminal |
| Sale voided | Original sale ID, void reason, approver if required |
| Refund issued | Refund ID, original sale, amount, method, reason |
| Payment tendered | Method, amount, reference, gateway txn ID |
| Payment gateway webhook received | Gateway, event ID, topic, status |
| STK push initiated/completed/failed | Phone, amount, context, failure reason |
| Payment reversal | Reason, original payment reference |

#### 4. Cash Drawer & Shifts

| Event | Notes |
|-------|-------|
| Shift opened | Opening cash, cashier, terminal, branch |
| Shift suspended/resumed | Reason |
| Cash paid in | Amount, reason, category |
| Cash paid out / expense | Amount, category, recipient |
| Cash drop | Amount, to safe/deposit |
| Sale added to drawer | Cash amount (automatic) |
| Refund removed from drawer | Cash amount (automatic) |
| Shift closed | Expected, counted, variance, status |
| Variance approved | Amount, approver, reason |
| Drawout initiated/approved/rejected/voided/expired | Tier, amount, recipient |
| Note added | Free text note on shift |

#### 5. Inventory & Stock

| Event | Notes |
|-------|-------|
| Stock received | GRN/invoice, supplier, batch, qty, cost |
| Stock adjusted | Variance, reason, approved by |
| Stock transferred out/in | Branch, batch, qty |
| Stock transfer cancelled | Reason |
| Wastage recorded | Reason, qty, batch |
| Batch cleared | Expiry/damage, qty |
| Stock take session opened/closed | Session type, branch |
| Count submitted | Item, counted qty, system qty, counter |
| Count edited | Old count, new count, who |
| Adjustment request approved/rejected | Variance, approver |

#### 6. Orders & Fulfillment

| Event | Notes |
|-------|-------|
| Web order created | Customer, channel, total |
| Web order paid | Payment method, gateway reference |
| Web order payment failed | Reason |
| Order confirmed | By whom |
| Order dispatched | Carrier, tracking |
| Order completed | By whom |
| Order cancelled | Reason, refund status |
| Order returned | Items, refund |
| POS draft cart created/updated/cancelled/completed | Line-level diff |

#### 7. Customers & Loyalty

| Event | Notes |
|-------|-------|
| Customer profile updated | Field diff |
| Credit limit changed | Old/new, by whom |
| Credit sale created | Sale, amount, due date |
| Credit payment received | Amount, method |
| Wallet topped up / debited | Source, amount |
| Loyalty points earned/redeemed/adjusted | Points, sale reference |

#### 8. Products & Pricing

| Event | Notes |
|-------|-------|
| Item created/updated/deleted | Field diff (stocked flag, unit, barcode, category, tax) |
| Selling price changed | Old → new, effective date, set by |
| Buying price changed | Old → new, supplier |
| Tax rate changed | Old → new |
| Category changed | Re-categorisation |
| Barcode/SKU changed | Old → new |

#### 9. Suppliers

| Event | Notes |
|-------|-------|
| Supplier created | Name, code, type, status |
| Supplier updated | Field diff (name, code, terms, payout settings, etc.) |
| Supplier contact added/updated/deleted | Contact details, primary flag |

#### 10. System & Integrations

| Event | Notes |
|-------|-------|
| Scheduled job started/completed/failed | Job name, duration, records affected |
| Webhook delivery attempted/succeeded/failed | URL, event type, retries, response code |
| Import job started/completed/failed | File, rows, errors |
| Backup started/completed/failed | Size, destination |
| Notification sent/failed | Channel, recipient, template |
| Supplier integration event | KopoKopo send-money status, failure reason |
| Exception / error | Stack trace hash, correlation ID, user impact |

### 5.3 Visibility matrix: who sees what

| Role | Security | Staff | Sales/Payments | Cash/Shift | Inventory | Orders | Customers | Products | System |
|------|----------|-------|----------------|------------|-----------|--------|-----------|----------|--------|
| Cashier | Own only | — | Own / own shift | Own shift only | Read own branch | Own draft | Read-only own | Read-only | — |
| Stock Manager | — | — | Read branch | Read branch | Full branch | Read branch | Read-only | Full branch | Read jobs |
| Shift Supervisor | Read branch | Read branch | Full branch | Full branch | Read branch | Read branch | Read branch | Read branch | Read jobs |
| Branch Manager | Read branch | Full branch | Full branch | Full branch | Full branch | Full branch | Full branch | Full branch | Full branch | Read |
| Owner / Admin | Full business | Full business | Full business | Full business | Full business | Full business | Full business | Full business | Full business | Read |
| Accountant / Auditor | Full business (read) | Read | Full business (read) | Full business (read) | Full business (read) | Read | Read | Read | Read | Read |
| Support Agent | Read limited | Read limited | Read limited | — | Read limited | Full business | Full business | Read | Read | Read limited |
| Super Admin | Platform | Platform | Platform | Platform | Platform | Platform | Platform | Platform | Platform | Platform |

### 5.4 Immutability rules

| Category | Immutable | Retention | Notes |
|----------|-----------|-----------|-------|
| Security & Access | Yes | 7 years | Legal/audit requirement |
| Sales & Payments | Yes | 7 years | Tax/financial audit |
| Cash Drawer & Shifts | Yes | 7 years | Fraud/cash audit |
| Inventory & Stock | Yes | 7 years | Traceability/recall |
| Orders & Fulfillment | Yes | 7 years | Consumer law/support |
| Customers & Loyalty | Yes | 7 years | Financial audit |
| Products & Pricing | Yes | 7 years | Pricing disputes |
| Staff & User Management | Yes | 7 years | HR/compliance |
| Suppliers | Yes | 7 years | Supplier disputes / AP audit |
| System & Integrations | Yes | 90 days hot, 1 year warm, 7 years cold | Ops/DevOps |

**No application user or admin can edit or delete an audit log.** GDPR anonymisation creates a new "Profile anonymised" audit entry; it does not remove historical entries.

---

## 6. Proposed Database / Logging Structure

### 6.1 Two-tier storage model

| Tier | Storage | Data | Query pattern |
|------|---------|------|---------------|
| **Operational audit store** | PostgreSQL/MariaDB tables | Business audit events (categories 1–8) | Time-range + entity + actor queries |
| **System telemetry store** | Structured log files / object storage / log aggregator | Application logs, exceptions, traces (category 9) | Full-text, metrics, alerting |

### 6.2 Core audit tables

#### `audit_events` (unified business audit log)

```sql
CREATE TABLE audit_events (
  id                 CHAR(36) PRIMARY KEY,
  business_id        CHAR(36) NOT NULL,
  branch_id          CHAR(36) NULL,
  category           VARCHAR(40) NOT NULL,        -- security, sales, cash, inventory, orders, customers, products, staff, system
  event_type         VARCHAR(80) NOT NULL,        -- login_succeeded, sale_voided, price_changed, ...
  severity           VARCHAR(20) NOT NULL,        -- debug, info, warn, error, critical
  actor_id           CHAR(36) NULL,               -- user id, api_key id, or system
  actor_type         VARCHAR(20) NOT NULL,        -- user, api_key, system, scheduler
  actor_name         VARCHAR(255) NULL,           -- display name at time of event
  target_type        VARCHAR(60) NULL,            -- sale, shift, item, customer, ...
  target_id          CHAR(36) NULL,
  target_label       VARCHAR(255) NULL,           -- human-readable label (sale #1234, John Doe)
  session_id         CHAR(36) NULL,
  correlation_id     CHAR(36) NULL,
  ip_address         VARCHAR(45) NULL,
  user_agent         VARCHAR(512) NULL,
  source             VARCHAR(40) NULL,            -- pos_terminal, web_admin, mobile_app, api, scheduler
  terminal_id        VARCHAR(80) NULL,
  shift_id           CHAR(36) NULL,
  old_state          JSON NULL,                   -- snapshot before change
  new_state          JSON NULL,                   -- snapshot after change
  diff               JSON NULL,                   -- computed field-level diff
  reason             VARCHAR(500) NULL,
  metadata           JSON NULL,                   -- extra event-specific fields
  checksum           VARCHAR(128) NULL,           -- HMAC of row for tamper detection
  created_at         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

  INDEX idx_audit_business_time (business_id, created_at DESC),
  INDEX idx_audit_category_type (business_id, category, event_type, created_at DESC),
  INDEX idx_audit_actor (business_id, actor_id, created_at DESC),
  INDEX idx_audit_target (business_id, target_type, target_id, created_at DESC),
  INDEX idx_audit_correlation (correlation_id),
  INDEX idx_audit_shift (shift_id, created_at DESC)
);
```

#### `audit_event_summaries` (rollup for fast dashboards)

```sql
CREATE TABLE audit_event_summaries (
  business_id        CHAR(36) NOT NULL,
  branch_id          CHAR(36) NULL,
  category           VARCHAR(40) NOT NULL,
  event_type         VARCHAR(80) NOT NULL,
  event_date         DATE NOT NULL,
  count              BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (business_id, branch_id, category, event_type, event_date)
);
```

### 6.3 Specialized supporting tables

Keep existing domain-specific tables where they are already semantically richer, but **treat them as append-only sub-ledgers** that also emit an `audit_events` row:

| Existing table | Role | Recommended change |
|----------------|------|---------------------|
| `stock_movements` | Inventory sub-ledger | Add `audit_event_id` FK, enforce `reference_id` indexing, emit `inventory_movement_recorded` event |
| `shift_audit_logs` | Shift sub-ledger | Migrate events to `audit_events`; keep as denormalized quick read for shift detail screen |
| `pos_draft_audit_log` | Draft cart sub-ledger | Expose via API, add `audit_event_id`, improve naming |
| `grocery_draft_audit_log` | Grocery draft sub-ledger | Same as above |
| `payment_webhook_events` | Payment gateway sub-ledger | Add `audit_event_id`, add attempt/response tracking |
| `gateway_stk_pushes` | STK push sub-ledger | Add `audit_event_id` |
| `webhook_deliveries` | Integration sub-ledger | Add admin list/filter API |
| `import_jobs`, `backup_runs`, `reporting_refresh_runs` | Job sub-ledgers | Emit `system` category events |

### 6.4 Application logging configuration

Create `src/main/resources/logback-spring.xml`:

```xml
<configuration>
  <springProfile name="!prod">
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
      <encoder>
        <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{correlationId}] %logger{36} - %msg%n</pattern>
      </encoder>
    </appender>
    <root level="INFO">
      <appender-ref ref="CONSOLE"/>
    </root>
  </springProfile>

  <springProfile name="prod">
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
      <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
    </appender>
    <root level="WARN">
      <appender-ref ref="JSON"/>
    </root>
    <logger name="zelisline.ub.audit" level="INFO"/>
    <logger name="zelisline.ub.security" level="INFO"/>
  </springProfile>
</configuration>
```

Add dependency in `build.gradle`:

```gradle
implementation 'net.logstash.logback:logstash-logback-encoder:8.0'
```

### 6.5 Correlation ID / MDC filter

Add a servlet filter that:
1. Reads `X-Correlation-Id` from client or generates a UUID.
2. Puts it in MDC as `correlationId`.
3. Returns it in response header `X-Correlation-Id`.
4. Clears MDC after request.

### 6.6 Writing events

Introduce an `AuditEventPublisher` service:

```java
public interface AuditEventPublisher {
    void publish(AuditEvent event);
}
```

Use Spring's `@TransactionalEventListener` to write audit events **after** the business transaction commits, ensuring the main operation succeeds before the log is persisted. For security events (login failure), write immediately outside the business transaction.

### 6.7 Migration strategy

1. Create `audit_events` and `audit_event_summaries` tables.
2. Backfill from existing `shift_audit_logs`, `pos_draft_audit_log`, `grocery_draft_audit_log`, `stock_movements`, `sales` status transitions, `refunds`, `web_orders` current states, `user_sessions`, etc.
3. Add `audit_event_id` columns to existing sub-ledgers.
4. Update all services to emit events going forward.
5. Keep old tables read-only for historical lookup.

---

## 7. Proposed Navigation Structure

### 7.1 Top-level navigation

```
Activity & Logs
├── Live Activity      (real-time feed for managers)
├── Audit Logs         (searchable unified log)
│   ├── Security & Access
│   ├── Staff & Users
│   ├── Sales & Payments
│   ├── Cash Drawer & Shifts
│   ├── Inventory & Stock
│   ├── Orders & Fulfillment
│   ├── Customers & Loyalty
│   ├── Products & Pricing
│   └── System & Integrations
├── Reports
│   ├── Shift Reconciliation
│   ├── Cash Movement Report
│   ├── Inventory Movement Report
│   ├── Sales Activity Report
│   ├── Staff Activity Report
│   └── Audit Trail Export
└── Settings
    ├── Log Retention
    ├── Audit Permissions
    └── Export Configuration
```

### 7.2 Contextual log access

Embed a **"Activity"** tab or panel on every primary record screen:

| Screen | Activity tab shows |
|--------|--------------------|
| Sale detail | Sale events, payment events, void/refund events, related stock movements |
| Shift detail | Open/close, paid-in/out, cash drops, sales, refunds, drawouts, notes |
| Item detail | Price changes, stock movements, wastage, transfers, adjustments |
| Customer detail | Profile edits, sales, credit/wallet/loyalty transactions |
| Web order detail | Status history, payment events, fulfillment events |
| Supplier detail | Profile edits, POs, GRNs, payments, disbursements |

### 7.3 Mobile-friendly considerations

- Logs should default to **today** and **this branch** with one-tap filters.
- Card-based layout for mobile; table for desktop.
- Swipe-to-view detail; tap event to see full diff.
- Push notifications for critical events (large variance, suspicious login, failed backup).
- Offline POS terminals queue audit events and sync when reconnected.

---

## 8. Example Screens and Layouts

### 8.1 Unified Audit Log screen (desktop)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ Audit Logs                                                    [Export] [?]  │
├─────────────────────────────────────────────────────────────────────────────┤
│ Filter: [All Categories ▼] [All Events ▼] [All Branches ▼] [Today ▼]       │
│ Search: [sale #1042              ] [Staff: Any ▼] [Severity: Any ▼] [🔍]  │
├─────────────────────────────────────────────────────────────────────────────┤
│ Time          Category        Event                Actor        Target     │
│ 14:32:05      Cash Drawer     Shift closed         Jane Doe     Shift #12  │
│ 14:31:48      Sales           Sale voided          Jane Doe     Sale #1042 │
│ 14:28:12      Inventory       Stock adjusted       Mike K.      Item #8821 │
│ 14:25:00      Security        Login failed         Unknown      -          │
│ 14:22:10      Products        Price changed        Admin        Item #1102 │
├─────────────────────────────────────────────────────────────────────────────┤
│ Selected: Sale #1042 voided                                                 │
│ ─────────────────────────────────────────────────────────────────────────── │
│ Actor: Jane Doe (Cashier)                                                   │
│ Time: 14:31:48 EAT                                                          │
│ Terminal: POS-3 | Shift: #12 | IP: 102.68.x.x                               │
│ Reason: Customer cancelled item                                             │
│ ─────────────────────────────────────────────────────────────────────────── │
│ Diff:                                                                       │
│   Status:  COMPLETED → VOIDED                                               │
│   Refund:  KES 1,250 to M-Pesa                                              │
│   Stock:   2 × Item #8821 restored to Batch #B-2024-001                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 8.2 Shift detail screen with activity timeline

```
┌─────────────────────────────────────────────────────────────┐
│ Shift #12 — Jane Doe — 08:00 to 14:32                       │
├─────────────────────────────────────────────────────────────┤
│ Summary: Opening KES 5,000 | Sales KES 42,300 | Refunds -KES 1,250 │
│ Expected: KES 45,050 | Counted: KES 45,000 | Variance: -KES 50     │
├─────────────────────────────────────────────────────────────┤
│ Activity Timeline                                           │
│ 14:32  ● Shift closed (variance -KES 50)                   │
│ 14:31  ● Sale #1042 voided (-KES 1,250)                    │
│ 13:45  ● Cash drop KES 10,000 to safe                      │
│ 12:20  ● Paid out KES 500 (petty cash)                     │
│ 10:15  ● Paid in KES 2,000 (change float)                  │
│ 08:00  ● Shift opened with KES 5,000                       │
└─────────────────────────────────────────────────────────────┘
```

### 8.3 Mobile audit log card

```
┌──────────────────────────────┐
│ 🛒 Sale completed              │
│ Sale #1041 — KES 3,200        │
│ Jane Doe • POS-3 • 14:20      │
│ [View details >]              │
└──────────────────────────────┘
```

### 8.4 Security alert screen

```
┌─────────────────────────────────────────────────────────────┐
│ Security Alerts                          [Mark reviewed ▼]  │
├─────────────────────────────────────────────────────────────┤
│ ⚠️ 5 failed logins for user john@example.com from IP 41.x   │
│    14:25 EAT — Account soft-locked for 15 min               │
│    [View full trail] [Block IP] [Force password reset]      │
├─────────────────────────────────────────────────────────────┤
│ ⚠️ API key "kiosk-12" accessed /admin/users at 13:10        │
│    Permission denied — key lacks users.read                 │
│    [Revoke key] [View key usage]                            │
└─────────────────────────────────────────────────────────────┘
```

---

## 9. Priority Ranking

### 9.1 Critical (do immediately)

| # | Improvement | Why critical |
|---|-------------|--------------|
| 1 | Create unified `audit_events` table and `AuditEventPublisher` | Without this, no foundation exists |
| 2 | Add security audit logging for login/logout, password/PIN changes, role changes, lockouts | Fraud/account compromise risk |
| 3 | Emit cash drawer mutations into audit log (sale, refund, void, paid-in/out, cash drop, shift open/close) | Cash fraud is the #1 POS risk |
| 4 | Add master-data audit for products, prices, customers, suppliers | Pricing disputes, compliance, fraud |
| 5 | Add web order status history | Customer support and consumer law |
| 6 | Configure logback with JSON appender and remove plaintext secrets from `application.properties` | Production observability and security |
| 7 | Add correlation ID / MDC filter | Without this, debugging is nearly impossible |

### 9.2 Important (do within 1–2 sprints)

| # | Improvement | Why important |
|---|-------------|---------------|
| 8 | Expose POS/grocery draft audit logs via API and UI | Data already exists but is unusable |
| 9 | Add dedicated audit permissions (`audit.read`, `audit.category.*`) | Least privilege and compliance |
| 10 | Improve `GlobalExceptionHandler` to log all exception types with correlation IDs | Faster incident response |
| 11 | Add request access-log filter | Security and performance visibility |
| 12 | Enforce foreign keys / indexed association keys for `stock_movements.reference_id`, `sale_payments.gateway_txn_id` | Data integrity and traceability |
| 13 | Backfill historical events into `audit_events` | Complete audit trail |
| 14 | Add `created_by` / `updated_by` population via `AuditorAware` | Basic audit hygiene |
| 15 | Persist rate-limit events to audit log | Brute-force forensics |

### 9.3 Nice-to-have (do when scaling)

| # | Improvement | Why nice-to-have |
|---|-------------|------------------|
| 16 | Real-time activity dashboard with WebSocket updates | Operations monitoring |
| 17 | Scheduler execution history table | Historical job audit |
| 18 | Log shipping to external aggregator (Datadog, CloudWatch, ELK) | Centralized ops at scale |
| 19 | Tamper-evident checksums on audit rows | Extreme audit assurance |
| 20 | AI/anomaly detection on audit patterns | Proactive fraud detection |
| 21 | Audit log data retention policy UI | Compliance self-service |

---

## 10. Implementation Roadmap

### Phase 1 — Foundation (Weeks 1–2)

1. Add `logback-spring.xml` with console + JSON appenders and MDC.
2. Implement correlation ID filter.
3. Create `audit_events` and `audit_event_summaries` tables.
4. Build `AuditEventPublisher` and `AuditEvent` domain model.
5. Add audit permissions to permission seed data.
6. Remove plaintext secrets from `application.properties`; use environment variables or secrets manager.

### Phase 2 — Security & Cash (Weeks 3–4)

1. Emit security events from `AuthService`, `SuperAdminAuthService`, `IdentityService`, `ApiKeyAuthenticationFilter`, `JwtAuthenticationFilter`.
2. Emit shift/drawer events from `ShiftService`, `DrawoutService`, `SaleService`, `SaleVoidService`, `SaleRefundService`.
3. Improve `GlobalExceptionHandler` logging.
4. Add request access-log filter.

### Phase 3 — Business Operations (Weeks 5–6)

1. Emit inventory events from `InventoryLedgerService`, `InventoryTransferService`, `StockTakeService`, `SupplyBatchClearanceService`.
2. Emit sales/payment/refund events.
3. Add web order status history table and events.
4. Add product/price/customer/supplier master-data events.

### Phase 4 — UI & APIs (Weeks 7–8)

1. Build `GET /api/v1/audit-events` with filters (category, eventType, actor, target, time range, branch).
2. Build admin audit log UI.
3. Expose POS/grocery draft audit logs in POS UI.
4. Add contextual "Activity" tabs on sale, shift, item, customer, order, supplier screens.
5. Add CSV/PDF export for auditors.

### Phase 5 — Scale & Harden (Weeks 9–10)

1. Backfill historical events.
2. Add log shipping / aggregator integration.
3. Add retention policy and archiving.
4. Add tamper-evident checksums if required by jurisdiction.
5. Add anomaly alerting rules.

---

## 11. Key Metrics to Track

| Metric | Target |
|--------|--------|
| % of business-mutating operations with an audit event | > 98% |
| Mean time to answer "who changed X?" | < 30 seconds |
| Audit log query p95 latency | < 500 ms |
| Critical security alert detection-to-notification latency | < 1 minute |
| Audit log retention compliance | 100% |
| Log loss rate | 0% |

---

## 12. Conclusion

The current logging layer is a collection of partial solutions rather than a coherent system. The good news is that the underlying transactional model (`sales`, `stock_movements`, `journal_entry`, `shifts`) is solid. The missing layer is a **unified, immutable, searchable, permission-controlled audit event system** that ties everything together.

Implement the **Critical** items first: the unified `audit_events` table, security audit logging, and cash drawer audit logging. These alone will dramatically reduce fraud risk and improve operational trust. Then layer on the business-domain events and UI to create a world-class POS/e-commerce audit experience.

The proposed design is deliberately simple: one primary table, nine categories, clear visibility rules, and strong immutability. It will scale from a single store to a multi-branch, multi-channel retail operation without redesign.
