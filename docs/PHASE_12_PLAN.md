<div align="center">

# 🔭 Phase 12 — Post-GA: Migration, Enterprise & Platform

### After GA (Phase 11), the programme shifts to **legacy migration**, **enterprise** readiness, **scale** under real load, and **sustained** platform quality — without replacing the modular monolith.

*`README.md` and `implement.md` §12 end the official roadmap at **Phase 11**. Phase 12 is a **proposed charter** for **v1.x / v2**, including the Turso migration tool called a **brownfield launch blocker** in `ARCHITECTURE_REVIEW.md` §6.8.*

[![Phase](https://img.shields.io/badge/phase-12-post--GA-violet)](./README.md#-milestones--roadmap)
[![Status](https://img.shields.io/badge/status-proposed-lightgrey)](./README.md#-milestones--roadmap)
[![Depends on](https://img.shields.io/badge/depends%20on-Phase%2011%20GA-green)](./README.md#-milestones--roadmap)

</div>

---

## 📑 Table of Contents

- [Why this document exists](#-why-this-document-exists)
- [What "Phase 12" means in one paragraph](#-what-phase-12-means-in-one-paragraph)
- [Prerequisites](#-prerequisites)
- [In scope / out of scope](#-in-scope--out-of-scope)
- [The slice plan at a glance](#-the-slice-plan-at-a-glance)
- [Slice 1 — Turso → PostgreSQL migration tool](#-slice-1--turso--postgresql-migration-tool)
- [Slice 2 — Enterprise readiness](#-slice-2--enterprise-readiness)
- [Slice 3 — Scale, cost & reliability](#-slice-3--scale-cost--reliability)
- [Slice 4 — Deferred product depth](#-slice-4--deferred-product-depth)
- [Slice 5 — Ecosystem & integrations](#-slice-5--ecosystem--integrations)
- [Slice 6 — Platform sustainment](#-slice-6--platform-sustainment)
- [Cross-cutting work](#-cross-cutting-work)
- [Definition of Done](#-definition-of-done)
- [Risks](#-risks)
- [Open questions](#-open-questions)

---

## 🎯 Why this document exists

Work that **does not** fit Phases **0–11** still has to land somewhere:

| Source | Gap |
|--------|-----|
| `implement.md` deliverable **#3** | One-click migration from existing Turso DB → this PostgreSQL schema, with `--dry-run`. |
| `ARCHITECTURE_REVIEW.md` §6.8 | Migration tool missing from Phase 0–11 — **brownfield** cutover risk. |
| Phase plans 2–10 | Turso migration repeatedly deferred to a **dedicated** phase. |

Phase 12 **groups** that obligation with **typical post-GA** work (enterprise, scale, sustainment). Renumbering `implement.md` §12 is optional until stakeholders adopt “Phase 12” officially.

---

## 🧭 What “Phase 12” means in one paragraph

When you execute this phase, existing Turso shops can **dry-run** and **commit** a **documented** schema mapping into Postgres with **parity reports**; enterprise deals get **SSO**, stronger **audit** exports, and an optional path to **SOC 2** evidence; production is **right-sized** (replicas, pools, cost visibility); deferred analytics and commercial features ship **without** breaking **locked COGS** rules; integrations mature behind **versioning**; and **LTS**, **deprecation**, **on-call**, and **support tiers** make releases boring — in a good way.

---

## ✅ Prerequisites

| Must be true | Why |
|--------------|-----|
| Phase **11** GA criteria met for **cloud** greenfield | Migration targets a **stable** API and schema. |
| Phase **10** done if **local/hybrid** shops are in migration scope | Installer path for on-prem cutover. |
| **OpenAPI** + **RLS** behaviour stable | Tooling and auditors rely on fixed boundaries. |

---

## 📦 In scope / out of scope

### In scope

- **Migration CLI**: Turso/SQLite dump or export → Flyway-aligned Postgres; `--dry-run`, idempotent apply, row counts and checksum **parity** reports.
- **Enterprise**: SAML 2.0 / OIDC SSO (optional); stricter session options; CMK story (ADR); SOC 2 **Type I** readiness optional.
- **Scale**: read replicas for reporting, pooler (e.g. PgBouncer / RDS Proxy), MV refresh isolation, basic FinOps views.
- **Product depth**: basket/MV analytics (`implement.md` §9.2 stretch), richer promos, M-Pesa statement reconciliation UI (`§9.5`), serial numbers if product picks up Phase 3 open question.
- **Ecosystem**: partner-facing patterns (accounting CSV/API), expanded webhook catalogue (builds on Phase 8).
- **Sustainment**: LTS branching, public deprecation policy, incident response, support SLAs by tier.

### Out of scope

- Microservices **split** or bounded-context rewrites without ADR.
- Unrelated product lines (payroll, HR, etc.).
- A **guarantee** of fully automatic semantic parity Turso → Java — expect **shop-specific** mapping sessions and **manual** reconciliation for edge cases.

---

## 🗺️ The slice plan at a glance

| # | Slice | Primary outcome |
|---|--------|------------------|
| 1 | Migration tool | Documented dry-run + apply + verify; ≥1 pilot brownfield |
| 2 | Enterprise | SSO (if in scope) + audit pack; optional SOC path |
| 3 | Scale & reliability | Documented capacity and cost envelope under load |
| 4 | Product depth | ≥3 deferred items shipped or ADR-deferred |
| 5 | Ecosystem | ≥2 integration patterns documented and supported |
| 6 | Sustainment | LTS + IR runbook + support tiers live |

Slices **2–5** often run in parallel once **Slice 1** fixes the **mapping spec** spike.

---

## 🏛️ Slice 1 — Turso → PostgreSQL migration tool

**Goal.** Satisfy `implement.md` deliverable **#3** and the architecture review.

### Deliverables

- Readable **mapping spec** (Turso table → Postgres table) listing known gaps.
- CLI: `migrate --dry-run`, `migrate --apply`, `migrate verify` (checksums).
- Report: entity counts within tolerance; optional tie-in to finance trial balance sanity.
- Pilot: at least one **real** shop dry-run before first production apply (see `implement.md` pilot / playbook language).

### Tests

- Fixture Turso snapshot in CI (subset); full-size shop migration is a **manual** gate before GA of the tool.

---

## 🏛️ Slice 2 — Enterprise readiness

**Goal.** Sell to multi-site and regulated buyers without maintaining a **fork**.

### Deliverables

- IdP integration (SAML / OIDC) behind feature flag.
- Audit export bundle (who did what when), aligned with Phase 8 DPA exports.
- Optional SOC 2 control matrix — reuse Phase 11 ASVS evidence where possible.

---

## 🏛️ Slice 3 — Scale, cost & reliability

**Goal.** Phase 11 Gatling is a point-in-time; real growth needs new SLOs and cost discipline.

### Deliverables

- Read path for heavy reports (replica lag SLA).
- Error budgets / burn-rate alerts per tier.
- Rough per-tenant cost view for pricing and finance.

---

## 🏛️ Slice 4 — Deferred product depth

**Goal.** Burn down high-value items deferred from Phases 3–9.

### Examples (product prioritisation — not fixed here)

- Basket / pair analytics or `mv_basket_pairs` ADR.
- Promotional pricing beyond margin suggest.
- M-Pesa bank statement reconciliation UI.
- Serialised items / IMEI if approved.

---

## 🏛️ Slice 5 — Ecosystem & integrations

**Goal.** Compose with tax, accounting, and BI without a custom integration per customer.

### Deliverables

- Partner SDK or explicit **OpenAPI** stability / semver policy.
- At least two “certified” patterns (CSV-first is acceptable) with test harnesses.

---

## 🏛️ Slice 6 — Platform sustainment

**Goal.** GA is a tag; years of operation need governance.

### Deliverables

- LTS strategy (e.g. N−1 security fixes on a stable branch).
- Deprecation policy for API fields and webhooks.
- Incident response runbook and on-call rota (can start small).
- Support tiers with P1 response expectations.

---

## 🔄 Cross-cutting work

| Concern | Rule |
|---------|------|
| Compliance | Migration logs: same PII retention story as Phase 8. |
| Communication | Semver + release notes for every migration tool release. |
| Feature flags | Large slices ship dark or pilot-first. |

---

## ✅ Definition of Done

- [ ] Migration tool: dry-run + apply + verify on ≥1 pilot brownfield tenant.
- [ ] Enterprise slice: SSO (if in scope) and audit narrative signed by security stakeholder.
- [ ] Scale slice: documented max tenants / RPS at target monthly cost.
- [ ] Product slice: backlog outcomes per planning (≥3 items or ADR defer).
- [ ] Sustainment slice: LTS + IR + support tiers published under `docs/ops/`.

---

## ⚠️ Risks

| Risk | Mitigation |
|------|------------|
| Sales promises full automatic parity | Contract lists **manual** steps and known gaps up front. |
| Enterprise scope creep | Split Phase 12 into 12a / 12b milestones. |
| Team fatigue post-GA | Named owner + rotation for migration tool. |

---

## ❓ Open questions

1. Is Phase 12 **migration-only** (narrow) or **full post-GA platform** (broad) — one doc or two?
2. Who owns mapping defects — professional services or core engineering?
3. SOC 2 Type II in v1.x or reserved for v2?
4. Should `README.md` gain an official Phase 12 bullet after stakeholder sign-off?

---

## Related horizons

Charters beyond the official `implement.md` §12 Phase 0–11 list:

| Doc | Topic |
|-----|--------|
| [Phase 13 — README](./PHASE_13_README.md) | International, analytics depth, ecosystem scale |
| [Phase 14 — README](./PHASE_14_README.md) | Electron.js desktop client (optional; see Phase 10) |
| [Phase 10 — Local / on-prem](./PHASE_10_PLAN.md) | `jpackage`, bundled Postgres, LAN installer |

---

<div align="center">

*Phase 11 ships the product; Phase 12 ships continuity — migration, moat, and years of operation.*

</div>
