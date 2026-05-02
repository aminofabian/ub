<div align="center">

# Phase 14 — README (Electron desktop)

### An **Electron** shell for cashier and back-office: **tray**, **deep links**, **auto-update**, and **native bridges** (printing, USB scanner helpers) — sitting **beside** or **after** the Java-first local installer in [Phase 10](./PHASE_10_PLAN.md).

[![Status](https://img.shields.io/badge/status-horizon-planned-lightgrey)](./README.md#-milestones--roadmap)
[![Depends on](https://img.shields.io/badge/depends%20on-Phase%2010%20%2F%2011-blue)](./PHASE_10_PLAN.md)
[![Stack](https://img.shields.io/badge/desktop-Electron-47848F)](https://www.electronjs.org/)

</div>

---

## What this is

**Phase 14** is **not** in [`README.md`](./README.md) or [`implement.md`](../implement.md) today. The blueprint’s primary on-prem story is **single JAR + bundled Postgres** and **`jpackage`** installers (`implement.md` §15, [Phase 10](./PHASE_10_PLAN.md)).

This README charters an **optional Electron** distribution for teams that want:

- a **familiar** desktop frame (tray, window management, OS integrations),
- **channel-based** updates (`electron-updater`) independent of the JVM release cadence **where ADR allows**,
- **main-process** bridges for **hardware** that is awkward in a bare browser PWA.

It does **not** replace the **authoritative** backend: **API + DB** remain the Spring Boot app (cloud or **local** `kiosk.local`-style). Electron is a **client** unless explicitly expanded later (see [Open questions](#open-questions)).

---

## Relationship to Phase 10 (`jpackage`)

| Approach | Role |
|----------|------|
| **Phase 10** | Bundled Postgres + Kiosk service + PWA served from JAR; **single** deliverable for **no-browser** trust model on LAN. |
| **Phase 14** | **Thin or thick** Electron **host** that loads the same **PWA** (`BrowserWindow` / `WebContentsView`) or opens **system browser** for admin — **connects** to local or cloud **base URL**. |

**ADR required:** one installed shop must not get **two conflicting** “sources of truth” (e.g. Electron pointing at cloud while JAR thinks local-only). Pick **either** primary desktop SKU per segment **or** one branded bundle that wraps both **with** one config story.

---

## Goals (indicative)

| Area | Intent |
|------|--------|
| **Shell** | Single-instance app, start-on-login optional, **system tray** with sync/backup status hooks (when hybrid). |
| **Navigation** | Configurable **server URL** (cloud tenant URL or `https://kiosk.local:8443`), persisted per profile; **deep links** (`kiosk://sale/...`) optional. |
| **Updates** | **Signed** releases (Windows EV, Apple notarization, Linux AppImage/deb rebundling policy — ADR). |
| **Hardware bridge** | ESC/POS or **privileged** USB paths via **preload** + **contextBridge** (no raw `node` in renderer for untrusted pages). |
| **Offline UX** | Surface **PWA service worker / queue** status; **clear** messaging when API unreachable (same semantics as Phase 4/9 offline). |
| **Security** | `contextIsolation: true`, hardened **CSP**, safe **window.open** handling, no mixed content to admin sessions. |

---

## Out of scope (initial)

- Rewriting **inventory / GL / sales** in Node — **backend stays Java**.
- Shipping Electron **without** **resolved** trust model for **self-signed LAN** certs (must document install-trust UX, same class of problem as Phase 10 §15.7).
- **Multi-tenant** **per** Electron binary without server-side enforcement — **forbidden**; tenant remains **server** truth.

---

## Dependencies

- Stable **HTTPS API** and **OpenAPI** contract (Phases **0–8+**).
- **PWA** build artefacts or documented URLs to load (Phase **4** / **9** polish helps).
- **[Phase 10](./PHASE_10_PLAN.md)** if the Electron app targets **local** server: networking, activation, and backup semantics should **align** not fork.

---

## Exit criteria (draft)

Agree with product before scheduling; starter bar:

- [ ] **Signed** installers for **≥2** OS targets (e.g. Windows + macOS) on an internal channel.
- [ ] **Security review** of **preload** surface and **update** pipeline (no unsigned feed).
- [ ] **E2E**: Electron → configured base URL → **login** → **one** sale path (against staging or local).
- [ ] **Runbook**: how support switches server URL, clears cache, and collects logs (`userData` path).

---

## Related docs

| Doc | Role |
|-----|------|
| [Phase 10 — Local / on-prem](./PHASE_10_PLAN.md) | Java + Postgres installer story |
| [Phase 9 — PWA polish](./PHASE_9_PLAN.md) | Install, camera, Bluetooth — loaded inside Electron |
| [Phase 13 — README](./PHASE_13_README.md) | Prior horizon (international / ecosystem) |
| [Blueprint §15 — Local deployment](../implement.md) | LAN, certs, modes |

---

## Open questions

1. **SKU**: Electron-only for **SaaS** cashiers, while **Phase 10** stays **on-prem** only — or **one** “Desktop” product with both backends?
2. **Embedded vs external** backend: does Electron ever **bundle** or **spawn** the JVM/Postgres (heavy), or always **remote** connect?
3. **WebView2 / Tauri** evaluation — is Electron the long-term pick or a **stopgap** for Windows print APIs?

---

*Phase 10 ships the **server on the desk**; Phase 14 ships a **purpose-built window** onto that server — or onto the cloud — without forking the books.*
