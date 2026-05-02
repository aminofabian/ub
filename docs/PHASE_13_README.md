<div align="center">

# Phase 13 — README

### Strategic horizon **after** [Phase 12](./PHASE_12_PLAN.md): international footprint, deeper analytics, and **ecosystem** scale — still one product, still one core codebase.

[![Status](https://img.shields.io/badge/status-horizon-planned-lightgrey)](./README.md#-milestones--roadmap)
[![Depends on](https://img.shields.io/badge/depends%20on-Phase%2012-blue)](./PHASE_12_PLAN.md)

</div>

---

## What this is

**Phase 13** is not in [`README.md`](./README.md) or [`implement.md`](../implement.md) today. This file is a **short charter** so post-migration, post-enterprise work has a named home without bloating Phase 12.

For execution detail, split Phase 13 into ADRs and issue epics when you schedule it.

---

## When Phase 13 makes sense

- Phase **11** (GA) and Phase **12** (migration + enterprise spine + sustainment) are **credible** in the field.
- You need **growth** or **compliance** that is **optional** for first revenue (multi-country, franchise, data warehouse, native apps).

---

## Goals (indicative)

| Theme | Examples |
|--------|-----------|
| **International** | Multi-currency, localised tax/VAT rule packs, regional payment rails beyond v1. |
| **Analytics & intelligence** | Warehouse export (Snowflake/BigQuery-style), curated semantic layer, demand signals — without breaking locked COGS. |
| **Client surfaces** | Optional **native** cashier or owner apps where PWA limits hurt. |
| **Ecosystem** | Public integration marketplace, partner certifications, revenue share — builds on Phase 8 API keys & webhooks. |
| **Operator / franchise** | White-label skins, master–store reporting, shared catalog templates — extends tenancy carefully. |

---

## Out of scope (unless ADR’d)

- Replacing the **modular monolith** without customer value.
- **Pièce de résistance** features that skip migration and core GA quality.

---

## Dependencies

- [Phase 12 — plan](./PHASE_12_PLAN.md) (migration tool, LTS, enterprise baselines).
- Stable **OpenAPI**, **RLS** / tenant model, and **observability** from earlier phases.

---

## Exit criteria (draft)

Agree with product before scheduling; starter bar:

- [ ] At least one **international** or **multi-currency** pilot **documented**.
- [ ] **Partner** integration path (Phase 8) used by **≥1** production partner without custom fork.
- [ ] **Analytics** export or **warehouse** sync **reconciles** to **journal** truth on a fixture tenant.

---

## Related docs

| Doc | Role |
|-----|------|
| [Milestones](./README.md#-milestones--roadmap) | Official phases **0–11** |
| [Phase 12 plan](./PHASE_12_PLAN.md) | Post-GA migration & platform |
| [Phase 14 — Electron desktop](./PHASE_14_README.md) | Optional Electron shell (post Phase 10/11) |
| [Architecture review](./ARCHITECTURE_REVIEW.md) | Constraints and risks |
| [Blueprint](../implement.md) | Behaviour source of truth |

---

*Phase 12 makes the business **portable** and **defensible**; Phase 13 makes it **expandable**.*
