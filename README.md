# RamSet — Hubitat Drivers & Apps

Local-first Hubitat Elevation drivers and apps. The theme across this repo: **keep
control on the hub** — no cloud accounts, no Apple hardware, no extra bridges where
it can be avoided. Everything installs through [Hubitat Package Manager](https://community.hubitat.com/t/release-hubitat-package-manager-hpm/94471) (HPM).

## Install

**Add the whole repository to HPM (recommended):**

1. Open **Hubitat Package Manager** → **Settings** → **Add a Custom Repository**.
2. Paste:
   ```
   https://raw.githubusercontent.com/RamSet/hubitat/main/repository.json
   ```
3. Then **Install** → search for any package below.

**Or install a single package:** in HPM choose **Install → From a URL** and paste that
package's manifest link from the table.

## Packages

### Thermostats / Climate
| Package | What it does | Install |
|---|---|---|
| **Ecobee HAP Thermostat (Local)** | Control an ecobee 100% locally over the HomeKit Accessory Protocol (HAP) — no cloud, no Apple hardware, no bridge. In-driver pairing, mode/setpoints/humidity/operating state, remote room sensors as child devices, real-time HomeKit event push. | [manifest](https://raw.githubusercontent.com/RamSet/hubitat/main/manifest-ecobee-hap.json) |
| **Local Ecobee Helpers** | Offline helper apps for the Ecobee HAP Thermostat: per-room vent control, open-contact HVAC pause with notifications, and humidifier control. Add as many helpers as needed under one parent. | [manifest](https://raw.githubusercontent.com/RamSet/hubitat/main/manifest-ecobee-helpers.json) |

### Integrations
| Package | What it does | Install |
|---|---|---|
| **HomeKit Import (Local)** | Import any LAN/Wi-Fi HomeKit (HAP) accessory into Hubitat — pair with a setup code and the driver auto-maps it to a Hubitat device. No cloud, no Apple hardware, no Homebridge. Runs on any hub (C5/C7/C8). | [manifest](https://raw.githubusercontent.com/RamSet/hubitat/main/manifest-homekit-import.json) |

### Air Quality / Weather
| Package | What it does | Install |
|---|---|---|
| **IKEA Air Quality to Hub Variables** | Publishes IKEA Vindstyrka (E2112) PM2.5, air quality, temperature, and humidity into Hub Variables for indoor and outdoor sensors. | [manifest](https://raw.githubusercontent.com/RamSet/hubitat/main/manifest-ikea-aqi.json) |
| **Air Quality Window Alerts** | Notifies when windows/doors are open and outdoor air quality is bad, naming every open sensor; holds alerts while a range hood or microwave fan is exhausting cooking fumes. | [manifest](https://raw.githubusercontent.com/RamSet/hubitat/main/manifest-aq-window-alerts.json) |
| **Virtual AQI Driver** | Virtual Air Quality Index (AQI) driver for Hubitat. | [manifest](https://raw.githubusercontent.com/RamSet/hubitat/main/manifest-aqi.json) |
| **Acuparse Weather Station** | Weather driver for polling Acuparse JSON data with capability integration, timestamp parsing, and optional extra fields. | [manifest](https://raw.githubusercontent.com/RamSet/hubitat/main/manifest-acuparse.json) |

### Irrigation
| Package | What it does | Install |
|---|---|---|
| **Zooz Sprinkler Scheduler** | Fully-local irrigation controller for Zooz ZEN16/ZEN17 relays — weather-aware scheduling, cycle & soak, moisture, pause-and-resume, and a verified hardware auto-off failsafe. | [manifest](https://raw.githubusercontent.com/RamSet/hubitat/main/manifest-zooz-sprinkler.json) |

### Safety / Monitoring
| Package | What it does | Install |
|---|---|---|
| **UV Exposure Alerts** | Warns about UV exposure when an exterior door is opened, with editable per-band messages and an optional siren strobe. | [manifest](https://raw.githubusercontent.com/RamSet/hubitat/main/manifest-uv-alerts.json) |
| **Network Monitor Health Check** | Monitors device availability via ping and status reporting. | [manifest](https://raw.githubusercontent.com/RamSet/hubitat/main/manifest-networkmonitor.json) |

### Convenience
| Package | What it does | Install |
|---|---|---|
| **Blinds Dusk Automation** | Lowers a room's blind at dusk, waits for an open window to close first (optional delay), and turns on the room light when occupied. One instance per room. | [manifest](https://raw.githubusercontent.com/RamSet/hubitat/main/manifest-blinds-dusk.json) |
| **Holiday Decorations** | Seasonal decoration schedules with shared wind and rain protection, darkness sensing, and a security-state veto. | [manifest](https://raw.githubusercontent.com/RamSet/hubitat/main/manifest-holiday-decorations.json) |
| **Fan Timer** | Run a fan for a set time from virtual switches, with an optional power-triggered run and a maximum-runtime backstop. | [manifest](https://raw.githubusercontent.com/RamSet/hubitat/main/manifest-fan-timer.json) |
| **Trash Reminder** | Reminds you to take the bins out, tracking alternating garbage and recycling weeks without needing a date/time device. | [manifest](https://raw.githubusercontent.com/RamSet/hubitat/main/manifest-trash-reminder.json) |

---

## Ecobee HAP Thermostat (Local) — notes

Full local control of an ecobee over HAP. Pair in the driver with the thermostat's
8-digit HomeKit setup code; the driver holds a persistent encrypted LAN session for
mode, setpoints, temperature, humidity, operating state, fan, comfort profiles, and
remote sensors (created automatically as child devices). Forum thread:
[Ecobee HAP Thermostat (Local)](https://community.hubitat.com/t/release-any-hub-c5-c7-c8-ecobee-no-cloud-hap-thermostat-local-direct-control-of-an-ecobee-thermostat-no-apple-hardware/164746).

### Comfort profiles, holds & Home/Away automation

Use `setComfortProfile("Home" | "Away" | "Sleep")` to select a climate. Set the
actual temperatures once on the ecobee (per climate) — the command only picks which
one is active. The per-climate targets are surfaced as attributes:
`homeHeatSetpoint`/`homeCoolSetpoint`, `awayHeatSetpoint`/`awayCoolSetpoint`,
`sleepHeatSetpoint`/`sleepCoolSetpoint`.

**How the ecobee treats it (important):** selecting a comfort profile creates a
**hold** of that climate, governed by the thermostat's own **Hold Action** preference
(_Settings → Preferences → Hold Action_):

- **"Until next scheduled activity"** (default) — the profile holds only until your
  next scheduled block, then the schedule resumes. e.g. choosing *Away* at 4 PM holds
  Away until the 10:30 PM *Sleep* block, then Sleep takes over.
- **"Until I change it"** — the profile holds indefinitely until you resume or pick
  another.

**HAP quirk to know for rules:** ecobee reports a *comfort-setting* hold over HomeKit
as a clean profile with **no** hold-end, so the driver reads `comfortProfile = Away`
but `onHold = false` / `holdEndsAt = none`. That's accurate to what HomeKit exposes —
ecobee doesn't send a comfort-hold's end date over HAP (a manual *temperature* hold
does). **Gate automations on `comfortProfile`, not on `onHold` / `holdEndsAt`.**

Return to the schedule with `resumeProgram()`; force a climate with
`setComfortProfile("Home")`.

**Example — Home/Away from Hubitat Safety Monitor (Rule Machine):**

```
Trigger:  HSM status changed
Actions:
  IF (HSM status is Armed Away) THEN
      setComfortProfile('Away') on <thermostat>
  ELSE
      resumeProgram() on <thermostat>      // or setComfortProfile('Home')
  END-IF
```

Set your Away/Home temperatures in the ecobee's comfort settings first — the rule
only picks the climate.

### Background refresh & the reconnect log line

Values HomeKit can't push (comfort profile, hold-end, per-profile setpoints, alerts,
sensor timers) are re-read on a background interval (default 5 min, configurable).

Since **0.19.0**, a configurable **Live-session safety-reconnect window** (default
360 s, above the refresh interval) keeps a quiet, idle thermostat connected instead
of reconnecting on a loop. Earlier builds logged `HAP: no update in Ns — reconnecting
to reconcile` as a **warning** every few minutes on an idle ecobee — harmless (an
idle thermostat pushes no events, tripping a fixed 120 s liveness timer), now demoted
to **info** and largely eliminated by the wider window. After updating via HPM, open
the device once and click **Save Preferences** for the new window to take effect.

---

**Author:** RamSet · **License:** [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0).
Provided as-is, without warranty of any kind; you assume all risk of controlling real
hardware (HVAC, irrigation, etc.) with these packages.
