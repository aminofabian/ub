# Butchery scale integration — v1

**Ticket 10 (Phase P4)** — first supported path for live weight at `/butcher`.

## v1 decision

| Item | Choice |
|------|--------|
| Transport | **Web Serial API** (Chrome / Edge desktop, HTTPS or localhost) |
| Protocol | **Generic continuous ASCII** — one weight per line |
| Baud rate | **9600** 8N1 (configurable in code; most retail scales default to 9600) |
| Stable weight | Software gate (600 ms, ±2 g) **or** hardware `S` / `ST` / `STABLE` prefix |
| Tare | Session tare in browser (per counter tab; not persisted) |

No local bridge or Electron helper in v1. Mobile native scale module is out of scope.

## Supported line formats (parser)

Examples that parse correctly:

```
   0.347 kg
1.250
S     1.250
ST,GS,     0.456kg
347 g
2.20 lb
```

The parser extracts the first numeric token and optional unit (`kg`, `g`, `lb`). Lines without a number are ignored.

## Hardware tested / expected compatible

Retail scales that expose a **USB virtual COM port** and stream **plain ASCII lines** (continuous mode) are the v1 target. Documented examples operators commonly use in East Africa / EU counter installs:

| Brand / family | Notes |
|----------------|-------|
| **Generic RS-232/USB POS scales** | Continuous output, 9600 baud — verify “auto print” / continuous mode in scale menu |
| **Mettler-Toledo** (bCounter, UC lines) | Enable continuous transmission; may use `S` stable prefix |
| **CAS** (ER/PD series) | Set PC/continuous output mode |
| **Avery Berkel** | Often needs continuous / print-on-stable disabled in favour of stream mode |

**Not in v1:** proprietary binary protocols, Bluetooth scales without serial bridge, label-print-only scales, multi-head abattoir indicators.

## Operator setup

1. Connect scale via USB (virtual COM).
2. Open `/butcher` in **Chrome or Edge** on desktop.
3. In the weight sheet, tap **Connect scale** and pick the COM port.
4. Place product; wait for **Stable** indicator.
5. Optional **Tare** for tray weight.
6. **Add to order** (blocked until stable when scale is connected).

## Tenant enablement

v1 is always available when Web Serial is supported. Future: gate via `businesses.settings.butcher.scale.enabled` (mirror `variableWeightBarcode`).

## Code

| Area | File |
|------|------|
| Parser + stable gate | `frontend/lib/butcher-scale.ts` |
| Web Serial hook | `frontend/hooks/use-butcher-serial-scale.ts` |
| Weight sheet UI | `frontend/components/butcher/butcher-cashier-workspace.tsx` |

## Known limits

- Browser must keep the tab open; no background serial on iOS Safari.
- User must grant port access once per browser profile (`getPorts()` reuses prior grant).
- Manual weight entry remains available when scale is disconnected or unsupported.
