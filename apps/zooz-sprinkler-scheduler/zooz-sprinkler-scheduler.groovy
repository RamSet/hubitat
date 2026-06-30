/**
 *  Zooz Sprinkler Scheduler — Hubitat app
 *  IMPORT URL: https://raw.githubusercontent.com/RamSet/hubitat/main/apps/zooz-sprinkler-scheduler/zooz-sprinkler-scheduler.groovy
 *
 *  Runs sprinkler zones via Zooz ZEN16 (3-relay) or ZEN17 (2-relay) 800LR
 *  multi-relay controllers — or any Hubitat device exposing the Switch
 *  capability. The hardware-watchdog push adapts to the relay count of
 *  each picked controller, so mixing models in one schedule is fine.
 *  Inspired by Plaid Systems'
 *  Spruce Scheduler — same overall flow (per-zone plant/soil/sprinkler types,
 *  weather-aware seasonal adjust, rain delay, optional pump/master) but
 *  hardware-agnostic so additional relays can be added without code changes.
 *
 *  Multi-instance: create one instance per watering schedule (e.g. "Front
 *  Lawn AM", "Veggie Garden", "Drip Lines"). Each instance owns its zones,
 *  days, time, and weather settings. Zone count is dynamic — no built-in cap.
 *
 *  Weather provider: Open-Meteo (https://open-meteo.com — free, no API key).
 *  Uses location.latitude / location.longitude from Hubitat Settings →
 *  Location for forecast queries.
 *
 *  Reference material:
 *    - Zooz official: How to use ZEN16 as a sprinkler controller on Hubitat
 *      https://www.support.getzooz.com/kb/article/371
 *    - Zooz advanced settings for ZEN16 on Hubitat
 *      https://www.support.getzooz.com/kb/article/376
 *    - Zooz Z-Wave config parameter spec (FW 1.03+):
 *        P1            Power-fail state         (recommend OFF for sprinklers)
 *        P6 / P8 / P10 R1/R2/R3 Auto Turn-Off timer (hardware watchdog)
 *        P15/P17/P19   Off-timer unit (0=min, 1=sec, 2=hr)
 *        P24           DC-motor interlock mode  (must be OFF for sprinklers)
 *      We push P6/P8/P10 from the "Hardware safety" page as a per-relay
 *      hardware watchdog independent of the app scheduler.
 *
 *  Copyright 2026 RamSet
 *  Licensed under the Apache License, Version 2.0
 */

definition(
    name: "Zooz Sprinkler Scheduler",
    namespace: "ramset",
    author: "RamSet",
    importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/main/apps/zooz-sprinkler-scheduler/zooz-sprinkler-scheduler.groovy",
    description: "Sprinkler schedule using Zooz ZEN16 / ZEN17 (or any Switch device) relays — Spruce-style logic, hardware-agnostic",
    category: "Green Living",
    // Resized via images.weserv.nl proxy — 618×618 source PNGs would render
    // gigantic in the iOS Hubitat app, which honours the native dimensions.
    iconUrl:   "https://images.weserv.nl/?url=raw.githubusercontent.com/hfg-gmuend/openmoji/master/color/618x618/1F4A7.png&w=64&h=64",
    iconX2Url: "https://images.weserv.nl/?url=raw.githubusercontent.com/hfg-gmuend/openmoji/master/color/618x618/1F4A7.png&w=128&h=128",
    iconX3Url: "https://images.weserv.nl/?url=raw.githubusercontent.com/hfg-gmuend/openmoji/master/color/618x618/1F4A7.png&w=192&h=192",
    singleInstance: false,
    oauth: true
)

mappings {
    path("/status")        { action: [GET: "apiStatus"]   }
    path("/run")           { action: [POST: "apiRun"]     }
    path("/stop")          { action: [POST: "apiStop"]    }
    path("/skip")          { action: [POST: "apiSkip"]    }
    path("/delay")         { action: [POST: "apiDelay"]   }
    path("/dashboard")     { action: [GET: "apiDashboard"]}
    path("/calendar.ics")  { action: [GET: "apiCalendar"] }
}

String getAppVersion() { return "v0.13.3 (2026-06)" }

// Simple vs Advanced interface. Simple shows only zones, schedule, weather and
// hardware safety; Advanced exposes everything (moisture, learning, sensors,
// notifications, dashboard, API, diagnostics, etc.). Settings are never deleted
// when hidden — flipping back to Advanced restores the full UI.
private boolean isAdvanced() { return (settings.uiMode ?: "simple") == "advanced" }

// Units follow the hub's Settings → Location temperature scale, so every
// weather threshold, label, forecast value and Open-Meteo request speaks the
// same units. Metric hub (°C) → mm / km/h; imperial hub (°F) → in / mph.
private boolean isMetric()  { return (location?.temperatureScale ?: "F") == "C" }
private String  tUnit()     { return isMetric() ? "°C"      : "°F" }
private String  tApiUnit()  { return isMetric() ? "celsius" : "fahrenheit" }
private String  rUnit()     { return isMetric() ? "mm"      : "in" }
private String  rApiUnit()  { return isMetric() ? "mm"      : "inch" }
private String  wUnit()     { return isMetric() ? "km/h"    : "mph" }
private String  wApiUnit()  { return isMetric() ? "kmh"     : "mph" }

// Zooz multi-relay model registry. Per-model lists of the Z-Wave parameter
// numbers we push for the hardware watchdog:
//   autoOff[i]   = parameter number that holds R(i+1)'s auto-off timer
//   unitParam[i] = parameter that holds that timer's unit (we set 0 = minutes)
// Add new models here when Zooz ships them.
@groovy.transform.Field static final Map ZOOZ_RELAY_MODELS = [
    "1": [name: "1 relay (single-relay Zooz)",          autoOff: [6],         unitParam: [15]],
    "2": [name: "2 relays (ZEN17 / ZEN52 / ZEN72)",     autoOff: [6, 8],      unitParam: [15, 17]],
    "3": [name: "3 relays (ZEN16 — default)",           autoOff: [6, 8, 10],  unitParam: [15, 17, 19]]
]

// =========================================================================
// Notification event keys & defaults
// =========================================================================
// Every notification flows through notify(key, context). Each event has:
//   - An on/off toggle  : settings."notifyEvent_${key}"      (default true unless noted)
//   - A custom message  : settings."notifyMsg_${key}"        (overrides DEFAULT)
//   - A Pushover priority override : settings."notifyPriority_${key}"
// Default messages support ${app}, ${zone}, ${reason}, ${duration}, ${cycle},
// ${remaining}, ${sensor}, ${count}, ${minutes}, ${hours}, ${detail}, ${planSize},
// ${seasonalMult}, ${until}, ${mode}, ${hsm}, ${delay}, ${tempF}, ${windMph},
// ${threshold}, ${tunit}, ${wunit}, ${elapsed}, ${watered}, ${scheduled},
// ${seasonal}, ${paused}, ${zones}, ${soak}, ${estTotal}, ${estWater},
// ${estSoak}. Missing variables render as the empty string.
@groovy.transform.Field static final Map NOTIFY_EVENTS = [
    // Lifecycle
    "schedule.start"   : [section: "Lifecycle",  default: '${app}: ▶ starting — ${planSize} zone(s), seasonal ×${seasonalMult} · ~${estTotal} total (water ${estWater} + soak ${estSoak})'],
    "schedule.finish"  : [section: "Lifecycle",  default: '${app}: ■ complete — ran ${elapsed} · watered ${watered} of ${scheduled} scheduled across ${zones} zone(s) · soak ${soak} · seasonal ${seasonal} · paused ${paused}'],
    "schedule.defer"   : [section: "Lifecycle",  default: '${app}: ⏳ holding — waiting for ${sensor} to clear before starting'],
    "schedule.deferResume": [section: "Lifecycle", default: '${app}: pause sensor clear — starting the held run in ${delay}'],
    "zone.start"       : [section: "Lifecycle",  default: '${app}: ▶ ${zone} for ${duration} (cycle ${cycle}/${totalCycles})', defaultOff: true],
    "zone.finish"      : [section: "Lifecycle",  default: '${app}: ■ ${zone} done', defaultOff: true],
    "pre-run"          : [section: "Lifecycle",  default: '${app}: schedule starts in ${minutes} minute(s) — clear the yard'],
    "error"            : [section: "Lifecycle",  default: '${app}: error — ${detail}'],

    // Skips
    "skip.manual"      : [section: "Skips",      default: '${app}: skipped — manual pause'],
    "skip.next"        : [section: "Skips",      default: '${app}: skipped — user-requested skip-next'],
    "skip.rain.weather": [section: "Skips",      default: '${app}: skipped — weather rain delay (${reason})'],
    "skip.rain.sensor" : [section: "Skips",      default: '${app}: skipped — rain sensor wet (${sensor})'],
    "skip.forced"      : [section: "Skips",      default: '${app}: skipped — forced rain delay until ${until}'],
    "skip.quiet"       : [section: "Skips",      default: '${app}: skipped — quiet hours active'],
    "skip.mode"        : [section: "Skips",      default: '${app}: skipped — Hubitat mode is ${mode}'],
    "skip.hsm"         : [section: "Skips",      default: '${app}: skipped — HSM is ${hsm}'],
    "skip.pause"       : [section: "Skips",      default: '${app}: skipped — pause sensor active (${sensor})'],
    "skip.budget"      : [section: "Skips",      default: '${app}: ${zone} skipped — weekly budget exhausted'],
    "skip.moisture"    : [section: "Skips",      default: '${app}: ${zone} skipped — soil already at ${moisture}% (target ${target}%)'],
    "skip.frost"       : [section: "Skips",      default: '${app}: skipped — overnight low ${tempF}${tunit} (threshold ${threshold}${tunit})'],
    "skip.cold"        : [section: "Skips",      default: '${app}: skipped — today\'s high ${tempF}${tunit} (threshold ${threshold}${tunit})'],
    "skip.wind"        : [section: "Skips",      default: '${app}: skipped — max wind ${windMph} ${wunit} (threshold ${threshold} ${wunit})'],
    "skip.coord"       : [section: "Skips",      default: '${app}: skipped — coordination switch held by another schedule (gave up after ${count} retries)'],

    // Pause & resume
    "pause.activate"   : [section: "Pause",      default: '${app}: PAUSED at ${zone} (${remaining} remaining) — ${reason}'],
    "pause.clear"      : [section: "Pause",      default: '${app}: pause sensors clear — resuming in ${delay}'],
    "pause.resume"     : [section: "Pause",      default: '${app}: resumed ${zone} (${remaining} left)'],
    "rain.mid.stop"    : [section: "Pause",      default: '${app}: rain detected (${sensor}) — stopped mid-run'],

    // Sensors
    "sensor.rain.wet"  : [section: "Sensors",    default: '${app}: rain sensor ${sensor} → WET',  defaultOff: true],
    "sensor.rain.dry"  : [section: "Sensors",    default: '${app}: rain sensor ${sensor} → DRY',  defaultOff: true],
    "sensor.pause.on"  : [section: "Sensors",    default: '${app}: pause sensor ${sensor} active', defaultOff: true],
    "sensor.pause.off" : [section: "Sensors",    default: '${app}: pause sensor ${sensor} clear',  defaultOff: true],

    // Hardware & watchdog
    "hardware.push"    : [section: "Hardware",   default: '${app}: Zooz relay watchdog set to ${minutes}min on ${count} controller(s)'],
    "watchdog.stale"   : [section: "Hardware",   default: '${app}: ${sensor} unreachable for ${hours}h'],

    // Test / manual
    "test.run"         : [section: "Test",       default: '${app}: testing ${zone} for ${duration}', defaultOff: true],

    // Moisture-aware
    "moisture.earlyStop": [section: "Sensors",    default: '${app}: ${zone} early-stopped — soil reached ${moisture}% (target ${target}%) after ${duration}'],
    "moisture.adapted":   [section: "Sensors",    default: '${app}: ${zone} adapted ${baseMin}m → ${adjMin}m (soil ${moisture}%, dryness ${dryness}×)', defaultOff: true],
    "moisture.learned":   [section: "Sensors",    default: '${app}: ${zone} learning — ${delta}%/min over ${runMin}m run', defaultOff: true],

    // Fertilizer
    "fertilizer.armed":    [section: "Lifecycle",  default: '${app}: ${zone} starting in fertilizer mode (${cycles}× ${perCycleMin}m, soak ${soakMin}m)'],
    "fertilizer.disarmed": [section: "Lifecycle",  default: '${app}: ${zone} fertilizer mode complete and disarmed', defaultOff: true],

    // Manual triggers via exposed child Virtual Switches
    "zone.manualStart":    [section: "Lifecycle",  default: '${app}: MANUAL ▶ ${zone} for ${duration} (switch ${switch})'],
    "zone.manualEnd":      [section: "Lifecycle",  default: '${app}: MANUAL ■ ${zone} (turned off)'],
    "zone.manualTimeout":  [section: "Lifecycle",  default: '${app}: MANUAL ■ ${zone} (manual-on timer expired)'],
    "zone.manualBlocked":  [section: "Lifecycle",  default: '${app}: manual ${zone} blocked — ${reason}']
]

@groovy.transform.Field static final List NOTIFY_PRIORITIES = ["default", "-2 silent", "-1 quiet", "0 normal", "1 high", "2 emergency"]
// Icon URL builder. The Hubitat mobile app honours the source PNG's native
// dimensions — OpenMoji ships them at 618×618, which makes them gigantic on
// phones. We route through images.weserv.nl (free, no auth) to resize to 72px,
// matching the size the web UI scales them to with CSS.
private String openmoji(String code) {
    String src = "raw.githubusercontent.com/hfg-gmuend/openmoji/master/color/618x618/${code}.png"
    return "https://images.weserv.nl/?url=${src}&w=72&h=72"
}

// =========================================================================
// Preferences (multi-page)
// =========================================================================

preferences {
    page(name: "mainPage")
    page(name: "zoneListPage")
    page(name: "zoneDetailPage")
    page(name: "schedulePage")
    page(name: "weatherPage")
    page(name: "rainSensorPage")
    page(name: "pauseSensorPage")
    page(name: "pumpPage")
    page(name: "hardwarePage")
    page(name: "historyPage")
    page(name: "dashboardPage")
    page(name: "notificationPage")
    page(name: "diagnosticsPage")
    page(name: "restrictionsPage")
    page(name: "previewPage")
    page(name: "backupPage")
    page(name: "apiPage")
    page(name: "notifyEventsPage")
    page(name: "moisturePage")
    page(name: "aboutPage")
    page(name: "exposurePage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Zooz Sprinkler Scheduler — ${getAppVersion()}",
                install: true, uninstall: true) {
        // Warnings banner: surface the most actionable config issues at the
        // top of every visit to the main page so they aren't buried.
        List<String> banners = quickWarnings()
        if (banners) {
            section {
                paragraph "<div style='background:#fff8e1;border-left:4px solid #f39c12;padding:8px;'>" +
                          "⚠ Attention<ul style='margin:4px 0'>" +
                          banners.collect { "<li>${it}</li>" }.join("") +
                          "</ul></div>"
            }
        }
        section {
            label title: "Schedule name (shown in the Apps list)",
                  required: true
        }
        section("App mode") {
            input name: "uiMode", type: "enum", title: "Interface",
                  options: ["simple":   "Simple — zones, schedule & weather only",
                            "advanced": "Advanced — every feature"],
                  defaultValue: "simple", required: true, submitOnChange: true
            if (!isAdvanced()) {
                paragraph "<small>Simple mode keeps things to the basics: zones, schedule (time & frequency) and weather skipping. The extras — soil-moisture/learning, rain & pause sensors, pump, notifications, dashboard, API, restrictions, diagnostics — stay configured but hidden. Switch to Advanced any time to see them.</small>"
            }
        }
        section("Configuration") {
            href name: "zoneListPage", title: "Zones (${zoneCount()})", page: "zoneListPage",
                 image: openmoji("1F33F"),
                 description: zoneSummaryString()
            href name: "schedulePage", title: "Schedule", page: "schedulePage",
                 image: openmoji("23F1"),
                 description: scheduleSummaryString()
            href name: "weatherPage", title: "Weather (rain delay & seasonal adjust)", page: "weatherPage",
                 image: openmoji("1F327"),
                 description: weatherSummaryString()
            href name: "hardwarePage", title: "Hardware safety (Zooz relay watchdog)", page: "hardwarePage",
                 image: openmoji("1F6E1"),
                 description: hardwareSafetySummaryString()
            href name: "exposurePage", title: "Zone switches (HomeKit / Rules)", page: "exposurePage",
                 image: openmoji("1F39B"),
                 description: exposureSummaryString()
            if (isAdvanced()) {
                href name: "rainSensorPage", title: "Rain sensors (binary wet/dry)", page: "rainSensorPage",
                     image: openmoji("1F4A7"),
                     description: rainSensorSummaryString()
                href name: "pauseSensorPage", title: "Pause sensors (contacts / switches)", page: "pauseSensorPage",
                     image: openmoji("23F8"),
                     description: pauseSensorSummaryString()
                href name: "pumpPage", title: "Pump / Master valve", page: "pumpPage",
                     image: openmoji("1F527"),
                     description: pumpSummaryString()
                href name: "notificationPage", title: "Notifications", page: "notificationPage",
                     image: openmoji("1F4E2"),
                     description: notificationSummaryString()
                href name: "historyPage", title: "Run history", page: "historyPage",
                     image: openmoji("1F4DC"),
                     description: historySummaryString()
                href name: "dashboardPage", title: "Dashboard tile", page: "dashboardPage",
                     image: openmoji("1F4F1"),
                     description: dashboardSummaryString()
                href name: "restrictionsPage", title: "Restrictions (quiet hours / mode / HSM)", page: "restrictionsPage",
                     image: openmoji("1F6AB"),
                     description: restrictionsSummaryString()
                href name: "previewPage", title: "Next 7 days preview", page: "previewPage",
                     image: openmoji("1F4C5"),
                     description: previewSummaryString()
                href name: "backupPage", title: "Backup / restore configuration", page: "backupPage",
                     image: openmoji("1F4BE"),
                     description: backupSummaryString()
                href name: "apiPage", title: "External JSON API", page: "apiPage",
                     image: openmoji("1F517"),
                     description: apiSummaryString()
                href name: "moisturePage", title: "Moisture learning", page: "moisturePage",
                     image: openmoji("1F33F"),
                     description: moistureSummaryString()
                href name: "diagnosticsPage", title: "Diagnostics & test runs", page: "diagnosticsPage",
                     image: openmoji("1F50D"),
                     description: diagnosticsSummaryString()
            }
            href name: "aboutPage", title: "About / changelog", page: "aboutPage",
                 image: openmoji("2139"),
                 description: "Version ${getAppVersion()}"
        }
        section("Run now / pause") {
            input name: "btnRunNow",  type: "button", title: "Run schedule now"
            input name: "btnStopAll", type: "button", title: "Stop all zones now"
            paragraph "Status: ${runStatusString()}"
            input name: "pauseHours", type: "number", title: "Pause schedule for N hours (0 = active)",
                  range: "0..72", required: false, defaultValue: 0, submitOnChange: true
        }
        if (isAdvanced()) {
            section("Logging") {
                input name: "debugOutput", type: "bool", title: "Enable debug logging",
                      description: "Auto-turns off after 30 minutes", defaultValue: false
                input name: "descTextEnable", type: "bool", title: "Enable description text logging",
                      defaultValue: true
            }
        }
        section {
            paragraph "<div style='font-size:0.85em;color:#666'>" +
                      "Zooz Sprinkler Scheduler ${getAppVersion()} · " +
                      "<a href='https://www.support.getzooz.com/kb/article/371'>Zooz KB #371 (sprinkler on Hubitat)</a> · " +
                      "<a href='https://www.support.getzooz.com/kb/article/376'>KB #376 (advanced settings)</a> · " +
                      "weather by <a href='https://open-meteo.com'>Open-Meteo</a>" +
                      "</div>"
        }
    }
}

private List<String> quickWarnings() {
    List<String> w = []
    Integer n = zoneCount()
    if (n == 0) w << "No zones configured. Open the Zones page to add at least one."
    int withSw = 0
    for (int i = 1; i <= n; i++) if (settings."zone${i}Switch") withSw++
    if (n > 0 && withSw == 0) w << "No zones have a Switch device assigned yet."
    if (settings.scheduleEnabled && !settings.scheduleStartTime) {
        w << "Schedule is enabled but the Window 1 start time is missing."
    }
    if (settings.scheduleEnabled && !isIntervalMode() && !settings.scheduleDays) {
        w << "Schedule is enabled but no days of week are selected."
    }
    if (settings.scheduleEnabled && isIntervalMode() && !(settings.scheduleIntervalDays)) {
        w << "Schedule is enabled but the every-N-days interval is missing."
    }
    if ((settings.rainDelayEnabled || settings.seasonalEnabled) && (!location?.latitude || !location?.longitude)) {
        w << "Weather is enabled but Hubitat location lat/long is not set (Settings → Location)."
    }
    if (!settings.hwZen16Parents) {
        w << "Hub-independent hardware watchdog is OFF. Recommended: open Hardware safety and pick your Zooz relay parent device(s) (ZEN16, ZEN17, etc.)."
    }
    if (state.skipNextRun) w << "Skip-next is armed: the next scheduled run will be skipped."
    Long rd = (state.forcedRainDelayUntilMs ?: 0L) as long
    if (rd > now()) {
        String until = new Date(rd).format("yyyy-MM-dd HH:mm", location?.timeZone ?: TimeZone.getDefault())
        w << "Forced rain delay until ${until} — runs are blocked."
    }
    return w
}

def zoneListPage() {
    dynamicPage(name: "zoneListPage", title: "Zones") {
        section {
            paragraph "Add as many zones as you have relays. Each zone maps to a single Hubitat Switch device — typically a child of your Zooz multi-relay controller (ZEN16 = 3 relays, ZEN17 = 2 relays, etc.), but anything with the Switch capability works."
            input name: "zoneCountPref", type: "number", title: "Number of zones",
                  range: "0..64", defaultValue: 0, submitOnChange: true, required: true
        }
        Integer n = (settings.zoneCountPref ?: 0) as int
        if (n > 0) {
            section("Configure each zone") {
                paragraph "Tap a zone to set its switch device, run time, plant/soil/sprinkler type and the physical port label so you know which Zooz relay drives it."
                for (int i = 1; i <= n; i++) {
                    String label = settings."zone${i}Name" ?: "Zone ${i}"
                    String hint  = zoneOneLineSummary(i)
                    href name: "zoneDetailPage_${i}", page: "zoneDetailPage",
                         params: [zoneId: i],
                         title: "${i}. ${label}",
                         image: openmoji(zoneTypeIcon(settings."zone${i}Plant")),
                         description: hint
                }
            }
        }
    }
}

def zoneDetailPage(Map params = [:]) {
    Integer zid = (params?.zoneId ?: state.editingZone ?: 1) as int
    state.editingZone = zid
    dynamicPage(name: "zoneDetailPage", title: "Zone ${zid}") {
        section("Identity") {
            input name: "zone${zid}Name",      type: "text",   title: "Zone name (e.g. \"Front Lawn N\")",
                  required: true, submitOnChange: true
            if (isAdvanced()) {
                input name: "zone${zid}PortLabel", type: "text",
                      title: "Port label",
                      description: "Free text — e.g. \"ZEN16 #1 R2 (15A)\" or \"ZEN17 #2 R1 (20A)\". Helps you match this zone to a physical relay during wiring audits.",
                      required: false
            }
            input name: "zone${zid}Enabled",   type: "bool",   title: "Zone enabled in schedule",
                  defaultValue: true
        }
        section("Relay (switch device)") {
            input name: "zone${zid}Switch", type: "capability.switch",
                  title: "Switch device",
                  description: "Zooz ZEN16/ZEN17 relay child — or any Switch device",
                  required: true, multiple: false, submitOnChange: true
            def sw = settings."zone${zid}Switch"
            if (sw) {
                paragraph "Selected device: ${sw.displayName}<br>" +
                          "Current state: ${sw.currentValue('switch') ?: 'unknown'}"
            }
        }
        section("Run time") {
            if (isAdvanced()) {
                input name: "zone${zid}RuntimeMode", type: "enum",
                      title: "How to compute base runtime",
                      options: [
                        "fixed":  "Fixed: N minutes per cycle",
                        "weekly": "Weekly target: total minutes per week ÷ days per week (Spruce-style)"
                      ],
                      defaultValue: "fixed", submitOnChange: true
            }
            if (isAdvanced() && (settings."zone${zid}RuntimeMode" ?: "fixed") == "weekly") {
                input name: "zone${zid}WeeklyMinutes", type: "number",
                      title: "Total minutes per week (target)",
                      description: "Distributed across the days-per-week below. Seasonal scaling still applies.",
                      range: "1..1000", defaultValue: 30, required: true
                input name: "zone${zid}DaysPerWeek", type: "number",
                      title: "Days per week (how often this zone gets watered)",
                      range: "1..7", defaultValue: 3, required: true
                paragraph "Effective base = ${zoneEffectiveBaseString(zid)} per cycle."
            } else {
                input name: "zone${zid}RunMinutes", type: "number",
                      title: "Base run time per cycle (minutes)",
                      description: isAdvanced() ? "Seasonal weather adjust will scale this if enabled in Weather settings." : "Minutes this zone runs each time.",
                      range: "1..240", defaultValue: 10, required: true
            }
            if (isAdvanced()) {
                input name: "zone${zid}CycleSoak", type: "enum",
                      title: "Cycle & soak (split run to reduce runoff)",
                      options: ["1": "No cycle (run all at once)",
                                "2": "Cycle 2× (run / soak / run)",
                                "3": "Cycle 3× (run / soak / run / soak / run)"],
                      defaultValue: "1"
                input name: "zone${zid}SoakMinutes", type: "number",
                      title: "Soak time between cycles (minutes)",
                      range: "1..60", defaultValue: 10
            }
        }
        if (isAdvanced()) {
        section("Exposed switch override") {
            paragraph "When the global zone-switch feature is on, this zone exposes a child Virtual Switch. Override the manual-on auto-off timer for THIS zone only (blank = use the global default)."
            input name: "zone${zid}ManualTimerMin", type: "number",
                  title: "Manual-on timer (minutes) for this zone",
                  range: "1..240", required: false
        }

        section("Fertilizer mode (one-shot)") {
            paragraph "Arming fertilizer mode changes the NEXT run only: zone runs as short bursts with long soak intervals for dilution and uniform uptake. Auto-disarms after the run completes."
            input name: "zone${zid}FertArmed", type: "bool",
                  title: "Arm fertilizer mode for the next run of this zone",
                  defaultValue: false
            input name: "zone${zid}FertBurstMin", type: "number",
                  title: "Burst length (minutes)",
                  range: "1..20", defaultValue: 2
            input name: "zone${zid}FertBurstCount", type: "number",
                  title: "Number of bursts",
                  range: "2..10", defaultValue: 3
            input name: "zone${zid}FertSoakMin", type: "number",
                  title: "Soak between bursts (minutes)",
                  range: "1..60", defaultValue: 15
        }

        section("Weekly budget cap (optional)") {
            input name: "zone${zid}WeeklyCapMinutes", type: "number",
                  title: "Maximum minutes per ISO week (0 = no cap)",
                  description: "Hard weekly ceiling. Over-budget runs are clipped; if nothing left, zone is skipped.",
                  range: "0..1000", defaultValue: 0
            paragraph "Used this week: ${(state.zoneMinutesThisWeek ?: [:])[zid.toString()] ?: 0} min"
        }
        section("Landscape (informational + seasonal adjust)") {
            input name: "zone${zid}Plant", type: "enum",
                  title: "Plant type",
                  options: ["Lawn", "Garden", "Flowers", "Shrubs", "Trees", "Xeriscape", "New Plants"],
                  defaultValue: "Lawn"
            input name: "zone${zid}Sprinkler", type: "enum",
                  title: "Sprinkler head type",
                  options: ["Spray", "Rotor", "Drip", "Master Valve", "Pump"],
                  defaultValue: "Spray"
            input name: "zone${zid}Soil", type: "enum",
                  title: "Soil type",
                  options: ["Loam", "Sand", "Clay", "Slope"],
                  defaultValue: "Loam"
        }
        section("Moisture-aware watering (optional)") {
            paragraph "Pick any device with a humidity (or moisture) attribute — most Hubitat soil sensors. Choose the exact attribute below."
            input name: "zone${zid}MoistureSensor", type: "capability.relativeHumidityMeasurement",
                  title: "Soil moisture sensor",
                  description: "Optional — humidity/moisture sensor",
                  required: false, multiple: false, submitOnChange: true
            if (settings."zone${zid}MoistureSensor") {
                input name: "zone${zid}MoistureMode", type: "enum",
                      title: "Moisture mode",
                      options: [
                        "off":       "Off (sensor reading ignored — still recorded)",
                        "skip":      "Skip-only: skip the whole zone if moisture is already above target",
                        "earlyStop": "Early stop: run normally but cut the cycle when target is reached mid-run",
                        "adapt":     "Adaptive: scale baseline runtime by current dryness (also early-stops at target)",
                        "learn":     "Adaptive + learning: use historical pre/post deltas to predict runtime to target"
                      ],
                      defaultValue: "skip", submitOnChange: true
                input name: "zone${zid}MoistureTarget", type: "number",
                      title: "Target moisture % (zone is \"wet enough\" at or above this)",
                      range: "0..100", defaultValue: 50, required: false
                input name: "zone${zid}MoistureMin", type: "number",
                      title: "Dry-bottom moisture % (zone is \"bone dry\" at or below this)",
                      description: "Adaptive scaling uses this. Lower = more water when dry.",
                      range: "0..100", defaultValue: 20, required: false
                String attrPick = settings."zone${zid}MoistureAttribute" ?: "humidity"
                input name: "zone${zid}MoistureAttribute", type: "enum",
                      title: "Sensor attribute to read",
                      options: ["humidity": "humidity (default — most Hubitat soil moisture sensors)",
                                "moisture": "moisture (some specialised drivers)"],
                      defaultValue: "humidity"
                def sw = settings."zone${zid}MoistureSensor"
                def cur = sw?.currentValue(attrPick)
                paragraph "Current reading: ${cur != null ? "${cur}%" : "n/a"} · " +
                          "${zoneAdaptiveStatusString(zid)}"
            }
        }
        } // end advanced-only zone sections
        section {
            paragraph "Last run: ${state.lastRunByZone?.get(zid as String) ?: 'never'}"
        }
    }
}

def schedulePage() {
    dynamicPage(name: "schedulePage", title: "Schedule") {
        section("When to run") {
            input name: "scheduleEnabled", type: "bool",
                  title: "Schedule enabled",
                  defaultValue: true
            input name: "scheduleMode", type: "enum",
                  title: "Schedule by",
                  options: ["dow": "Days of week", "interval": "Every N days"],
                  defaultValue: "dow", required: true, submitOnChange: true
            if ((settings.scheduleMode ?: "dow") == "interval") {
                input name: "scheduleIntervalDays", type: "number",
                      title: "Run every N days (1 = daily, 2 = every other day, 3 = every third day …)",
                      range: "1..30", defaultValue: 2, required: true, submitOnChange: true
                input name: "scheduleIntervalAnchor", type: "date",
                      title: "Start date — the cycle counts forward from this day (blank = today)",
                      required: false, submitOnChange: true
                paragraph intervalPreviewHint()
            } else {
                input name: "scheduleDays", type: "enum",
                      title: "Days of week",
                      options: ["MON","TUE","WED","THU","FRI","SAT","SUN"],
                      multiple: true, required: true,
                      defaultValue: ["MON","WED","FRI"]
            }
            paragraph "Watering windows. Each window starts a full sweep through all enabled zones. Skip slots 2 and 3 if you only want one start time."
            input name: "scheduleStartTime",  type: "time",
                  title: "Window 1 start time", required: true
            input name: "scheduleStartTime2", type: "time",
                  title: "Window 2 start time (optional)", required: false
            input name: "scheduleStartTime3", type: "time",
                  title: "Window 3 start time (optional)", required: false
        }
        if (isAdvanced()) {
        section("Behaviour") {
            input name: "scheduleOrder", type: "enum",
                  title: "Zone ordering",
                  options: ["sequential": "Sequential (by zone number)",
                            "random":     "Random order each run"],
                  defaultValue: "sequential"
            input name: "scheduleBetweenZoneSec", type: "number",
                  title: "Delay between zones (seconds)",
                  range: "0..600", defaultValue: 10
        }
        section("Auto-stagger across schedules") {
            paragraph "If you have multiple instances of this app (one per yard area), they'd all start at their configured times — possibly overlapping and overwhelming pipe pressure. Pick a single Virtual Switch shared between every instance: each instance checks it before starting, turns it ON for the duration of its run, and OFF at the end. Other instances scheduled at the same time see the lock and defer."
            input name: "coordSwitch", type: "capability.switch",
                  title: "Shared coordination switch (any Virtual Switch — create one called \"Sprinkler Lock\" and pick the same device in every instance)",
                  required: false, multiple: false
            input name: "coordDeferSec", type: "number",
                  title: "Wait this many seconds before retrying if locked",
                  range: "10..600", defaultValue: 60
            input name: "coordMaxRetries", type: "number",
                  title: "Maximum retries before giving up and skipping this run",
                  range: "0..30", defaultValue: 10
        }

        section("Software failsafe (per-zone cap)") {
            input name: "scheduleMaxRunMinutes", type: "number",
                  title: "Maximum single zone run time (minutes)",
                  description: "Caps each zone's adjusted runtime. Set Hardware safety auto-off to this +5min for backup.",
                  range: "1..240", defaultValue: 60
        }
        } // end advanced-only schedule sections
    }
}

def weatherPage() {
    dynamicPage(name: "weatherPage", title: "Weather (Open-Meteo)") {
        section("Provider") {
            paragraph "Weather data comes from <a href='https://open-meteo.com'>Open-Meteo</a>. " +
                      "No API key needed. Uses your hub's configured location " +
                      "(Settings → Location → ${location?.latitude}, ${location?.longitude})."
        }
        section("Rain delay") {
            input name: "rainDelayEnabled", type: "bool",
                  title: "Skip schedule when rain is expected or has fallen recently",
                  defaultValue: true
            input name: "rainPopThreshold", type: "number",
                  title: "Skip if today's rain probability ≥ this %",
                  description: "0 = skip on any chance.",
                  range: "0..100", defaultValue: 60
            input name: "rainAmountThreshold", type: "decimal",
                  title: "Skip if forecast or past-24h rain exceeds this much (${rUnit()})",
                  range: isMetric() ? "0..130" : "0..5",
                  defaultValue: isMetric() ? 5.0 : 0.2
        }
        section("Smart skips (temperature / wind)") {
            paragraph "Skip runs based on forecast extremes — useful for off-season frost protection and avoiding water loss in high wind. Units follow your hub (currently ${tUnit()} / ${wUnit()})."
            input name: "smartSkipFrostF", type: "number",
                  title: "Skip if overnight low (today) is below this ${tUnit()} (frost protection)",
                  description: isMetric() ? "Typical: 2 (freezing risk) or 0 (hard freeze)." : "Typical: 36 (below freezing risk) or 32 (hard freeze).",
                  range: isMetric() ? "-40..16" : "-40..60", required: false
            input name: "smartSkipColdHighF", type: "number",
                  title: "Skip if today's high is below this ${tUnit()} (winter mode)",
                  description: isMetric() ? "Typical: 10 (cool-season grass stops growing) or 4 (no point watering)." : "Typical: 50 (cool-season grass stops growing) or 40 (no point watering anything).",
                  range: isMetric() ? "-40..49" : "-40..120", required: false
            input name: "smartSkipWindMph", type: "number",
                  title: "Skip if today's max wind is above this ${wUnit()} (atomized spray loss)",
                  description: isMetric() ? "Typical: 24 km/h causes drift on spray heads; 40 km/h is severe." : "Typical: 15 mph causes significant drift on spray heads; 25 mph is severe.",
                  range: isMetric() ? "0..160" : "0..100", required: false
        }

        section("Seasonal adjust") {
            input name: "seasonalEnabled", type: "bool",
                  title: "Scale runtimes by upcoming weather",
                  description: "Hotter/drier → longer; cooler/wetter → shorter.",
                  defaultValue: true
            input name: "seasonalMaxPct", type: "number",
                  title: "Cap the seasonal scaling at ±%",
                  description: "Prevents extreme swings. 50 means runtime scales between 50% and 150% of baseline.",
                  range: "0..100", defaultValue: 50
        }
        section("Optional external rain gauge") {
            input name: "rainSensorDevice", type: "capability.relativeHumidityMeasurement",
                  title: "Local weather station / rain gauge (must expose a numeric \"rainToday\" attribute, in ${rUnit()})",
                  required: false
        }
        section("Today's forecast (live from Open-Meteo)") {
            paragraph forecastPreviewHtml()
            input name: "btnRefreshForecast", type: "button", title: "Refresh forecast cache"
        }
    }
}

private String forecastPreviewHtml() {
    Map om = fetchWeather()
    if (!om?.daily?.time) return "<i>No forecast data — check hub location lat/lon.</i>"
    String todayISO = new Date().format("yyyy-MM-dd", location?.timeZone ?: TimeZone.getDefault())
    int idx = (om.daily.time as List).findIndexOf { it == todayISO }
    if (idx < 0) idx = (om.daily.time.size() > 1) ? 1 : 0
    StringBuilder sb = new StringBuilder("<table style='font-family:monospace;font-size:0.9em'>")
    sb << "<tr><th align='left'>Day</th><th align='left'>High ${tUnit()}</th><th align='left'>POP %</th><th align='left'>Precip ${rUnit()}</th><th align='left'>Sunrise</th><th align='left'>Sunset</th></tr>"
    int n = Math.min(5, om.daily.time.size() - idx)
    for (int j = 0; j < n; j++) {
        int i = idx + j
        sb << "<tr>"
        sb << "<td>${om.daily.time[i]}${j == 0 ? ' (today)' : ''}</td>"
        sb << "<td>${(om.daily.temperature_2m_max ?: [])[i] ?: '?'}</td>"
        sb << "<td>${(om.daily.precipitation_probability_mean ?: [])[i] ?: '?'}</td>"
        sb << "<td>${(om.daily.precipitation_sum ?: [])[i] ?: '?'}</td>"
        sb << "<td>${(om.daily.sunrise ?: [])[i]?.tokenize('T')?.getAt(1) ?: '?'}</td>"
        sb << "<td>${(om.daily.sunset  ?: [])[i]?.tokenize('T')?.getAt(1) ?: '?'}</td>"
        sb << "</tr>"
    }
    sb << "</table>"
    sb << "<br><i>Past-24h precipitation: ${String.format('%.2f', omPrecipLast24h(om))} ${rUnit()}"
    if (state.__omCacheAt) {
        long ageSec = (now() - (state.__omCacheAt as long)) / 1000
        sb << " · cache age ${ageSec}s"
    }
    sb << "</i>"
    return sb.toString()
}

def rainSensorPage() {
    dynamicPage(name: "rainSensorPage", title: "Rain sensors") {
        section {
            paragraph "Any combination of the three options below can be used together. If ANY of them detects rain, the schedule is skipped — and if a run is in progress and \"stop mid-run\" is on, it is stopped immediately."
        }

        section("Water sensors (wet/dry capability)") {
            paragraph "For dedicated rain detectors that report water/wet/dry — e.g. wireless leak sensors, weather-station rain pucks, or any Hubitat device with the Water Sensor capability."
            input name: "rainSensorWaterDevices", type: "capability.waterSensor",
                  title: "Rain water sensors",
                  multiple: true, required: false, submitOnChange: true
        }

        section("Contact-based rain sensors (incl. Zooz relay inputs)") {
            paragraph "Most rain sensors sold for irrigation are dry-contact devices — two terminals that get bridged when water collects on the sensor. Wire one to a Zooz multi-relay input port: ZEN16 has Sw1/Sw2/Sw3, ZEN17 has Sw1/Sw2. The Hubitat driver exposes each input as a child Contact Sensor — pick that child here. Any other Contact Sensor device (door/window, leaf-wetness sensor with contact output, etc.) works too."
            input name: "rainSensorContactDevices", type: "capability.contactSensor",
                  title: "Rain contact sensors",
                  multiple: true, required: false, submitOnChange: true
            input name: "rainSensorContactWetState", type: "enum",
                  title: "Contact state when raining",
                  options: ["closed": "closed (most rain sensors — contacts close when wet)",
                            "open":   "open   (normally-closed sensors)"],
                  defaultValue: "closed"
            paragraph "Wiring tip (ZEN16 / ZEN17): the rain sensor's two leads go to a Sw input and its paired GND/COM. Set parameter P2 (Sw1 input type — or P3/P4 for Sw2/Sw3 on ZEN16) to a sensor/contact type so the controller reports the input, then pick the resulting Sw child device above."
            paragraph "⚠ Confirmed gotcha: after you change an Sw input to a sensor type, the ZEN16/ZEN17 will NOT actually report it (wet/dry stays stuck) until you EXCLUDE + RE-INCLUDE the relay. The parameter takes effect and the input can even drive a relay, but the sensor-report association is only created during Z-Wave inclusion — Save/Configure/power-cycle won't enable it. If a freshly-set input never changes, re-pair the controller, then re-pick its child here. (Verified on ZEN16 FW 3.10.)"
        }

        section("Behavior") {
            input name: "rainSensorStopRunning", type: "bool",
                  title: "Stop a running schedule when a rain sensor activates mid-run",
                  defaultValue: true
            input name: "rainSensorClearMinutes", type: "number",
                  title: "After sensors go dry, wait N minutes before unblocking",
                  range: "0..720", defaultValue: 60
        }

        if (settings.rainSensorWaterDevices || settings.rainSensorContactDevices) {
            section("Current state") {
                (settings.rainSensorWaterDevices ?: []).each { dev ->
                    String cur = dev.currentValue('water') ?: 'unknown'
                    paragraph "• [water] ${dev.displayName} — ${cur}${cur == 'wet' ? '  ⛈ WET' : ''}"
                }
                String wetState = settings.rainSensorContactWetState ?: "closed"
                (settings.rainSensorContactDevices ?: []).each { dev ->
                    String cur = dev.currentValue('contact') ?: 'unknown'
                    boolean wet = (cur == wetState)
                    paragraph "• [contact] ${dev.displayName} — ${cur}${wet ? '  ⛈ WET' : ''}"
                }
                paragraph "Aggregate: ${rainSensorWet() ? '⛈ WET — schedule blocked' : '✓ dry — schedule allowed'}"
            }
        }
    }
}

def pauseSensorPage() {
    dynamicPage(name: "pauseSensorPage", title: "Pause sensors") {
        section {
            paragraph "External devices that pause the schedule when active. Useful for: contact sensors on a back door (pause when door open so the kids don't get sprayed), a manual override switch, a garage door, a presence sensor, etc."
        }
        section("Contact sensors") {
            input name: "pauseContacts", type: "capability.contactSensor",
                  title: "Pause when ANY of these contacts are in the trigger state",
                  multiple: true, required: false, submitOnChange: true
            input name: "pauseContactsState", type: "enum",
                  title: "Trigger state for contacts",
                  options: ["open":"open", "closed":"closed"],
                  defaultValue: "open"
        }
        section("Switches") {
            input name: "pauseSwitches", type: "capability.switch",
                  title: "Pause when ANY of these switches are in the trigger state",
                  description: "e.g. a virtual switch tied to Alexa, a wall override, a tablet button",
                  multiple: true, required: false, submitOnChange: true
            input name: "pauseSwitchesState", type: "enum",
                  title: "Trigger state for switches",
                  options: ["on":"on", "off":"off"],
                  defaultValue: "on"
        }
        section("Behaviour") {
            input name: "pauseMode", type: "enum",
                  title: "What happens mid-run when a pause sensor activates",
                  options: ["pause":  "Pause the current zone immediately, resume from the exact moment when all sensors clear (recommended)",
                            "stop":   "Stop everything and skip the rest of the schedule (wait for next scheduled time)"],
                  defaultValue: "pause"
            input name: "pauseResumeDelaySec", type: "number",
                  title: "After all pause sensors clear, wait this many seconds before resuming",
                  description: "Lets the door fully close / person walk away before water comes back on.",
                  range: "0..600", defaultValue: 30
        }
        if (settings.pauseContacts || settings.pauseSwitches) {
            section("Current state") {
                (settings.pauseContacts ?: []).each { dev ->
                    paragraph "• ${dev.displayName} (contact) — ${dev.currentValue('contact') ?: 'unknown'}"
                }
                (settings.pauseSwitches ?: []).each { dev ->
                    paragraph "• ${dev.displayName} (switch) — ${dev.currentValue('switch') ?: 'unknown'}"
                }
                paragraph "Aggregate pause-active right now: ${externalPauseActive() ? 'YES — schedule blocked' : 'no — schedule allowed'}"
            }
        }
    }
}

def pumpPage() {
    dynamicPage(name: "pumpPage", title: "Pump / Master valve") {
        section {
            paragraph "Optional. If your system has a pump or a master valve that must run alongside every zone, pick it here — the scheduler will turn it on before the first zone and off after the last."
            input name: "pumpSwitch", type: "capability.switch",
                  title: "Pump / master valve switch (any Switch device)",
                  required: false, multiple: false, submitOnChange: true
            def p = settings.pumpSwitch
            if (p) {
                input name: "pumpPortLabel", type: "text",
                      title: "Physical port label (free text)",
                      description: "e.g. \"ZEN16 #2 R1 (20A)\" or \"ZEN17 #1 R1 (20A)\"", required: false
                input name: "pumpPreSec",  type: "number",
                      title: "Pre-delay (seconds) — pump on, wait, then first zone",
                      range: "0..120", defaultValue: 5
                input name: "pumpPostSec", type: "number",
                      title: "Post-delay (seconds) — last zone off, wait, then pump off",
                      range: "0..120", defaultValue: 5
                paragraph "Current state of ${p.displayName}: ${p.currentValue('switch') ?: 'unknown'}"
            }
        }
    }
}

def hardwarePage() {
    Integer maxRun = (settings.scheduleMaxRunMinutes ?: 60) as int
    Integer targetMin = Math.max(1, maxRun + 5)   // app max + 5min buffer
    dynamicPage(name: "hardwarePage", title: "Hardware safety — Zooz relay watchdog") {
        section {
            paragraph "Why this matters. If the hub crashes mid-cycle, dies, loses Z-Wave, or the app errors out, the relay stays ON until someone notices. That's how a stuck sprinkler floods a yard. Zooz multi-relay devices (ZEN16, ZEN17, etc.) have per-relay hardware auto-off timers that fire inside the relay itself — no hub required. We push them once and forget."
            paragraph "Recommended target for this schedule: ${targetMin} minutes (max-zone-run-minutes plus a 5-minute safety buffer)."
        }
        section("Pick the Zooz relay parent controller(s)") {
            paragraph "Pick the PARENT relay device (the one whose name typically contains ZEN16 / ZEN17 — not its child relays). The model determines which parameter numbers we push:"
            paragraph "  ZEN16 (3 relays): P6 / P8 / P10 auto-off + P15 / P17 / P19 units\n  ZEN17 (2 relays): P6 / P8 auto-off + P15 / P17 units\n  Plus P1 (power-fail OFF) and P24 (DC-motor OFF) on both."
            input name: "hwZen16Parents", type: "capability.actuator",
                  title: "Zooz relay parent device(s)",
                  multiple: true, required: false, submitOnChange: true
            if (settings.hwZen16Parents) {
                paragraph "For each picked controller, set the relay count below so we push only the parameters that model supports:"
                settings.hwZen16Parents.eachWithIndex { dev, i ->
                    input name: "hwModel_${dev.id}", type: "enum",
                          title: "Model of ${dev.displayName}",
                          options: ZOOZ_RELAY_MODELS.collectEntries { k, v -> [(k): v.name] },
                          defaultValue: "3"
                }
            }
        }
        section("Recommended values") {
            input name: "hwAutoOffMinutes", type: "number",
                  title: "Auto-off timer (minutes) to push to every R1/R2/R3 timer",
                  description: "Default = schedule max-run + 5min. 0 disables (not recommended).",
                  range: "0..1440", defaultValue: targetMin
            input name: "hwPowerFailOff", type: "bool",
                  title: "Set P1 power-fail state = OFF",
                  description: "Stops relays auto-resuming after a power blip.",
                  defaultValue: true
            input name: "hwForceDcMotorOff", type: "bool",
                  title: "Force P24 DC-motor mode = OFF",
                  description: "Prevents accidental R1/R2 interlock.",
                  defaultValue: true
            input name: "zen16StaleHours", type: "number",
                  title: "Warn if a relay is unreachable for this many hours",
                  description: "These relays only report when actuated, so they're silent between waterings. Blank = auto (your watering gap + a buffer, currently ~${expectedQuietHours()}h) so you only get warned if a run is actually missed.",
                  range: "1..2000", required: false
        }
        if (settings.hwZen16Parents) {
            section("Push now") {
                input name: "btnPushHardwareSafety", type: "button",
                      title: "Push recommended Z-Wave parameters to selected controller(s)"
                paragraph "${state.hwLastPushSummary ?: 'No push performed yet.'}"
            }
            section("Selected controllers") {
                Map actByParent = (state.lastActuationByParent ?: [:]) as Map
                settings.hwZen16Parents.each { dev ->
                    String modelKey = settings."hwModel_${dev.id}" ?: "3"
                    String modelName = (ZOOZ_RELAY_MODELS[modelKey]?.name ?: "?") as String
                    paragraph "• ${dev.displayName} (id ${dev.id}) — model: ${modelName} — setParameter: " +
                              "${dev.hasCommand('setParameter') ? 'yes' : 'NO — the built-in driver does not expose setParameter; install the jtp10181 Advanced driver (vendored below)'}"
                    // Reachability diagnostics: when the controller last reported to
                    // the hub, and when the app last drove one of its relays. If the
                    // "app drove" line stays "never", this controller's zone switches
                    // aren't matched to it (check getParentDeviceId mapping).
                    Long drove = actByParent[dev.id as String] as Long
                    Long reported = null
                    try { if (dev.respondsTo("getLastActivity")) { Date la = dev.getLastActivity(); if (la) reported = la.getTime() } } catch (e) {}
                    paragraph "&nbsp;&nbsp;&nbsp;↳ app last drove a relay here: ${agoString(drove)} · controller last reported to hub: ${agoString(reported)}"
                }
            }
        }
        section("Driver options") {
            paragraph "Hubitat's built-in Zooz driver works for the basics (zone child switches), but it does NOT expose setParameter on the parent — so the Hardware Safety push needs jtp10181's Advanced drivers (vendored in this repo):"
            paragraph "• ZEN16:  https://raw.githubusercontent.com/RamSet/hubitat/main/zooz-zen16-multirelay.groovy\n" +
                      "• ZEN17:  https://raw.githubusercontent.com/RamSet/hubitat/main/zooz-zen17-universal-relay.groovy\n" +
                      "(Vendored from jtp10181/Hubitat — upstream: https://github.com/jtp10181/Hubitat/tree/main/Drivers/zooz)"
            input name: "hwSetParamStyle", type: "enum",
                  title: "setParameter argument order",
                  description: "Hubitat hides argument names from apps, so this can't be reliably auto-detected. Leave on Auto (assumes the jtp10181/vendored driver order, value-then-size) unless the push reports success but the device's Auto-Off timers stay 0 — then try Built-in.",
                  options: ["auto": "Auto (jtp10181 order — recommended)",
                            "jtp10181": "Force jtp10181 (paramNumber, value, size)",
                            "builtin":  "Force built-in (paramNumber, size, value)"],
                  defaultValue: "auto"
            paragraph "If a push reports success but the relay's Auto Turn-Off (P6/P8/P10) stays 0 on the device, the argument order is wrong — flip this setting and push again."
        }
        section {
            paragraph "References: " +
                      "<a href='https://www.support.getzooz.com/kb/article/371'>Zooz KB #371 — Sprinkler use on Hubitat (ZEN16)</a> · " +
                      "<a href='https://www.support.getzooz.com/kb/article/376'>KB #376 — Advanced settings</a> · " +
                      "<a href='https://community.hubitat.com/t/driver-zooz-relays-advanced-zen16-zen17-zen51-zen52/98194'>Community thread (jtp10181 drivers)</a>"
        }
    }
}

def historyPage() {
    dynamicPage(name: "historyPage", title: "Run history") {
        List runs = (state.runHistory ?: []) as List
        section {
            paragraph "Last ${runs.size()} run${runs.size() == 1 ? '' : 's'} (newest first). Capped at 50."
            if (!runs) {
                paragraph "No runs recorded yet."
            } else {
                StringBuilder sb = new StringBuilder("<table style='width:100%;font-family:monospace;font-size:0.9em'>")
                sb << "<tr><th align='left'>When</th><th align='left'>Zones</th><th align='left'>Outcome</th></tr>"
                runs.reverse().each { r ->
                    String when = r?.startedAt ?: ""
                    String zonesStr = (r?.zoneSummaries ?: []).join("<br>")
                    String outcome = r?.outcome ?: ""
                    sb << "<tr><td valign='top'>${when}</td><td valign='top'>${zonesStr}</td><td valign='top'>${outcome}</td></tr>"
                }
                sb << "</table>"
                paragraph sb.toString()
            }
            input name: "btnClearHistory", type: "button", title: "Clear history"
        }
    }
}

def dashboardPage() {
    dynamicPage(name: "dashboardPage", title: "Dashboard tile") {
        section {
            paragraph "Optionally create a child Virtual Switch that reflects this schedule's state. " +
                      "Put it on a Hubitat dashboard tile to see the schedule's status at a glance. The " +
                      "switch turns ON while watering is active. Custom attributes (currentZone, " +
                      "nextRun, lastFinish, seasonalMult, paused) are also published for advanced tiles."
            input name: "dashboardEnabled", type: "bool",
                  title: "Create / maintain dashboard child device",
                  defaultValue: false, submitOnChange: true
            if (settings.dashboardEnabled) {
                input name: "dashboardLabel", type: "text",
                      title: "Child device label (default: \"<schedule name> Tile\")",
                      required: false
                def existing = getDashboardChild()
                paragraph "Child device: ${existing ? existing.displayName : 'will be created on save'}"
                if (existing) {
                    input name: "btnRefreshDashboard", type: "button", title: "Refresh tile state now"
                }
            } else {
                def existing = getDashboardChild()
                if (existing) {
                    paragraph "Existing child device ${existing.displayName} will be removed on save."
                }
            }
        }
    }
}

def diagnosticsPage() {
    dynamicPage(name: "diagnosticsPage", title: "Diagnostics & test runs") {
        section("Health check") {
            paragraph healthCheckReport()
            input name: "btnRunHealthCheck", type: "button", title: "Re-run health check"
        }
        section("Per-zone test runs") {
            paragraph "Quickly pulse each relay so you can verify wiring and observe a visible valve open. Each test runs the relay for the seconds below, regardless of weather / pause / schedule state. Pump (if configured) is NOT automatically engaged."
            input name: "testRunSeconds", type: "number",
                  title: "Test duration per zone (seconds)",
                  range: "5..600", defaultValue: 30
            Integer n = zoneCount()
            for (int i = 1; i <= n; i++) {
                String label = settings."zone${i}Name" ?: "Zone ${i}"
                def sw = settings."zone${i}Switch"
                input name: "btnTestZone_${i}", type: "button", title: "▶ Test ${i}. ${label}${sw ? '' : ' (no switch!)'}"
            }
            input name: "btnTestAllZones", type: "button", title: "▶ Test ALL zones sequentially"
        }
        section("Force rain delay") {
            paragraph "Skip the next scheduled run, or block runs for a fixed number of hours."
            input name: "btnSkipNext",       type: "button", title: "Skip next scheduled run"
            input name: "btnRainDelay6h",    type: "button", title: "Force rain delay: 6 hours"
            input name: "btnRainDelay24h",   type: "button", title: "Force rain delay: 24 hours"
            input name: "btnRainDelay48h",   type: "button", title: "Force rain delay: 48 hours"
            input name: "btnRainDelay72h",   type: "button", title: "Force rain delay: 72 hours"
            input name: "btnClearRainDelay", type: "button", title: "Clear all forced delays"
            paragraph "Currently: ${forcedDelayDisplayString()}"
        }
        section("Live state dump") {
            paragraph stateDumpString()
        }
    }
}

def restrictionsPage() {
    dynamicPage(name: "restrictionsPage", title: "Restrictions") {
        section("Quiet hours blackout") {
            paragraph "Block runs between these times. If a scheduled trigger falls in the quiet window, it's skipped. Optionally, an in-progress run is stopped when quiet hours begin (the rest of the plan is skipped — pick up next scheduled window)."
            input name: "quietHoursEnabled", type: "bool",
                  title: "Enable quiet hours", defaultValue: false, submitOnChange: true
            if (settings.quietHoursEnabled) {
                input name: "quietStartTime", type: "time",
                      title: "Start of quiet window (no runs after)",
                      required: true
                input name: "quietEndTime", type: "time",
                      title: "End of quiet window (runs allowed after)",
                      required: true
                input name: "quietStopInProgress", type: "bool",
                      title: "Stop a run already in progress when quiet hours begin",
                      defaultValue: true
            }
        }

        section("Hubitat mode pause") {
            paragraph "Pause/skip the schedule when the Hubitat location is in any of these modes."
            input name: "pauseModes", type: "mode",
                  title: "Pause when mode is", multiple: true, required: false
        }

        section("HSM (Hubitat Safety Monitor) pause") {
            paragraph "Pause if HSM enters an alarmed state (intrusion / water / smoke). Re-enables when HSM clears."
            input name: "hsmPauseEnabled", type: "bool",
                  title: "Pause schedule when HSM is in any alarmed state",
                  defaultValue: false
            input name: "hsmPauseArmedAway", type: "bool",
                  title: "Also pause when HSM is in armedAway",
                  defaultValue: false
        }

        section("Pre-run lead notification") {
            paragraph "Send a notification N minutes BEFORE each scheduled window starts. Lets people clear the yard. Uses the notification devices selected on the Notifications page."
            input name: "preRunLeadMinutes", type: "number",
                  title: "Minutes before scheduled start (0 = disabled)",
                  range: "0..60", defaultValue: 0
        }

        if (settings.quietHoursEnabled || settings.pauseModes || settings.hsmPauseEnabled) {
            section("Current state") {
                paragraph quietHoursActive() ? "🌙 In quiet hours right now — runs blocked" : "✓ Outside quiet hours"
                if (settings.pauseModes) paragraph "Current mode: ${location?.mode ?: '?'} — " +
                                                    "${modeShouldPause() ? '⏸ in pause list' : '✓ not in pause list'}"
                if (settings.hsmPauseEnabled) paragraph "HSM state: ${location?.hsmStatus ?: 'unknown'} — " +
                                                       "${hsmShouldPause() ? '⏸ alarmed' : '✓ disarmed/clear'}"
            }
        }
    }
}

def previewPage() {
    dynamicPage(name: "previewPage", title: "Next 7 days preview") {
        section {
            paragraph "Calendar view of the next 7 days. Shows when each window would run, after accounting for skip-next, forced rain delay, day-of-week filters, and quiet hours. (Weather rain-skip is dynamic and can't be predicted ahead of time.)"
        }
        section("Schedule") {
            paragraph previewNextSevenDaysHtml()
        }
    }
}

def backupPage() {
    dynamicPage(name: "backupPage", title: "Backup / restore configuration") {
        section("Export") {
            paragraph "Copy the JSON below to back up this schedule. Device references (switches, sensors, controllers) are stored as labels — you'll have to re-pick the actual devices after restore on a different hub."
            input name: "btnRefreshExport", type: "button", title: "Refresh export"
            paragraph "<textarea readonly style='width:100%;height:240px;font-family:monospace;font-size:0.85em'>${exportConfigJson()}</textarea>"
        }
        section("Import") {
            paragraph "Paste a previously exported JSON and tap Apply. Non-device settings (run times, weather thresholds, days, etc.) are restored. Existing zones are preserved unless overwritten by the import."
            input name: "importJson", type: "text",
                  title: "Paste JSON here",
                  required: false
            input name: "btnImportConfig", type: "button", title: "Apply import"
            if (state.lastImportSummary) {
                paragraph "${state.lastImportSummary}"
            }
        }
    }
}

def apiPage() {
    String token = state.apiAccessToken ?: ""
    String localUri = ""
    try { localUri = getFullLocalApiServerUrl() } catch (ignored) {}
    dynamicPage(name: "apiPage", title: "External JSON API") {
        section("What this is") {
            paragraph "Read-only status JSON plus a small set of POST endpoints (run / stop / skip / delay). Token-protected. Use it for a phone widget, a Grafana panel, a script that triggers watering after sunset, etc."
        }
        section("Endpoints") {
            if (!token) {
                paragraph "Token not yet generated. Tap below to generate one (Hubitat will create an OAuth access token for this app)."
                input name: "btnGenerateApiToken", type: "button", title: "Generate access token"
            } else {
                paragraph "Token: ${token}<br>" +
                          "Local base URL: ${localUri}<br><br>" +
                          "GET  ${localUri}/status?access_token=${token} — JSON status<br>" +
                          "GET  ${localUri}/dashboard?access_token=${token} — HTML auto-refreshing dashboard<br>" +
                          "GET  ${localUri}/calendar.ics?access_token=${token} — iCalendar feed (next 30 days)<br>" +
                          "POST ${localUri}/run?access_token=${token}<br>" +
                          "POST ${localUri}/stop?access_token=${token}<br>" +
                          "POST ${localUri}/skip?access_token=${token}<br>" +
                          "POST ${localUri}/delay?access_token=${token}&hours=24"
                input name: "btnRevokeApiToken", type: "button", title: "Revoke / regenerate token"
            }
        }
        section("Example") {
            paragraph "<pre style='font-size:0.85em;background:#f4f4f4;padding:6px'>" +
                      "curl -s '${localUri}/status?access_token=${token ?: 'TOKEN'}'\n" +
                      "curl -X POST '${localUri}/run?access_token=${token ?: 'TOKEN'}'\n" +
                      "curl -X POST '${localUri}/delay?access_token=${token ?: 'TOKEN'}&hours=24'" +
                      "</pre>"
        }
    }
}

def exposurePage() {
    dynamicPage(name: "exposurePage", title: "Zone switches") {
        section {
            paragraph "Expose each enabled zone as a child Virtual Switch you can trigger from anywhere — Hubitat dashboards, Rule Machine, HomeKit (via Hubitat's HomeKit Integration), Alexa/Google routines. The child switch reflects the zone's actual relay state both ways: turn it ON externally and the zone runs; turn it OFF and the zone stops."
            input name: "zoneSwitchesEnabled", type: "bool",
                  title: "Create / maintain a child switch per enabled zone",
                  defaultValue: false, submitOnChange: true
            if (settings.zoneSwitchesEnabled) {
                input name: "defaultManualTimerMin", type: "number",
                      title: "Default manual-on timer (minutes)",
                      description: "When a child switch is turned ON from outside, the zone auto-stops after this many minutes. Per-zone override on the zone detail page.",
                      range: "1..240", defaultValue: 10
                input name: "zoneSwitchPrefix", type: "text",
                      title: "Optional label prefix for child devices",
                      description: "e.g. \"Sprinkler \" yields child names like \"Sprinkler Front Lawn\". Leave blank to use the zone name alone.",
                      required: false
                input name: "btnRebuildZoneSwitches", type: "button",
                      title: "Rebuild zone switches now (delete + recreate)"
            }
        }
        if (settings.zoneSwitchesEnabled) {
            section("Currently exposed") {
                Integer n = zoneCount()
                int created = 0
                for (int i = 1; i <= n; i++) {
                    def ch = getZoneChildVs(i)
                    if (ch) {
                        created++
                        String label = settings."zone${i}Name" ?: "Zone ${i}"
                        Integer mins = (settings."zone${i}ManualTimerMin" ?: settings.defaultManualTimerMin ?: 10) as int
                        Long expiresMs = (state.manualActive ?: [:])[i.toString()] as Long
                        String when = expiresMs ? " — auto-off at ${new Date(expiresMs).format('HH:mm:ss', location?.timeZone ?: TimeZone.getDefault())}" : ""
                        paragraph "• ${label} (${ch.displayName}) — current: ${ch.currentValue('switch') ?: '?'} · timer ${mins}min${when}"
                    }
                }
                if (created == 0) paragraph "No child switches exist yet. Save this page to create them."
            }
        } else {
            section {
                paragraph "Disabling will remove the child switches on save."
            }
        }
        section("Run-schedule control switch") {
            paragraph "Expose a single switch that starts the whole schedule on demand (turn ON) and stops it (turn OFF). Put it in HomeKit, on a dashboard, or in a routine. It also reflects status — it shows ON whenever the schedule is running (manual or timed) and OFF when idle."
            input name: "runSwitchEnabled", type: "bool",
                  title: "Create a \"Run schedule\" switch",
                  defaultValue: false, submitOnChange: true
            if (settings.runSwitchEnabled) {
                input name: "runSwitchLabel", type: "text",
                      title: "Switch label (default: \"${app.label} Run\")",
                      required: false, submitOnChange: true
            }
            // Create/rename/remove the device now so it appears immediately.
            ensureRunSwitchDevice()
            if (settings.runSwitchEnabled) {
                def rc = getRunCtlChild()
                paragraph rc ? "Created: ${rc.displayName} — ${rc.currentValue('switch') ?: 'off'}. Tap Done so it can control the schedule, then add it in Settings → HomeKit Integration."
                              : "Could not create the device — check the app's Logs (Apps → this app → Logs)."
            }
        }
    }
}

private String exposureSummaryString() {
    List<String> parts = []
    if (settings.zoneSwitchesEnabled) {
        Integer mins = (settings.defaultManualTimerMin ?: 10) as int
        int n = 0
        Integer zc = zoneCount()
        for (int i = 1; i <= zc; i++) { if (getZoneChildVs(i)) n++ }
        parts << "${n} zone switch(es) · ${mins}min"
    }
    if (settings.runSwitchEnabled) {
        parts << (getRunCtlChild() ? "Run switch ✓" : "Run switch — tap Done to create")
    }
    return parts ? parts.join(" · ") : "Disabled — no child switches"
}

def aboutPage() {
    dynamicPage(name: "aboutPage", title: "About") {
        section {
            paragraph "<h2>Zooz Sprinkler Scheduler</h2>" +
                      "Version: ${getAppVersion()}<br>" +
                      "Author: RamSet<br>" +
                      "License: Apache License, Version 2.0<br>" +
                      "Source: <a href='https://github.com/RamSet/hubitat'>github.com/RamSet/hubitat</a><br>" +
                      "Import URL: https://raw.githubusercontent.com/RamSet/hubitat/main/apps/zooz-sprinkler-scheduler/zooz-sprinkler-scheduler.groovy"
        }
        section("What this is") {
            paragraph "A Hubitat app for running sprinkler zones via Zooz ZEN16 / ZEN17 800LR multi-relay controllers — or any Hubitat device exposing the Switch capability. Hardware-agnostic, multi-instance, with Spruce-style weather adaptation, per-zone moisture-aware watering, restrictions (quiet hours / mode / HSM), pause-and-resume from external sensors, hub-independent hardware watchdog via Z-Wave parameters (model-aware: pushes the right per-relay timers for ZEN16's 3 relays or ZEN17's 2 relays), full external JSON/HTML/iCal API, and granular templated notifications with Pushover support."
        }
        section("Changelog") {
            paragraph "v0.12.4 — The Hardware-safety push won't set a relay auto-off timer shorter than the longest single watering cycle this schedule actually drives on that controller — it raises the value automatically so the hardware can't cut your own watering short. It also warns before lowering a timer the device already holds higher, which protects controllers shared between two app instances (a relay driven by the other instance may need the longer timer)."
            paragraph "v0.12.3 — Fixed false \"relay unreachable\" alerts right after a successful watering. The reachability watchdog judged the controller only by when its parent device last reported to the hub, which some Zooz drivers don't refresh when a child relay is toggled — so a controller the app had just driven could be flagged unreachable. The app now counts its own successful waterings as proof the controller is reachable, attributed to the specific controller that owns the relay so a run on one controller can't hide a genuine outage on another. The Hardware-safety page now shows, per controller, when the app last drove one of its relays and when the controller last reported to the hub — so you can confirm each controller is mapped correctly."
            paragraph "v0.12.2 — Fixed pause sensors reporting \"0s remaining\" and skipping ahead when they fired during a soak or the gap between zones. The schedule now tracks soak and between-zone phases as pausable too, so a pause that lands mid-soak reports the real soak time left and resumes that soak (valves stay off) instead of jumping to the next zone."
            paragraph "v0.13.3 — Fixes two scheduling problems. (1) Multiple start times now ALL work: each was scheduled on the same internal handler, so Hubitat overwrote all but the last — only your final start time ran. Each window now has its own handler. (2) A run can no longer start twice from a single trigger: a re-entrancy guard ignores a duplicate scheduled invocation within 15 seconds (and logs it), preventing the double \"starting\" / double watering seen after editing a program near its run time. Re-save each sprinkler app once after updating so the new per-window schedules register."
            paragraph "v0.13.2 — Pause sensors NEVER skip a run, even a manual one. Previously a manual run (the Run switch or \"Run schedule now\" button) with a pause sensor active (e.g. water heater on) reported \"skipped — pause sensor active\"; now it holds and auto-starts when the sensor clears, exactly like a scheduled run. (A wet rain sensor still skips.)"
            paragraph "v0.13.1 — Pause-sensor hold now applies on EVERY scheduled start regardless of the pause/stop mode (that setting only governs what happens mid-run). Previously a sensor set to 'stop' mode would still skip the cycle at the scheduled start instead of holding."
            paragraph "v0.13.0 — Pause sensors (water heater on, a door/contact open) now HOLD the scheduled run and start it automatically once they clear, instead of skipping the cycle — with a \"waiting for X to clear\" notification. Genuine skips (wet rain sensor, weather rain delay, quiet hours, mode/HSM) still skip the cycle and say why. Also: the completion summary's seasonal figure now shows the actual extra over the scheduled base, so \"watered X of Y · (+delta)\" always reconciles (previously the parenthetical was a theoretical seasonal estimate that didn't match the real watered total)."
            paragraph "v0.12.1 — The Hardware-Safety push no longer claims \"successful\" just because the commands were sent. It now reads the Auto-Off timers back off each controller ~15s later and reports the truth — \"✓ armed\" with the real values, or \"⚠ did NOT take\" with a prompt to flip the setParameter-order override and push again (and an error notification if any relay is left unprotected)."
            paragraph "v0.12.0 — IMPORTANT safety fix: the Hardware-Safety push was sending the relay Auto-Off timers in the wrong setParameter argument order, so they silently never took (the device's P6/P8/P10 stayed 0 — no hardware failsafe) even though the push reported success. The app can't read argument names from Hubitat, so it now defaults to the correct jtp10181/vendored-driver order (paramNumber, value, size), with a manual override on the Hardware-safety page. Re-push after updating, and confirm the Auto Turn-Off timers are non-zero on each relay."
            paragraph "v0.11.11 — Documented a confirmed ZEN16/ZEN17 gotcha on the Rain sensors page: an Sw input set to a sensor/water type won't actually report (stays stuck dry) until the relay is EXCLUDED + RE-INCLUDED — the sensor-report association is only set up during Z-Wave inclusion, not by Save/Configure/power-cycle. Verified on ZEN16 FW 3.10."
            paragraph "v0.11.10 — The \"zone turned off\" notification is now ON by default (was off), so a manual off is announced just like a manual on. If you'd previously saved Notifications, enable \"zone turned off\" there to get it."
            paragraph "v0.11.9 — Fixed a HomeKit zone switch sometimes refusing to turn off (the relay stayed on). A leftover tile-suppression flag could make the app mistake your real toggle for one of its own and ignore it. Suppression is now time-bounded — only the app's own command within the last few seconds is ignored — and stale flags are pruned, so manual on/off is always honored."
            paragraph "v0.11.8 — Fixed false \"relay unreachable\" warnings. These relays only report when actuated, so they sit silent between waterings; the old 6-hour threshold cried wolf a few hours after every run. The watchdog now defaults the threshold to your watering gap plus a buffer (e.g. ~96h for every-3-days), so you're only warned if a run is actually missed. Tunable on the Hardware safety page."
            paragraph "v0.11.7 — The \"schedule starting\" notification now includes an estimated total run time — watering + soak + between-zone delays — e.g. \"~1h 38m total (water 1h 14m + soak 20m)\". New template variables: \${estTotal}, \${estWater}, \${estSoak}."
            paragraph "v0.11.6 — Zone list now shows the cycle & soak breakdown and total clock time per zone (e.g. \"12m water (2× 6m) + 10m soak = 22m total\"). Soak time is tracked separately from watering: the complete notification reports soak on its own, and \"watered\" stays valves-on time only. New \${soak} template variable."
            paragraph "v0.11.5 — Fixed exposed zone tiles (HomeKit/dashboard) desyncing during a run — finished zones could stay \"on\" while the running zone showed \"off\", making it look like several zones ran at once (only one valve was ever open). Tile mirroring now uses per-zone tracking and reconciles every tile to its relay on each zone change and at completion."
            paragraph "v0.11.4 — The \"schedule complete\" notification now summarises the run: total elapsed time, actual watering vs the scheduled (pre-seasonal) amount, the seasonal adjustment (multiplier and the time it added/removed), and how long the run was paused. New template variables: \${elapsed}, \${watered}, \${scheduled}, \${seasonal}, \${paused}, \${zones}."
            paragraph "v0.11.3 — Notifications now show durations in human form (e.g. \"7m 59s\" instead of \"479s\") for pause/resume, test runs and moisture early-stops."
            paragraph "v0.11.2 — Manual/on-demand runs (Run switch, Run now) now still respect active safety — pause sensors (wind/contacts), a wet rain sensor, and mode/HSM holds — while still bypassing scheduling holds (off-cycle day, quiet hours, weather forecast, forced rain delay). Also fixed notifications showing a stray \"[default]\" prefix when a Pushover event priority was left on \"default\"."
            paragraph "v0.11.1 — Fixed a crash that aborted every run when Seasonal adjust was enabled: the seasonal multiplier did a Double.setScale() that Groovy rejects, so runSchedule threw before watering started (no zones ran, no start notification). Seasonal scaling now computes correctly. This affected both manual and scheduled runs whenever Seasonal adjust was on."
            paragraph "v0.11.0 — The Run switch and \"Run schedule now\" button are now true force-runs: they water immediately, ignoring every hold (rain sensor, pause sensors, mode/HSM, quiet hours, weather). Scheduled (timed) runs keep all safety checks. The log now always prints the run plan and whether each run is manual/force, so a non-start is unambiguous."
            paragraph "v0.10.5 — When an on-demand run (Run switch / Run now) doesn't start, the app log now states exactly why — a pause sensor, a wet rain sensor, a mode/HSM hold, or no enabled zones with a relay — instead of silently flicking the switch back off."
            paragraph "v0.10.4 — A manual/on-demand run (the Run-schedule switch and \"Run schedule now\" button) now overrides the advisory holds — forecast rain-delay, smart-skip, forced rain delay and quiet hours — so pressing it actually waters. Active safety still applies: a wet rain sensor, pause sensors, mode/HSM and an already-running schedule. This is why the switch flicked on then back off before: the run was being skipped by the weather rain-delay."
            paragraph "v0.10.3 — The Run-schedule switch now starts listening for on/off the moment it's created, not only after Done — so toggling it actually starts/stops the schedule right away. (Previously the event subscription was only wired up on Done, so a freshly-created switch did nothing.)"
            paragraph "v0.10.2 — The Run-schedule switch is now created the moment you enable it on the Zone-switches page, instead of only on Done — so it appears right away. Tap Done afterwards to activate its control of the schedule."
            paragraph "v0.10.1 — The Zone-switches summary now also shows the Run-schedule switch status (it previously only counted zone switches, so an enabled Run switch looked missing). The Run switch is created when you tap Done."
            paragraph "v0.10.0 — Added an optional \"Run schedule\" control switch (Zone switches page). Expose it to HomeKit/dashboards/routines: turn it ON to start the whole schedule on demand, OFF to stop it. It also reflects status — ON while the schedule is running (manual or timed), OFF when idle — and bounces back off if an on-demand start is skipped by rain/quiet-hours/pause."
            paragraph "v0.9.2 — Turning a zone off from HomeKit now cancels its auto-off timer immediately, so you no longer get a stray \"timer expired\" notification ~10 minutes later. Per-zone manual timers are tracked independently. Left on, a zone still auto-stops after its timer. During a scheduled run, the HomeKit tile reflects the schedule and ignores stray toggles (use Stop-all to interrupt a run)."
            paragraph "v0.9.1 — Fixed HomeKit/dashboard zone switches not driving the relay: toggling an exposed zone switch had no effect because the event handler read a device-network-id field that is always empty on Hubitat events. It now resolves the zone from the event's device, so manual on/off (and the auto-off timer) work again."
            paragraph "v0.9.0 — Weather is now unit-aware: temperature, wind and rainfall follow the hub's Settings → Location measurement scale (°F/in/mph when imperial, °C/mm/km/h when metric). Forecast table, thresholds, defaults, seasonal scaling and skip notifications all switch automatically. Also fixed wind data that was fetched in km/h but labelled mph."
            paragraph "v0.8.0 — Added a global Simple / Advanced interface mode (top of the main page). Simple mode shows just the essentials — zones, schedule (time & frequency), weather and hardware safety — and trims each zone to name, relay and run-minutes. Advanced reveals everything (soil-moisture/learning, sensors, pump, notifications, dashboard, API, restrictions, diagnostics). Hidden settings are kept, not deleted. Also fixed an overflowing soil-moisture sensor hint that ran off-screen on phones."
            paragraph "v0.7.1 — Reachability watchdog no longer cries wolf: an idle-but-reachable relay used to be reported \"unreachable\" just for being quiet, and the alert repeated every hour. It now actively pings a quiet relay and only alerts (once per outage) if the ping goes unanswered."
            paragraph "v0.7.0 — Added \"Every N days\" scheduling alongside the existing day-of-week mode. Pick a start date and an interval (every other day, every third day, etc.); the 7-day preview and iCal feed follow the cycle. Manual \"Run schedule now\" still runs regardless of the cycle."
            paragraph "v0.6.2 — Vendored jtp10181's ZEN16/ZEN17 Advanced drivers into the repo as failsafes. Scheduler auto-detects the setParameter argument order (built-in uses paramNumber/size/value; jtp10181 uses paramNumber/value/size) so the same Hardware Safety push works with either driver."
            paragraph "v0.6.1 — Generalised hardware-safety push: works with ZEN16 (3 relays), ZEN17 (2 relays), and other Zooz multi-relay models. Per-controller model selector on the Hardware Safety page; push iterates only the parameters relevant to each picked controller. Every UI string and notification message broadened to \"Zooz multi-relay\" instead of \"ZEN16\" specifically."
            paragraph "v0.6 — Per-zone child Virtual Switches for HomeKit/Rule-Machine/dashboard control with configurable manual-on auto-off timer (default 10 min, per-zone override). Contact-sensor rain support (incl. Zooz relay input child devices). HTML stripped from input/paragraph text on mobile clients. App icons resized for the iOS Hubitat app."
            paragraph "v0.5 — Smart skips (frost/cold/wind via Open-Meteo), per-zone fertilizer mode (one-shot short-burst-with-soak), auto-stagger across instances via shared coordination switch, HTML dashboard endpoint, iCalendar export endpoint, About page."
            paragraph "v0.4 — Per-zone moisture modes (off / skip / earlyStop / adapt / learn), pre/post moisture capture with rolling rate, learned-rate prediction, Moisture page with per-zone history table."
            paragraph "v0.3 — Per-event notification system with 25 named events + templates + Pushover. Spruce-style weekly-minutes runtime. Per-zone weekly budget cap. Today's forecast preview on weather page."
            paragraph "v0.2 — Quiet hours, mode/HSM pause, pre-run lead notification, 7-day schedule preview, OAuth JSON API, backup/restore JSON, ZEN16 reachability watchdog."
            paragraph "v0.1 — Initial release: zones, schedule, weather (Open-Meteo), rain sensors, pause sensors with true pause-and-resume, pump/master, hardware safety push, history, test runs, diagnostics, dashboard child device."
        }
        section("Acknowledgements") {
            paragraph "Inspired by the Plaid Systems Spruce Scheduler — same overall approach (per-zone plant/sprinkler/soil typing, weather-aware seasonal adjust, rain delay, optional pump/master, moisture sensors)."
            paragraph "• <a href='https://www.support.getzooz.com/kb/article/371'>Zooz KB #371 — sprinkler use on Hubitat</a><br>" +
                      "• <a href='https://www.support.getzooz.com/kb/article/376'>Zooz KB #376 — advanced settings</a><br>" +
                      "• Weather by <a href='https://open-meteo.com'>Open-Meteo</a> (no API key required)<br>" +
                      "• Icons by <a href='https://openmoji.org'>OpenMoji</a> (CC-BY-SA 4.0)"
        }
        section("License (Apache 2.0)") {
            paragraph "<pre style='font-size:0.8em'>Licensed under the Apache License, Version 2.0 (the \"License\"); you may not use this file except in compliance with the License. You may obtain a copy of the License at\n\n  http://www.apache.org/licenses/LICENSE-2.0\n\nUnless required by applicable law or agreed to in writing, software distributed under the License is distributed on an \"AS IS\" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.</pre>"
        }
    }
}

def moisturePage() {
    dynamicPage(name: "moisturePage", title: "Moisture learning") {
        section {
            paragraph "Per-zone moisture status. The scheduler captures a pre-moisture reading at zone start and a post-moisture reading at zone end. From the pre/post delta and the actual run minutes, it derives a soil-response rate (% per minute). In learn mode, future runs use this rate to predict the runtime needed to lift moisture from current level up to target."
        }
        Integer n = zoneCount()
        boolean any = false
        for (int i = 1; i <= n; i++) {
            if (!settings."zone${i}MoistureSensor") continue
            any = true
            String label = settings."zone${i}Name" ?: "Zone ${i}"
            String mode  = settings."zone${i}MoistureMode" ?: "skip"
            Float cur    = readZoneMoisture(i)
            Integer target = (settings."zone${i}MoistureTarget" ?: 50) as int
            Integer minPct = (settings."zone${i}MoistureMin" ?: 20) as int
            Float rate   = learnedRate(i)
            String status = zoneAdaptiveStatusString(i)
            section("${i}. ${label}") {
                paragraph "Mode: ${mode} · " +
                          "Current: ${cur != null ? "${cur}%" : 'n/a'} · " +
                          "Target: ${target}% · Min: ${minPct}% · " +
                          "Status: ${status}"
                List recs = ((state.moistureLearning ?: [:])[i.toString()] ?: []) as List
                if (recs) {
                    StringBuilder sb = new StringBuilder("<table style='width:100%;font-family:monospace;font-size:0.85em'>")
                    sb << "<tr><th align='left'>When</th><th align='right'>Pre %</th><th align='right'>Post %</th>"
                    sb << "<th align='right'>Δ %</th><th align='right'>RunMin</th><th align='right'>%/min</th></tr>"
                    recs.reverse().each { r ->
                        sb << "<tr><td>${r.ts}</td><td align='right'>${r.pre}</td><td align='right'>${r.post}</td>"
                        sb << "<td align='right'>${r.delta}</td><td align='right'>${r.runMin}</td><td align='right'>${r.rate}</td></tr>"
                    }
                    sb << "</table>"
                    paragraph sb.toString()
                    paragraph "Weighted-average rate (recent runs weigh more): ${rate ?: 'insufficient data'}"
                    input name: "btnClearMoisture_${i}", type: "button", title: "Clear learning history for this zone"
                } else {
                    paragraph "No learning records yet. Run the zone at least once with a moisture sensor in any mode to start collecting data."
                }
            }
        }
        if (!any) {
            section {
                paragraph "No zones have a moisture sensor assigned. Pick one on each zone's detail page to enable moisture-aware watering."
            }
        }
    }
}

private String moistureSummaryString() {
    Integer n = zoneCount()
    int withSensor = 0
    int adaptive = 0
    for (int i = 1; i <= n; i++) {
        if (settings."zone${i}MoistureSensor") {
            withSensor++
            String mode = settings."zone${i}MoistureMode" ?: "skip"
            if (mode in ["adapt", "learn", "earlyStop"]) adaptive++
        }
    }
    if (withSensor == 0) return "No moisture sensors configured"
    return "${withSensor} zone(s) with sensor · ${adaptive} adaptive/early-stop"
}

def notificationPage() {
    dynamicPage(name: "notificationPage", title: "Notifications") {
        section("Where to send notifications") {
            input name: "notifyDevices", type: "capability.notification",
                  title: "Notification devices (any Hubitat capability.notification)",
                  multiple: true, required: false
        }
        section("Pushover (optional)") {
            paragraph "Install the community <a href='https://community.hubitat.com/t/release-pushover-notifications/29795'>Pushover Notifications</a> driver, pair your account, then pick the resulting device here. Pushover messages mirror everything sent to the regular notification devices above, with optional per-event priority/sound overrides on the next page."
            input name: "pushoverDevice", type: "capability.notification",
                  title: "Pushover device(s)",
                  multiple: true, required: false, submitOnChange: true
            if (settings.pushoverDevice) {
                input name: "pushoverDefaultPriority", type: "enum",
                      title: "Default Pushover priority",
                      options: NOTIFY_PRIORITIES, defaultValue: "0 normal"
                input name: "pushoverDefaultSound", type: "text",
                      title: "Default Pushover sound (e.g. \"cosmic\", \"siren\", \"tugboat\" — leave blank for device default)",
                      required: false
                input name: "pushoverEmergencyOnError", type: "bool",
                      title: "Force emergency priority for `error` events",
                      defaultValue: false
                paragraph "If your Pushover driver supports the bracketed prefix format [priority|sound|message], the app will use it; otherwise messages go through as plain deviceNotification."
            }
        }
        section("Per-event configuration") {
            href name: "notifyEventsPage", title: "Configure per-event toggles and custom messages →",
                 page: "notifyEventsPage",
                 description: notifyEventsSummaryString()
        }
        section("Test") {
            input name: "btnNotifyTest", type: "button",
                  title: "Send a test notification to every selected channel"
        }
    }
}

def notifyEventsPage() {
    dynamicPage(name: "notifyEventsPage", title: "Per-event notifications") {
        section {
            paragraph "Every notification the scheduler can emit is listed below, grouped by category. Toggle individual events on/off. To customise the wording, paste your own message in the override field — leave blank to use the default. Custom messages support these template variables (any missing variable renders as empty):"
            paragraph "\${app} · \${zone} · \${duration} · \${remaining} · " +
                      "\${reason} · \${sensor} · \${cycle} · \${totalCycles} · " +
                      "\${count} · \${minutes} · \${hours} · \${detail} · " +
                      "\${planSize} · \${seasonalMult} · \${until} · " +
                      "\${mode} · \${hsm} · \${delay} · " +
                      "\${tempF} · \${windMph} · \${threshold} · \${tunit} · \${wunit}"
        }
        // Group events by section preserving NOTIFY_EVENTS insertion order
        Map<String, List<Map>> grouped = [:]
        NOTIFY_EVENTS.each { key, meta ->
            String sec = meta.section as String
            grouped[sec] = (grouped[sec] ?: []) + [[key: key, meta: meta]]
        }
        grouped.each { sec, items ->
            section("${sec}") {
                items.each { item ->
                    String key = item.key
                    Map meta = item.meta
                    String safeKey = key.replace(".", "_").replace("-", "_")
                    boolean defOn = !(meta.defaultOff == true)
                    input name: "notifyEvent_${safeKey}", type: "bool",
                          title: "${key} — ${escapeForUi(meta.default as String)}",
                          defaultValue: defOn
                    input name: "notifyMsg_${safeKey}", type: "text",
                          title: "Override message (leave blank for default)",
                          required: false
                    if (settings.pushoverDevice) {
                        input name: "notifyPriority_${safeKey}", type: "enum",
                              title: "Pushover priority override (default = use device default)",
                              options: NOTIFY_PRIORITIES, required: false
                    }
                    paragraph "<hr style='margin:4px 0'>"
                }
            }
        }
        section {
            input name: "btnResetEventDefaults", type: "button",
                  title: "Clear every override (restore defaults across the board)"
        }
    }
}

private String escapeForUi(String s) {
    return s ? s.replace("<", "&lt;").replace(">", "&gt;") : ""
}

// =========================================================================
// App lifecycle
// =========================================================================

def installed() {
    if (debugOutput) log.debug "Sprinkler scheduler installed"
    state.zones ?: (state.zones = [:])
    state.lastRunByZone ?: (state.lastRunByZone = [:])
    initialize()
}

def updated() {
    if (debugOutput) log.debug "Sprinkler scheduler updated"
    unschedule()
    initialize()
    if (debugOutput) runIn(1800, "logsOff")
}

def initialize() {
    state.zones = state.zones ?: [:]
    state.lastRunByZone = state.lastRunByZone ?: [:]
    state.running = false
    state.currentZoneIdx = 0
    state.zonesPlan = []
    state.deferredRunPending = false   // never carry a held-defer across re-init/reboot
    state.lastSchedEntryMs = 0L         // reset the double-start guard window

    if (settings.scheduleEnabled && settings.scheduleStartTime && (isIntervalMode() || settings.scheduleDays)) {
        // Interval mode fires the cron every day; runSchedule() gates on the
        // N-day cycle. Lock "today" as the anchor if the user didn't pick a date.
        if (isIntervalMode() && !settings.scheduleIntervalAnchor && !state.intervalAnchorMs) {
            state.intervalAnchorMs = localMidnightMs(now())
        }
        List cronDays = isIntervalMode() ? ["SUN","MON","TUE","WED","THU","FRI","SAT"] : settings.scheduleDays
        ["scheduleStartTime", "scheduleStartTime2", "scheduleStartTime3"].eachWithIndex { key, i ->
            String t = settings[key]
            if (t) {
                String cron = buildCron(t, cronDays)
                // Each window needs its OWN handler. Hubitat keys a schedule by its
                // handler method name, so scheduling every window on "runSchedule"
                // makes each call overwrite the last — only the final start time
                // survives. Distinct per-window handlers keep all start times alive.
                String runHandler = "runScheduleW${i + 1}"
                if (descTextEnable) log.info "${app.label}: window ${i + 1} scheduled at cron='${cron}' (${runHandler})"
                schedule(cron, runHandler)
                // Pre-run lead notification (N minutes before the window)
                Integer lead = (settings.preRunLeadMinutes ?: 0) as int
                if (lead > 0) {
                    String leadCron = buildLeadCron(t, cronDays, lead)
                    if (leadCron) {
                        schedule(leadCron, "preRunNotifyW${i + 1}")
                    }
                }
            }
        }
    }

    // Quiet hours edge-trigger: when the start time arrives, run the
    // in-progress-stop check.
    if (settings.quietHoursEnabled && settings.quietStartTime) {
        schedule(buildDailyCron(settings.quietStartTime), "quietHoursStart")
    }

    // Mode / HSM subscriptions
    if (settings.pauseModes) subscribe(location, "mode", "modeChanged")
    if (settings.hsmPauseEnabled || settings.hsmPauseArmedAway) {
        subscribe(location, "hsmStatus", "hsmChanged")
        subscribe(location, "hsmAlert",  "hsmChanged")
    }

    maintainDashboardChild()
    maintainZoneSwitches()
    maintainRunSwitch()
    runEvery1Hour("publishDashboardState")
    runEvery1Hour("zen16Watchdog")
    // Weekly-budget rollover every Monday 00:01 local
    schedule("0 1 0 ? * 2", "rolloverWeeklyBudget")
    // Lazy check at every wake: if we crossed a week boundary while powered off
    rolloverWeeklyBudgetIfNeeded()

    // Subscribe to rain sensors (water-capability + contact-based) so a
    // wet event during a run can stop it. Both routes call rainSensorEvent.
    if (settings.rainSensorWaterDevices) {
        subscribe(settings.rainSensorWaterDevices, "water", "rainSensorEvent")
    }
    if (settings.rainSensorContactDevices) {
        subscribe(settings.rainSensorContactDevices, "contact", "rainSensorEvent")
    }
    // Subscribe to pause sensors (contacts + switches) for mid-run safety stop.
    if (settings.pauseContacts) subscribe(settings.pauseContacts, "contact", "pauseSensorEvent")
    if (settings.pauseSwitches) subscribe(settings.pauseSwitches, "switch",  "pauseSensorEvent")
}

// ---- Restriction-edge handlers ----

def quietHoursStart() {
    if (!state.running) return
    if (settings.quietStopInProgress == false) return
    log.warn "${app.label}: quiet hours starting — stopping in-progress run"
    notify("rain.mid.stop", [sensor: "quiet hours", reason: "quiet hours start"])
    stopAllZones()
}

def modeChanged(evt) {
    if (modeShouldPause() && state.running) {
        log.warn "${app.label}: Hubitat mode changed to ${evt?.value} — pausing run"
        pauseRunningSchedule("mode=${evt?.value}")
    } else if (!modeShouldPause() && state.paused && (state.pausedReason ?: "").startsWith("mode=")) {
        log.info "${app.label}: mode cleared — resuming"
        Integer delaySec = (settings.pauseResumeDelaySec ?: 30) as int
        runIn(Math.max(1, delaySec), "doResumeAfterPause")
    }
}

def hsmChanged(evt) {
    if (hsmShouldPause() && state.running) {
        log.warn "${app.label}: HSM=${location?.hsmStatus} — pausing run"
        pauseRunningSchedule("hsm=${location?.hsmStatus}")
    } else if (!hsmShouldPause() && state.paused && (state.pausedReason ?: "").startsWith("hsm=")) {
        log.info "${app.label}: HSM cleared — resuming"
        Integer delaySec = (settings.pauseResumeDelaySec ?: 30) as int
        runIn(Math.max(1, delaySec), "doResumeAfterPause")
    }
}

def preRunNotify() {
    Integer lead = (settings.preRunLeadMinutes ?: 0) as int
    if (lead <= 0) return
    String msg = "${app.label}: schedule starts in ${lead} minute${lead == 1 ? '' : 's'}"
    notify("pre-run", msg)
    if (descTextEnable) log.info msg
}

// ---- Mid-run safety event handlers ----

def rainSensorEvent(evt) {
    // Water-capability device emits attribute "water" with value "wet" or "dry".
    // Contact device emits attribute "contact" with value "open" or "closed".
    // The configured "wet state" tells us which contact value means raining.
    String wetContactState = settings.rainSensorContactWetState ?: "closed"
    boolean isWet
    if (evt?.name == "water") {
        isWet = (evt.value == "wet")
    } else if (evt?.name == "contact") {
        isWet = (evt.value == wetContactState)
    } else {
        return
    }
    if (!isWet) return
    if (descTextEnable) log.info "${app.label}: ${evt.displayName} → wet (${evt.name}=${evt.value})"
    if (state.running && settings.rainSensorStopRunning != false) {
        log.warn "${app.label}: rain detected mid-run (${evt.displayName}) — stopping all zones"
        notify("rain.mid.stop", [sensor: evt.displayName, reason: "rain ${evt.name}=${evt.value}"])
        stopAllZones()
    }
}

def pauseSensorEvent(evt) {
    boolean active = externalPauseActive()
    String mode = settings.pauseMode ?: "pause"
    if (active) {
        if (descTextEnable) log.info "${app.label}: pause sensor activated (${evt?.displayName} → ${evt?.value})"
        if (mode == "stop") {
            if (state.running) {
                log.warn "${app.label}: external pause active mid-run — STOP mode, killing schedule"
                notify("pause.activate", [zone: "schedule", remaining: fmtDuration(0), reason: "${evt?.displayName} → ${evt?.value} (stop mode)"])
                stopAllZones()
            }
        } else {  // pause mode
            if (state.running) {
                pauseRunningSchedule("${evt?.displayName} ${evt?.value}")
            }
        }
    } else {
        // A pause sensor cleared. If we're in paused state and ALL pause
        // sensors are now clear, schedule a resume after the configured delay.
        if (state.paused) {
            Integer delaySec = (settings.pauseResumeDelaySec ?: 30) as int
            log.info "${app.label}: all pause sensors clear — resuming in ${delaySec}s"
            notify("pause.clear", [delay: fmtDuration(delaySec as int)])
            runIn(Math.max(1, delaySec), "doResumeAfterPause")
        } else if (state.deferredRunPending) {
            // A run was held at its scheduled start because a pause sensor was
            // active. All clear now — launch it after the resume delay, re-checking
            // every gate (rain, quiet hours, etc.) on the way in.
            Integer delaySec = (settings.pauseResumeDelaySec ?: 30) as int
            log.info "${app.label}: pause sensors clear — starting held run in ${delaySec}s"
            state.deferredRunPending = false
            notify("schedule.deferResume", [delay: fmtDuration(delaySec as int)])
            runIn(Math.max(1, delaySec), "startDeferredRun")
        }
    }
}

// ---- True pause / resume of an in-progress watering run ----

private void pauseRunningSchedule(String reason) {
    if (!state.running) return
    Integer zid = (state.currentZoneId ?: 0) as int
    String phaseType = state.currentPhaseType ?: "water"
    if (zid == 0) {
        // Before the first zone; just stop and remember plan position.
        log.info "${app.label}: pause requested between zones — holding plan"
        state.pausedRemainingSec = 0
        state.pausedPhaseType = "gap"
    } else {
        // Compute remaining seconds left in the current phase (watering,
        // soak, or between-zone gap). currentPhaseStartMs/DurationSec are kept
        // in sync by whichever phase is active, so this is accurate for all.
        Long startMs = (state.currentPhaseStartMs ?: now()) as long
        Integer phaseDurSec = (state.currentPhaseDurationSec ?: 0) as int
        long elapsedMs = now() - startMs
        long remainingMs = Math.max(0L, (phaseDurSec * 1000L) - elapsedMs)
        Integer remainingSec = (remainingMs / 1000L) as int

        state.pausedRemainingSec = remainingSec
        state.pausedPhaseType = phaseType

        // Only an active watering phase has a valve open to close.
        if (phaseType == "water") {
            def sw = settings."zone${zid}Switch"
            if (sw) try { sw.off() } catch (e) { log.warn "pause: ${e.message}" }
        }
        String phaseLabel = (phaseType == "soak") ? "soak" : (phaseType == "gap" ? "between-zone gap" : "watering cycle ${((state.currentZoneCycleIdx ?: 0) as int) + 1}/${state.currentZoneCycles}")
        log.warn "${app.label}: PAUSED at ${settings."zone${zid}Name" ?: "zone ${zid}"} — ${remainingSec}s left in ${phaseLabel}. Reason: ${reason}"
        notify("pause.activate", [zone: (settings."zone${zid}Name" ?: "Zone ${zid}"), remaining: fmtDuration(remainingSec as int), reason: reason])
    }

    // Cancel any pending phase / soak / next-zone callbacks.
    unschedule("startNextZone")
    unschedule("zoneCyclePhaseDone")
    unschedule("zoneCycleResume")
    unschedule("doResumeAfterPause")

    state.paused = true
    state.running = false  // schedule is no longer actively running
    state.pausedReason = reason
    state.pauseStartMs = now()   // for total-paused accounting at finish
}

def doResumeAfterPause() {
    if (!state.paused) return
    if (externalPauseActive()) {
        log.info "${app.label}: resume aborted — pause sensor activated again during resume delay"
        return
    }
    Integer zid = (state.currentZoneId ?: 0) as int
    Integer remainingSec = (state.pausedRemainingSec ?: 0) as int
    String phaseType = state.pausedPhaseType ?: "water"
    // Accumulate how long we were paused into the run record.
    Long pStart = (state.pauseStartMs ?: 0L) as long
    if (pStart > 0 && state.currentRunRecord) {
        def r = state.currentRunRecord
        r.pausedSec = ((r.pausedSec ?: 0) as int) + (int)((now() - pStart) / 1000L)
        state.currentRunRecord = r
    }
    state.pauseStartMs = 0L
    state.paused = false
    state.running = true

    if (zid == 0) {
        // No zone context — fall back to advancing the plan.
        if (descTextEnable) log.info "${app.label}: resuming with next zone in plan"
        runIn(1, "startNextZone")
        return
    }

    String zname = settings."zone${zid}Name" ?: "Zone ${zid}"

    // Resume the exact phase we paused in, with its remaining time.
    if (phaseType == "gap") {
        if (descTextEnable) log.info "${app.label}: resuming between-zone gap — ${remainingSec}s left"
        notify("pause.resume", [zone: zname, remaining: fmtDuration(remainingSec as int)])
        state.currentPhaseStartMs = now()
        state.currentPhaseDurationSec = remainingSec
        state.currentPhaseType = "gap"
        runIn(Math.max(1, remainingSec), "startNextZone")
        return
    }

    if (phaseType == "soak") {
        if (descTextEnable) log.info "${app.label}: resuming soak — ${remainingSec}s left (valves stay off)"
        notify("pause.resume", [zone: zname, remaining: fmtDuration(remainingSec as int)])
        state.currentPhaseStartMs = now()
        state.currentPhaseDurationSec = remainingSec
        state.currentPhaseType = "soak"
        runIn(Math.max(1, remainingSec), "zoneCycleResume")
        return
    }

    // Watering phase. If somehow nothing is left, advance the plan.
    if (remainingSec <= 0) {
        if (descTextEnable) log.info "${app.label}: resuming with next zone in plan"
        runIn(1, "startNextZone")
        return
    }
    def sw = settings."zone${zid}Switch"
    if (sw) try { sw.on() } catch (e) { log.warn "resume: ${e.message}" }
    log.info "${app.label}: RESUMED ${zname} — ${remainingSec}s left in this cycle"
    notify("pause.resume", [zone: zname, remaining: fmtDuration(remainingSec as int)])
    state.currentPhaseStartMs = now()
    state.currentPhaseDurationSec = remainingSec
    state.currentPhaseType = "water"
    runIn(Math.max(1, remainingSec), "zoneCyclePhaseDone")
}

def logsOff() {
    log.warn "${app.label}: debug logging disabled"
    app.updateSetting("debugOutput", [value: "false", type: "bool"])
}

// =========================================================================
// Cron from time + days-of-week enum list
// =========================================================================

// =========================================================================
// Every-N-days (interval) scheduling helpers
// =========================================================================

private boolean isIntervalMode() {
    return (settings.scheduleMode ?: "dow") == "interval"
}

// Local-midnight epoch ms for the day containing `ms`.
private long localMidnightMs(long ms) {
    TimeZone tz = location?.timeZone ?: TimeZone.getDefault()
    Calendar c = Calendar.getInstance(tz)
    c.setTimeInMillis(ms)
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0);      c.set(Calendar.MILLISECOND, 0)
    return c.getTimeInMillis()
}

// Anchor (local midnight ms) the N-day cycle counts forward from.
// Priority: explicit Start-date setting → locked install-time anchor → today.
private long intervalAnchorMs() {
    TimeZone tz = location?.timeZone ?: TimeZone.getDefault()
    String a = settings.scheduleIntervalAnchor
    if (a) {
        try {
            String datePart = a.contains("T") ? a.tokenize("T")[0] : a.take(10)
            String[] p = datePart.tokenize("-")
            Calendar c = Calendar.getInstance(tz)
            c.set(p[0].toInteger(), p[1].toInteger() - 1, p[2].toInteger(), 0, 0, 0)
            c.set(Calendar.MILLISECOND, 0)
            return c.getTimeInMillis()
        } catch (e) { /* fall through to saved / today */ }
    }
    long saved = (state.intervalAnchorMs ?: 0L) as long
    if (saved > 0) return saved
    return localMidnightMs(now())
}

// True if the local day containing `ms` is an "every N days" run day.
private boolean isIntervalRunDayMs(long ms) {
    int n = (settings.scheduleIntervalDays ?: 2) as int
    if (n < 1) n = 1
    long diffDays = Math.round((localMidnightMs(ms) - localMidnightMs(intervalAnchorMs())) / 86400000.0d)
    return (((diffDays % n) + n) % n) == 0L
}

private boolean isIntervalRunToday() {
    return isIntervalRunDayMs(now())
}

// Hint paragraph for the schedule page: the next few run dates in the cycle.
private String intervalPreviewHint() {
    TimeZone tz = location?.timeZone ?: TimeZone.getDefault()
    List<String> dates = []
    Calendar c = Calendar.getInstance(tz)
    int found = 0
    for (int d = 0; d < 120 && found < 5; d++) {
        if (isIntervalRunDayMs(c.getTimeInMillis())) {
            dates << c.getTime().format("EEE MMM d", tz)
            found++
        }
        c.add(Calendar.DAY_OF_MONTH, 1)
    }
    return "Next run days: ${dates.join('  ·  ')}"
}

private String buildCron(String timeStr, List daysList) {
    Date t = timeToday(timeStr, location?.timeZone)
    Calendar c = Calendar.getInstance(location?.timeZone ?: TimeZone.getDefault())
    c.setTime(t)
    int hour = c.get(Calendar.HOUR_OF_DAY)
    int min  = c.get(Calendar.MINUTE)
    // Quartz cron day-of-week: SUN=1, MON=2, ..., SAT=7
    Map<String, Integer> mapDow = [SUN:1, MON:2, TUE:3, WED:4, THU:5, FRI:6, SAT:7]
    List<Integer> dowNums = daysList.collect { mapDow[it] }.findAll { it != null }
    String dowStr = dowNums.sort().join(",")
    return "0 ${min} ${hour} ? * ${dowStr}"
}

// Per-window cron handlers — one per start time so their schedules don't
// overwrite each other (Hubitat keys a schedule by its handler name). They all
// funnel into the single runSchedule(), which carries the re-entrancy guard.
def runScheduleW1() { runSchedule([:]) }
def runScheduleW2() { runSchedule([:]) }
def runScheduleW3() { runSchedule([:]) }
def preRunNotifyW1() { preRunNotify() }
def preRunNotifyW2() { preRunNotify() }
def preRunNotifyW3() { preRunNotify() }

// =========================================================================
// Schedule entry — fires at the configured time
// =========================================================================

def runSchedule(Map opts = [:]) {
    boolean manual = (opts?.manual == true)
    // Re-entrancy guard: two scheduled invocations seconds apart (e.g. a re-init
    // race that briefly leaves a duplicate cron, or any double trigger) must never
    // both start a run. Ignore a non-manual call landing within 15s of the last
    // one and log it, so a recurrence is visible. Manual runs are never blocked.
    Long nowMs = now()
    Long lastMs = (state.lastSchedEntryMs ?: 0L) as long
    if (!manual && (nowMs - lastMs) < 15000L) {
        log.warn "${app.label}: ignoring duplicate runSchedule (${nowMs - lastMs}ms after the previous trigger) — suppressing a double-start"
        return
    }
    if (!manual) state.lastSchedEntryMs = nowMs
    // Manual/on-demand runs bypass SCHEDULING holds (off-cycle day, quiet hours,
    // weather forecast, forced rain delay, pause-for-hours) but ALWAYS respect
    // ACTIVE SAFETY: pause sensors (wind/contacts), a wet rain sensor, mode/HSM.
    log.info "${app.label}: runSchedule — manual=${manual}"
    if (!manual && isPaused()) {
        if (descTextEnable) log.info "${app.label}: manually paused — skipping run"
        notify("skip.manual")
        recordRunSkip("manual pause")
        return
    }
    if (!manual && state.skipNextRun) {
        log.info "${app.label}: skip-next-run flag consumed"
        state.skipNextRun = false
        recordRunSkip("skip-next requested")
        notify("skip.next")
        return
    }
    // Every-N-days gate: in interval mode the cron fires daily, so skip quietly
    // on off-cycle days. A manual "Run now" (manual=true) bypasses this.
    if (!manual && isIntervalMode() && !isIntervalRunToday()) {
        if (descTextEnable) log.info "${app.label}: off-cycle day (every ${settings.scheduleIntervalDays ?: 2} days) — no run"
        return
    }
    Long rd = (state.forcedRainDelayUntilMs ?: 0L) as long
    if (!manual && rd > now()) {
        String until = new Date(rd).format("yyyy-MM-dd HH:mm", location?.timeZone ?: TimeZone.getDefault())
        log.info "${app.label}: forced rain delay active until ${until}"
        recordRunSkip("forced rain delay until ${until}")
        notify("skip.forced", [until: until])
        return
    }
    if (!manual && quietHoursActive()) {
        log.info "${app.label}: skipped — in quiet hours"
        recordRunSkip("quiet hours")
        notify("skip.quiet")
        return
    }
    if (modeShouldPause()) {
        log.info "${app.label}: skipped — Hubitat mode is ${location?.mode}"
        recordRunSkip("mode=${location?.mode}")
        notify("skip.mode", [mode: location?.mode])
        return
    }
    if (hsmShouldPause()) {
        log.info "${app.label}: skipped — HSM is ${location?.hsmStatus}"
        recordRunSkip("HSM=${location?.hsmStatus}")
        notify("skip.hsm", [hsm: location?.hsmStatus])
        return
    }
    if (rainSensorWet()) {
        String who = rainSensorReason()
        log.info "${app.label}: skipped by binary rain sensor (${who})"
        notify("skip.rain.sensor", [sensor: who])
        recordRunSkip("rain sensor wet (${who})")
        return
    }
    // Smart-skip: frost / cold / wind from forecast (advisory — manual overrides)
    Map smartSkipResult = manual ? [skip: false] : smartSkipCheck()
    if (smartSkipResult?.skip) {
        String reason = smartSkipResult.reason as String
        log.info "${app.label}: smart-skip — ${reason}"
        recordRunSkip(reason)
        notify(smartSkipResult.eventKey as String, smartSkipResult.ctx as Map)
        return
    }
    // Pause sensors (water heater ON, a door/contact OPEN) are TRANSIENT — they
    // never skip the cycle. ALWAYS hold the run and start it automatically once
    // every pause sensor clears — for scheduled AND manual runs (Run switch /
    // Run-now button) alike. Genuine skips (wet rain sensor, weather, quiet hours,
    // mode/HSM) were checked above and already returned, so they take precedence.
    // pause/stop mode governs MID-RUN behavior only, not the start.
    if (externalPauseActive()) {
        String who = externalPauseReason()
        log.info "${app.label}: pause sensor active (${who}) — holding run until it clears"
        state.deferredRunPending = true
        notify("schedule.defer", [sensor: who])
        return
    }
    if (state.running) {
        log.warn "${app.label}: previous run still active (zone ${state.currentZoneIdx}). Skipping new trigger."
        return
    }
    // Auto-stagger: if another instance holds the coordination switch, defer.
    if (!manual && settings.coordSwitch && settings.coordSwitch.currentValue("switch") == "on") {
        Integer retried = (state.coordRetries ?: 0) as int
        Integer maxRetries = (settings.coordMaxRetries ?: 10) as int
        Integer defer = (settings.coordDeferSec ?: 60) as int
        if (retried >= maxRetries) {
            log.warn "${app.label}: coordination switch held — gave up after ${retried} retries, skipping"
            state.coordRetries = 0
            recordRunSkip("coordination switch held (${retried} retries)")
            notify("skip.coord", [count: retried])
            return
        }
        state.coordRetries = retried + 1
        log.info "${app.label}: coordination switch held — deferring ${defer}s (retry ${state.coordRetries}/${maxRetries})"
        runIn(defer, "runSchedule")
        return
    }
    state.coordRetries = 0
    Integer n = (settings.zoneCountPref ?: 0) as int
    if (n < 1) {
        log.warn "${app.label}: no zones configured — nothing to run"
        return
    }

    // Weather gate (rain-delay is forecast-based and advisory — manual overrides)
    Map weather = settings.rainDelayEnabled || settings.seasonalEnabled ? fetchWeather() : null
    if (!manual && settings.rainDelayEnabled && weather && shouldSkipForRain(weather)) {
        String reason = rainSkipReason(weather)
        log.info "${app.label}: skipping schedule — ${reason}"
        notify("skip.rain.weather", [reason: reason])
        return
    }

    // Build the run plan
    List<Integer> plan = []
    for (int i = 1; i <= n; i++) {
        if (settings."zone${i}Enabled" != false && settings."zone${i}Switch") {
            // Per-zone moisture skip (skip + adapt + learn all honor target threshold at start)
            String mode = settings."zone${i}MoistureMode" ?: (settings."zone${i}MoistureSensor" ? "skip" : "off")
            if (mode in ["skip", "earlyStop", "adapt", "learn"]) {
                Float cur = readZoneMoisture(i)
                Integer target = (settings."zone${i}MoistureTarget" ?: 50) as int
                if (cur != null && cur >= target) {
                    if (descTextEnable) log.info "${app.label}: zone ${i} skipped (moisture ${cur}% ≥ target ${target}%)"
                    String zname = settings."zone${i}Name" ?: "Zone ${i}"
                    notify("skip.moisture", [zone: zname, moisture: cur, target: target])
                    continue
                }
            }
            plan << i
        }
    }
    if (settings.scheduleOrder == "random") Collections.shuffle(plan)
    log.info "${app.label}: run plan = ${plan} (zoneCountPref=${n})"
    if (plan.isEmpty()) {
        log.warn "${app.label}: no zones to run — check that zones are enabled and each has a Switch device assigned (zoneCountPref=${n})"
        return
    }

    // Compute seasonal multiplier once per run
    BigDecimal seasonalMult = (settings.seasonalEnabled && weather) ? computeSeasonalMultiplier(weather) : 1.0
    state.seasonalMult = seasonalMult.toString()

    if (descTextEnable) {
        log.info "${app.label}: starting run — plan=${plan}, seasonal=×${seasonalMult}"
    }
    Map est = estimateRunSeconds(plan, seasonalMult)
    notify("schedule.start", [planSize: plan.size(), seasonalMult: seasonalMult,
                              estTotal: fmtDuration(est.total), estWater: fmtDuration(est.water),
                              estSoak: fmtDuration(est.soak)])

    state.zonesPlan = plan
    state.currentZoneIdx = 0
    state.running = true
    state.deferredRunPending = false   // committing to a run clears any held-defer
    syncRunControlSwitch()   // reflect "running" on the HomeKit control switch
    recordRunStart(plan, seasonalMult)
    // Acquire the shared coordination lock for the duration of this run
    if (settings.coordSwitch) {
        try { settings.coordSwitch.on() }
        catch (e) { log.warn "coordSwitch.on(): ${e.message}" }
    }
    startPumpIfNeeded()
    publishDashboardState()
    Integer preSec = (settings.pumpSwitch && settings.pumpPreSec) ? (settings.pumpPreSec as int) : 0
    runIn(preSec, "startNextZone")
}

// Launch a run that was held at its scheduled start by an active pause sensor,
// once that sensor has cleared. Re-enters the full scheduling gate so a condition
// that changed while we waited (rain now wet, quiet hours, etc.) is honored.
def startDeferredRun() {
    if (state.running) {
        log.info "${app.label}: held run skipped — a run is already active"
        return
    }
    if (externalPauseActive()) {
        log.info "${app.label}: held run aborted — pause sensor active again (${externalPauseReason()})"
        state.deferredRunPending = true
        return
    }
    log.info "${app.label}: launching held run — pause sensors clear"
    runSchedule([:])
}

// =========================================================================
// Zone sequencing
// =========================================================================

def startNextZone() {
    if (!state.running) return
    List<Integer> plan = state.zonesPlan as List<Integer>
    Integer idx = state.currentZoneIdx ?: 0
    if (idx >= plan.size()) {
        finishRun()
        return
    }
    Integer zid = plan[idx]
    def sw = settings."zone${zid}Switch"
    String zname = settings."zone${zid}Name" ?: "Zone ${zid}"
    if (!sw) {
        log.warn "${app.label}: zone ${zid} has no switch configured — skipping"
        state.currentZoneIdx = idx + 1
        runIn(1, "startNextZone")
        return
    }

    Integer baseMin = resolveZoneBaseMinutes(zid)
    BigDecimal mult = (state.seasonalMult ?: "1.0") as BigDecimal

    // Capture pre-moisture and (if mode is adapt/learn) compute an adaptive
    // multiplier on top of the seasonal one.
    Float preMoisture = readZoneMoisture(zid)
    String moistureMode = settings."zone${zid}MoistureMode" ?: (settings."zone${zid}MoistureSensor" ? "skip" : "off")
    state.currentZonePreMoisture = preMoisture
    state.currentZoneMoistureMode = moistureMode
    BigDecimal moistureMult = 1.0
    if (moistureMode in ["adapt", "learn"] && preMoisture != null) {
        moistureMult = computeMoistureMultiplier(zid, preMoisture, baseMin)
        if (descTextEnable) {
            log.info "${app.label}: ${zname} moisture-adapted ×${moistureMult} (current=${preMoisture}%)"
        }
    }

    Integer rawAdj = Math.round((baseMin * mult.multiply(moistureMult)).floatValue()) as int
    Integer adjMin = Math.max(1, Math.min((settings.scheduleMaxRunMinutes ?: 60) as int, rawAdj))
    if (moistureMode in ["adapt", "learn"] && preMoisture != null && adjMin != baseMin) {
        notify("moisture.adapted", [zone: zname, baseMin: baseMin, adjMin: adjMin,
                                    moisture: preMoisture, dryness: moistureMult.setScale(2, BigDecimal.ROUND_HALF_UP)])
    }

    // Weekly budget enforcement
    Integer cap = (settings."zone${zid}WeeklyCapMinutes" ?: 0) as int
    if (cap > 0) {
        Integer used = (state.zoneMinutesThisWeek ?: [:])[zid.toString()] ?: 0
        Integer left = Math.max(0, cap - used)
        if (left <= 0) {
            log.info "${app.label}: ${zname} skipped — weekly budget exhausted (${used}/${cap}min)"
            notify("skip.budget", [zone: zname, reason: "budget exhausted (${used}/${cap}min)"])
            state.currentZoneIdx = idx + 1
            runIn(1, "startNextZone")
            return
        }
        if (adjMin > left) {
            if (descTextEnable) log.info "${app.label}: ${zname} clipped from ${adjMin}m to ${left}m by weekly budget"
            adjMin = left
        }
    }

    Integer cycles, perCycleMin, soakMin
    boolean fertArmed = (settings."zone${zid}FertArmed" == true)
    if (fertArmed) {
        cycles      = (settings."zone${zid}FertBurstCount" ?: 3) as int
        perCycleMin = (settings."zone${zid}FertBurstMin"   ?: 2) as int
        soakMin     = (settings."zone${zid}FertSoakMin"    ?: 15) as int
        adjMin      = cycles * perCycleMin   // override total
        if (descTextEnable) log.info "${app.label}: ${zname} fertilizer mode — ${cycles}× ${perCycleMin}m bursts, ${soakMin}m soak"
        notify("fertilizer.armed", [zone: zname, cycles: cycles, perCycleMin: perCycleMin, soakMin: soakMin])
    } else {
        cycles = ((settings."zone${zid}CycleSoak" ?: "1") as int)
        perCycleMin = Math.max(1, (adjMin / cycles) as int)
        soakMin     = (settings."zone${zid}SoakMinutes" ?: 10) as int
    }

    state.currentZoneId       = zid
    state.currentZoneCycles   = cycles
    state.currentZoneCycleIdx = 0
    state.currentZonePerMin   = perCycleMin
    state.currentZoneSoakMin  = soakMin
    state.currentZoneTotalAdjMin = adjMin

    if (descTextEnable) {
        log.info "${app.label}: ▶ ${zname} — ${adjMin}m (${cycles}× ${perCycleMin}m, soak ${soakMin}m); switch=${sw.displayName}"
    }
    state.lastRunByZone[zid.toString()] = new Date().format("yyyy-MM-dd HH:mm", location?.timeZone ?: TimeZone.getDefault())
    sw.on()
    // The app just successfully drove this relay — proof its OWNING controller is
    // reachable. Stamp that specific parent so the watchdog won't false-flag a
    // controller the app just drove, while still catching a real outage on a
    // different controller that didn't run this cycle. The per-cycle on-time is
    // the longest continuous actuation, which the auto-off-timer guard uses.
    markControllerReachable(sw, perCycleMin * 60)
    // Mirror onto the exposed child Virtual Switch (HomeKit/dashboard view),
    // then reconcile all tiles so any prior zone's tile is cleared.
    setZoneChildSwitch(zid, "on")
    runIn(3, "syncAllZoneChildren")
    // Early-stop subscription on the moisture sensor for the duration of this zone
    if (moistureMode in ["earlyStop", "adapt", "learn"]) {
        def msens = settings."zone${zid}MoistureSensor"
        if (msens) {
            String attr = settings."zone${zid}MoistureAttribute" ?: "humidity"
            subscribe(msens, attr, "moistureEvent")
        }
    }
    // Track phase start + duration so pauseRunningSchedule() can compute
    // exactly how many seconds are left if we have to stop mid-cycle.
    state.currentPhaseStartMs = now()
    state.currentPhaseDurationSec = perCycleMin * 60
    state.currentPhaseType = "water"
    runIn(perCycleMin * 60, "zoneCyclePhaseDone")
}

def zoneCyclePhaseDone() {
    if (!state.running) return
    Integer zid = state.currentZoneId as int
    def sw = settings."zone${zid}Switch"
    String zname = settings."zone${zid}Name" ?: "Zone ${zid}"
    if (sw) sw.off()
    setZoneChildSwitch(zid, "off")
    runIn(3, "syncAllZoneChildren")

    Integer cycles    = state.currentZoneCycles as int
    Integer cycleIdx  = (state.currentZoneCycleIdx ?: 0) as int
    cycleIdx++
    state.currentZoneCycleIdx = cycleIdx

    if (cycleIdx >= cycles) {
        // Zone finished — advance after between-zone delay
        if (descTextEnable) log.info "${app.label}: ■ ${zname} done"
        Integer totalMin = ((state.currentZoneTotalAdjMin ?: 0) as int)
        Integer totalSec = totalMin * 60
        Float postMoisture = readZoneMoisture(zid)
        recordZoneLearning(zid, state.currentZonePreMoisture as Float, postMoisture, totalMin)
        unsubscribeZoneMoisture(zid)
        recordZoneRun(zid, totalSec, "ok")
        consumeWeeklyBudget(zid, totalMin)
        // Disarm fertilizer mode if it was armed for this run
        if (settings."zone${zid}FertArmed" == true) {
            app.updateSetting("zone${zid}FertArmed", [value: "false", type: "bool"])
            notify("fertilizer.disarmed", [zone: zname])
        }
        state.currentZoneIdx = (state.currentZoneIdx ?: 0) + 1
        Integer betweenSec = (settings.scheduleBetweenZoneSec ?: 10) as int
        // Track the gap as a pausable phase so a pause sensor that fires
        // between zones resumes the gap instead of reporting "0s remaining".
        state.currentPhaseStartMs = now()
        state.currentPhaseDurationSec = betweenSec
        state.currentPhaseType = "gap"
        runIn(betweenSec, "startNextZone")
    } else {
        // Soak then resume same zone
        if (descTextEnable) log.info "${app.label}: ${zname} — soak ${state.currentZoneSoakMin}m (cycle ${cycleIdx + 1}/${cycles})"
        Integer soakMin = state.currentZoneSoakMin as int
        // Account soak time separately (valves are off — not watering).
        if (state.currentRunRecord) {
            def r = state.currentRunRecord
            r.soakSec = ((r.soakSec ?: 0) as int) + soakMin * 60
            state.currentRunRecord = r
        }
        // Track the soak as a pausable phase (valves off) so a pause sensor
        // that fires mid-soak resumes the remaining soak instead of advancing.
        state.currentPhaseStartMs = now()
        state.currentPhaseDurationSec = soakMin * 60
        state.currentPhaseType = "soak"
        runIn(soakMin * 60, "zoneCycleResume")
    }
}

def zoneCycleResume() {
    if (!state.running) return
    Integer zid = state.currentZoneId as int
    def sw = settings."zone${zid}Switch"
    String zname = settings."zone${zid}Name" ?: "Zone ${zid}"
    Integer perCycleMin = state.currentZonePerMin as int
    if (sw) {
        if (descTextEnable) log.info "${app.label}: ▶ ${zname} resume cycle ${(state.currentZoneCycleIdx + 1)}/${state.currentZoneCycles} (${perCycleMin}m)"
        sw.on()
        state.currentPhaseStartMs = now()
        state.currentPhaseDurationSec = perCycleMin * 60
        state.currentPhaseType = "water"
        runIn(perCycleMin * 60, "zoneCyclePhaseDone")
    }
}

def finishRun() {
    if (descTextEnable) log.info "${app.label}: schedule finished"
    // Build the completion summary from the run record before it's archived.
    def rec = state.currentRunRecord
    Map finCtx = [:]
    if (rec) {
        Long sMs = (rec.startedMs ?: now()) as long
        int elapsedSec = (int)((now() - sMs) / 1000L)
        int baseSec    = (rec.baseSec   ?: 0) as int
        int waterSec   = (rec.waterSec  ?: 0) as int
        int soakSec    = (rec.soakSec   ?: 0) as int
        int pausedSec  = (rec.pausedSec ?: 0) as int
        String mult    = (rec.seasonal ?: "1.0") as String
        // Show the ACTUAL extra over the scheduled base (seasonal multiplier PLUS
        // per-zone whole-minute rounding and any moisture adjustment), so the
        // summary reconciles: scheduled + delta == watered.
        int seasonalDeltaSec = waterSec - baseSec
        String seasonalStr = "×${mult}" + (seasonalDeltaSec != 0 ? " (${seasonalDeltaSec > 0 ? '+' : '−'}${fmtDuration(Math.abs(seasonalDeltaSec))})" : "")
        finCtx = [elapsed: fmtDuration(elapsedSec), watered: fmtDuration(waterSec),
                  scheduled: fmtDuration(baseSec), seasonal: seasonalStr,
                  soak: fmtDuration(soakSec), paused: fmtDuration(pausedSec),
                  zones: ((rec.plan ?: []) as List).size()]
    }
    recordRunFinish("completed")
    state.running = false
    syncRunControlSwitch()   // reflect "idle" on the HomeKit control switch
    state.currentZoneIdx = 0
    Integer postSec = (settings.pumpSwitch && settings.pumpPostSec) ? (settings.pumpPostSec as int) : 0
    runIn(postSec, "stopPumpIfNeeded")
    // Release the shared auto-stagger coordination lock
    if (settings.coordSwitch) {
        try { settings.coordSwitch.off() }
        catch (e) { log.warn "coordSwitch.off(): ${e.message}" }
    }
    notify("schedule.finish", finCtx)
    runIn(3, "syncAllZoneChildren")   // clear every zone tile after the run
    publishDashboardState()
}

def stopAllZones() {
    log.warn "${app.label}: stop-all invoked"
    unschedule("startNextZone")
    unschedule("zoneCyclePhaseDone")
    unschedule("zoneCycleResume")
    unschedule("doResumeAfterPause")
    unschedule("manualZoneTimeout")
    Integer n = (settings.zoneCountPref ?: 0) as int
    for (int i = 1; i <= n; i++) {
        def sw = settings."zone${i}Switch"
        if (sw) try { sw.off() } catch (e) { log.warn "stop zone ${i}: ${e.message}" }
        unsubscribeZoneMoisture(i)
        setZoneChildSwitch(i, "off")
    }
    state.manualActive = [:]
    state.running = false
    state.paused = false
    state.pausedRemainingSec = 0
    state.currentZoneIdx = 0
    stopPumpIfNeeded()
    syncRunControlSwitch()   // reflect "idle" on the HomeKit control switch
    if (settings.coordSwitch) {
        try { settings.coordSwitch.off() }
        catch (e) { log.warn "coordSwitch.off(): ${e.message}" }
    }
}

// =========================================================================
// Pump / master valve
// =========================================================================

def startPumpIfNeeded() {
    if (settings.pumpSwitch) {
        if (descTextEnable) log.info "${app.label}: pump on (${settings.pumpSwitch.displayName})"
        try { settings.pumpSwitch.on() } catch (e) { log.warn "pump on failed: ${e.message}" }
    }
}

def stopPumpIfNeeded() {
    if (settings.pumpSwitch) {
        if (descTextEnable) log.info "${app.label}: pump off (${settings.pumpSwitch.displayName})"
        try { settings.pumpSwitch.off() } catch (e) { log.warn "pump off failed: ${e.message}" }
    }
}

// =========================================================================
// Pause handling
// =========================================================================

private boolean isPaused() {
    Integer hrs = (settings.pauseHours ?: 0) as int
    if (hrs <= 0) return false
    Long until = (state.pauseUntilMs ?: 0L) as long
    Long now = now()
    if (until == 0L) {
        until = now + (hrs * 3600L * 1000L)
        state.pauseUntilMs = until
    }
    return now < until
}

// =========================================================================
// Weather — Open-Meteo (adapted from the Spruce migration)
// =========================================================================

private Map fetchWeather() {
    Map om = state.__omCacheData
    Long cachedAt = state.__omCacheAt as Long
    long nowMs = now()
    // Invalidate cache if the hub's unit scale changed since the cached fetch —
    // the cached values would otherwise be in the old units.
    String scaleNow = isMetric() ? "C" : "F"
    if (state.__omCacheScale != scaleNow) { om = null; cachedAt = null }
    if (!om || !cachedAt || (nowMs - cachedAt) > (15L * 60L * 1000L)) {
        om = omFetch()
        if (om) {
            state.__omCacheData = om
            state.__omCacheAt = nowMs
            state.__omCacheScale = scaleNow
        }
    }
    return om
}

private Map omFetch() {
    BigDecimal lat = (location?.latitude  ?: 0.0) as BigDecimal
    BigDecimal lon = (location?.longitude ?: 0.0) as BigDecimal
    if (lat == 0 && lon == 0) {
        log.warn "${app.label}: hub location not configured (Settings → Location). Weather disabled."
        return null
    }
    Map params = [
        uri: "https://api.open-meteo.com/v1/forecast",
        query: [
            latitude: lat.toString(),
            longitude: lon.toString(),
            daily: "temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_mean,wind_speed_10m_max,sunrise,sunset",
            hourly: "relative_humidity_2m,precipitation",
            past_days: 1,
            forecast_days: 4,
            temperature_unit: tApiUnit(),
            precipitation_unit: rApiUnit(),
            wind_speed_unit: wApiUnit(),
            timezone: "auto"
        ],
        timeout: 15
    ]
    try {
        Map result = null
        httpGet(params) { resp ->
            if (resp?.status == 200 && resp.data) {
                result = resp.data instanceof Map ? resp.data
                       : new groovy.json.JsonSlurper().parseText(resp.data.text)
            } else {
                log.warn "${app.label}: Open-Meteo HTTP ${resp?.status}"
            }
        }
        return result
    } catch (e) {
        log.warn "${app.label}: Open-Meteo fetch failed: ${e.message}"
        return null
    }
}

private boolean shouldSkipForRain(Map om) {
    if (!om?.daily) return false
    String todayISO = new Date().format("yyyy-MM-dd", location?.timeZone ?: TimeZone.getDefault())
    int idx = (om.daily.time as List).findIndexOf { it == todayISO }
    if (idx < 0) idx = (om.daily.time.size() > 1) ? 1 : 0
    Float popToday  = (om.daily.precipitation_probability_mean ?: [])[idx] as Float ?: 0.0f
    Float qpfToday  = (om.daily.precipitation_sum             ?: [])[idx] as Float ?: 0.0f
    Float past24    = omPrecipLast24h(om)
    // External rain gauge override (rainToday in inches)
    if (settings.rainSensorDevice) {
        def rt = settings.rainSensorDevice.currentValue("rainToday")
        if (rt != null) past24 = Math.max(past24, (rt as float))
    }
    Float popThr    = (settings.rainPopThreshold    ?: 60)  as float
    Float amtThr    = (settings.rainAmountThreshold ?: 0.2) as float
    return (popToday >= popThr) || (qpfToday >= amtThr) || (past24 >= amtThr)
}

private String rainSkipReason(Map om) {
    String todayISO = new Date().format("yyyy-MM-dd", location?.timeZone ?: TimeZone.getDefault())
    int idx = (om.daily.time as List).findIndexOf { it == todayISO }
    if (idx < 0) idx = 1
    Float popToday  = (om.daily.precipitation_probability_mean ?: [])[idx] as Float ?: 0.0f
    Float qpfToday  = (om.daily.precipitation_sum             ?: [])[idx] as Float ?: 0.0f
    Float past24    = omPrecipLast24h(om)
    return "POP today=${Math.round(popToday)}%, forecast=${qpfToday}in, past24h=${past24.round(2)}in"
}

private float omPrecipLast24h(Map om) {
    def times = om?.hourly?.time
    def precs = om?.hourly?.precipitation
    if (!times || !precs) return 0.0f
    Date now = new Date()
    long cutoff = now.getTime() - (24L * 60L * 60L * 1000L)
    float total = 0.0f
    times.eachWithIndex { String ts, int i ->
        try {
            Date t = Date.parse("yyyy-MM-dd'T'HH:mm", ts)
            if (t.getTime() >= cutoff && t.getTime() <= now.getTime()) {
                Number p = (i < precs.size()) ? precs[i] : null
                if (p != null) total += (p as float)
            }
        } catch (ignored) {}
    }
    return total
}

private BigDecimal computeSeasonalMultiplier(Map om) {
    if (!om?.daily?.temperature_2m_max) return 1.0
    List<Number> highs = om.daily.temperature_2m_max
    if (!highs) return 1.0
    // Average next 3 days of high temps
    int n = Math.min(3, highs.size())
    float total = 0; int counted = 0
    for (int i = 0; i < n; i++) {
        if (highs[i] != null) { total += (highs[i] as float); counted++ }
    }
    if (counted == 0) return 1.0
    float avg = total / counted
    // Baseline at a comfortable growing temp; warmer → longer, cooler → shorter.
    // °F: 70 baseline, +1.5%/°F above, −1.0%/°F below. Metric mirrors per °C (×1.8).
    float baseline  = isMetric() ? 21.0f : 70.0f
    float coefAbove = isMetric() ? 2.7f  : 1.5f
    float coefBelow = isMetric() ? 1.8f  : 1.0f
    float pctDelta = (avg - baseline) * (avg > baseline ? coefAbove : coefBelow)
    BigDecimal cap = ((settings.seasonalMaxPct ?: 50) as BigDecimal) / 100.0
    BigDecimal scale = ((1.0 + (pctDelta / 100.0)) as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP)
    BigDecimal min = (1.0 - cap).setScale(2, BigDecimal.ROUND_HALF_UP)
    BigDecimal max = (1.0 + cap).setScale(2, BigDecimal.ROUND_HALF_UP)
    if (scale < min) scale = min
    if (scale > max) scale = max
    return scale
}

// =========================================================================
// Notifications
// =========================================================================

// notify(key, contextOrMessage)
//   - Preferred form: notify("schedule.start", [planSize: 5, seasonalMult: "1.15"])
//   - Legacy form:    notify("legacy.tag", "raw message string")
// Routes through per-event enable/template-message lookup, substitutes
// template variables, and fans out to every regular notification device
// AND every Pushover device. Per-event Pushover priority/sound overrides
// apply; otherwise the device defaults are used.
private void notify(String key, def ctx = [:]) {
    String safeKey = key.replace(".", "_").replace("-", "_")
    Map meta = NOTIFY_EVENTS[key] as Map
    // Per-event enable check (if event isn't in NOTIFY_EVENTS map, default ON)
    if (meta != null) {
        boolean defOn = !(meta.defaultOff == true)
        def enabled = settings."notifyEvent_${safeKey}"
        if (enabled == false || (enabled == null && !defOn)) return
    }
    // Resolve message: per-event override > NOTIFY_EVENTS default > raw string ctx
    String template
    if (settings."notifyMsg_${safeKey}") {
        template = settings."notifyMsg_${safeKey}"
    } else if (meta?.default) {
        template = meta.default as String
    } else if (ctx instanceof String) {
        template = ctx as String
    } else {
        template = "[${key}]"
    }
    Map context = (ctx instanceof Map) ? (ctx as Map) : [:]
    if (!context.app) context.app = app.label
    String msg = applyTemplate(template, context)

    // Regular notification devices (any capability.notification)
    try { settings.notifyDevices?.each { it.deviceNotification(msg) } }
    catch (e) { log.warn "notify(${key}) regular: ${e.message}" }

    // Pushover (if any)
    if (settings.pushoverDevice) {
        String evtPrio = settings."notifyPriority_${safeKey}"
        // "default" means "use the device default priority" — don't treat it as an override.
        String prio = (evtPrio && evtPrio != "default") ? evtPrio : (settings.pushoverDefaultPriority ?: "0 normal")
        if (key == "error" && settings.pushoverEmergencyOnError) prio = "2 emergency"
        String sound = settings.pushoverDefaultSound
        sendPushover(msg, prio, sound)
    }
}

// Human-readable duration: 479 → "7m 59s", 3661 → "1h 1m 1s", 0 → "0s".
private String fmtDuration(int totalSec) {
    if (totalSec < 0) totalSec = 0
    int h = totalSec.intdiv(3600)
    int m = (totalSec % 3600).intdiv(60)
    int s = totalSec % 60
    List<String> parts = []
    if (h > 0) parts << "${h}h"
    if (m > 0) parts << "${m}m"
    if (s > 0 || parts.isEmpty()) parts << "${s}s"
    return parts.join(" ")
}

private String applyTemplate(String template, Map context) {
    if (!template) return ""
    String out = template
    context.each { k, v -> out = out.replace('${' + k + '}', (v == null ? "" : v.toString())) }
    // Strip any remaining ${...} tokens (variable was not supplied)
    out = out.replaceAll(/\$\{[^}]+\}/, "")
    return out
}

private void sendPushover(String msg, String priority, String sound) {
    if (!settings.pushoverDevice) return
    // Many community Pushover drivers accept a "[priority|sound|message]" prefix.
    // Pushover priority code is the leading number on the option string.
    String pCode = (priority ?: "0").toString().tokenize(" ")[0]
    if (!(pCode ==~ /-?\d+/)) pCode = "0"   // non-numeric (e.g. "default") → normal, no prefix
    String prefix
    if (sound) prefix = "[${pCode}|${sound}|"
    else if (pCode && pCode != "0") prefix = "[${pCode}|"
    else prefix = null
    String wire = prefix ? "${prefix}${msg}]" : msg
    try { settings.pushoverDevice.each { it.deviceNotification(wire) } }
    catch (e) {
        // Fallback to plain message if the bracket format isn't supported.
        try { settings.pushoverDevice.each { it.deviceNotification(msg) } }
        catch (e2) { log.warn "sendPushover: ${e2.message}" }
    }
}

// Test-button helper
def sendNotificationTest() {
    notify("error", [detail: "test notification at ${nowString()}"])
    log.info "${app.label}: test notification dispatched"
}

// =========================================================================
// Rain sensor & external pause sensor helpers
// =========================================================================

private boolean rainSensorWet() {
    // Wet/dry water sensors
    if ((settings.rainSensorWaterDevices ?: []).any { it.currentValue("water") == "wet" }) return true
    // Contact-based rain sensors (incl. ZEN16 inputs as child contact devices)
    String wetState = settings.rainSensorContactWetState ?: "closed"
    if ((settings.rainSensorContactDevices ?: []).any { it.currentValue("contact") == wetState }) return true
    return false
}

private String rainSensorReason() {
    List names = []
    (settings.rainSensorWaterDevices ?: []).each {
        if (it.currentValue("water") == "wet") names << it.displayName
    }
    String wetState = settings.rainSensorContactWetState ?: "closed"
    (settings.rainSensorContactDevices ?: []).each {
        if (it.currentValue("contact") == wetState) names << "${it.displayName} (contact ${wetState})"
    }
    return names.join(", ") ?: "unknown"
}

private boolean externalPauseActive() {
    String cState = settings.pauseContactsState ?: "open"
    String sState = settings.pauseSwitchesState ?: "on"
    if (settings.pauseContacts?.any { it.currentValue("contact") == cState }) return true
    if (settings.pauseSwitches?.any { it.currentValue("switch")  == sState }) return true
    return false
}

private String externalPauseReason() {
    String cState = settings.pauseContactsState ?: "open"
    String sState = settings.pauseSwitchesState ?: "on"
    List names = []
    (settings.pauseContacts ?: []).each {
        if (it.currentValue("contact") == cState) names << "${it.displayName}(${cState})"
    }
    (settings.pauseSwitches ?: []).each {
        if (it.currentValue("switch") == sState) names << "${it.displayName}(${sState})"
    }
    return names.join(", ") ?: "unknown"
}

// =========================================================================
// Run history (last 50)
// =========================================================================

// Estimate the run's wall-clock time up front: seasonal-adjusted watering +
// cycle soak + between-zone delays + pump pre/post. Returns seconds. It's an
// estimate — moisture skips, early-stops, weekly clips and pauses can change it.
private Map estimateRunSeconds(List plan, BigDecimal mult) {
    int waterSec = 0, soakSec = 0
    int maxRun = (settings.scheduleMaxRunMinutes ?: 60) as int
    plan.each { Integer zid ->
        int baseMin = resolveZoneBaseMinutes(zid)
        int adjMin  = Math.max(1, Math.min(maxRun, (int) Math.round((baseMin * mult).doubleValue())))
        int cycles  = ((settings."zone${zid}CycleSoak" ?: "1") as int)
        int soakMin = (settings."zone${zid}SoakMinutes" ?: 10) as int
        waterSec += adjMin * 60
        soakSec  += Math.max(0, cycles - 1) * soakMin * 60
    }
    int betweenSec = Math.max(0, plan.size() - 1) * ((settings.scheduleBetweenZoneSec ?: 10) as int)
    int pumpSec = settings.pumpSwitch ? (((settings.pumpPreSec ?: 0) as int) + ((settings.pumpPostSec ?: 0) as int)) : 0
    return [water: waterSec, soak: soakSec, total: waterSec + soakSec + betweenSec + pumpSec]
}

private void recordRunStart(List plan, BigDecimal seasonalMult) {
    int baseSec = 0
    plan.each { Integer zid -> baseSec += (resolveZoneBaseMinutes(zid) as int) * 60 }
    state.currentRunRecord = [
        startedAt: nowString(),
        startedMs: now(),
        plan:      plan,
        seasonal:  seasonalMult.toString(),
        baseSec:   baseSec,   // scheduled watering (pre-seasonal)
        waterSec:  0,         // actual watering time, accumulated per zone (excludes soak)
        soakSec:   0,         // total cycle-soak time (valves off, not watering)
        pausedSec: 0,         // total time paused mid-run
        zoneSummaries: [],
        outcome:   "running"
    ]
}

private void recordZoneRun(Integer zid, Integer durationSec, String status) {
    def rec = state.currentRunRecord
    if (!rec) return
    String label = settings."zone${zid}Name" ?: "Zone ${zid}"
    String mm = String.format("%dm%02ds", (int)(durationSec / 60), (int)(durationSec % 60))
    rec.zoneSummaries << "${label}: ${mm} (${status})"
    rec.waterSec = ((rec.waterSec ?: 0) as int) + (durationSec ?: 0)
    state.currentRunRecord = rec
}

private void recordRunFinish(String outcome) {
    def rec = state.currentRunRecord
    if (!rec) {
        // Synthesize a minimal record if we have no in-progress one
        rec = [startedAt: nowString(), zoneSummaries: [], outcome: outcome]
    }
    rec.outcome = outcome
    rec.finishedAt = nowString()
    state.currentRunRecord = null
    pushHistory(rec)
}

private void recordRunSkip(String reason) {
    pushHistory([startedAt: nowString(), zoneSummaries: [], outcome: "skipped — ${reason}"])
}

private void pushHistory(Map rec) {
    List h = (state.runHistory ?: []) as List
    h << rec
    while (h.size() > 50) h.remove(0)
    state.runHistory = h
}

private String nowString() {
    return new Date().format("yyyy-MM-dd HH:mm", location?.timeZone ?: TimeZone.getDefault())
}

// =========================================================================
// Skip-next / forced rain delay
// =========================================================================

private void skipNext() {
    state.skipNextRun = true
    log.info "${app.label}: next scheduled run will be skipped"
}

private void forceRainDelayHours(int hours) {
    Long until = now() + (hours * 3600L * 1000L)
    state.forcedRainDelayUntilMs = until
    String untilStr = new Date(until).format("yyyy-MM-dd HH:mm", location?.timeZone ?: TimeZone.getDefault())
    log.info "${app.label}: forced rain delay until ${untilStr} (${hours}h)"
    notify("skip.forced", [until: untilStr])
}

private void clearForcedDelays() {
    state.skipNextRun = false
    state.forcedRainDelayUntilMs = 0L
    log.info "${app.label}: cleared all forced delays"
}

private String forcedDelayDisplayString() {
    List parts = []
    if (state.skipNextRun) parts << "skip-next armed"
    Long rd = (state.forcedRainDelayUntilMs ?: 0L) as long
    if (rd > now()) {
        String until = new Date(rd).format("yyyy-MM-dd HH:mm", location?.timeZone ?: TimeZone.getDefault())
        long hrs = ((rd - now()) / 3600000L) as long
        parts << "rain delay until ${until} (${hrs}h)"
    }
    return parts.isEmpty() ? "no forced delays active" : parts.join(", ")
}

// =========================================================================
// ZEN16 hardware safety push
// =========================================================================

// Detect setParameter argument order on a given device. Hubitat's built-in
// Zooz driver uses (paramNumber, size, value); jtp10181's Advanced driver
// uses (paramNumber, value, size). We peek at the command's argument names.
private String detectSetParameterStyle(dev) {
    // Manual override wins. Auto-detection from the app side is unreliable:
    // getSupportedCommands() exposes argument TYPES (["NUMBER",...]), not names,
    // so the old name-sniff never matched and always fell back to "builtin" —
    // which pushed the WRONG arg order to the jtp10181/vendored drivers, so the
    // auto-off timers silently never took.
    String pref = settings.hwSetParamStyle
    if (pref == "jtp10181" || pref == "builtin") return pref
    try {
        def cmd = dev.getSupportedCommands()?.find { it.name == "setParameter" }
        int sizeIdx = -1, valueIdx = -1
        cmd?.arguments?.eachWithIndex { arg, int i ->
            String n = (arg instanceof Map ? (arg.name ?: "") : arg as String)
                       .toLowerCase().replace("*", "").trim()
            if (n == "size")  sizeIdx = i
            if (n == "value") valueIdx = i
        }
        if (valueIdx == 1 && sizeIdx == 2) return "jtp10181"  // (num, value, size)
        if (sizeIdx == 1 && valueIdx == 2) return "builtin"   // (num, size, value)
    } catch (e) { }
    // Default: the vendored/recommended jtp10181 ZEN16/ZEN17 drivers — and in
    // fact every Zooz relay driver that exposes setParameter — use (num, value, size).
    return "jtp10181"
}

private void callSetParameter(dev, String style, int paramNum, int size, int value) {
    if (style == "jtp10181") {
        dev.setParameter(paramNum, value, size)
    } else {
        dev.setParameter(paramNum, size, value)
    }
}

def pushHardwareSafety() {
    def parents = settings.hwZen16Parents
    if (!parents) {
        log.warn "${app.label}: no Zooz relay parents picked — nothing to push"
        state.hwLastPushSummary = "No parents selected"
        return
    }
    Integer requested = (settings.hwAutoOffMinutes ?: (((settings.scheduleMaxRunMinutes ?: 60) as int) + 5)) as int
    int ok = 0, fail = 0
    List<String> log_ = []
    Map expectedByDev = [:]
    parents.each { dev ->
        if (!dev.hasCommand("setParameter")) {
            log_ << "${dev.displayName}: setParameter() unsupported — the built-in driver does not expose it; switch the parent to jtp10181's Advanced driver"
            fail++
            return
        }
        String modelKey = settings."hwModel_${dev.id}" ?: "3"
        Map model = (ZOOZ_RELAY_MODELS[modelKey] ?: ZOOZ_RELAY_MODELS["3"]) as Map
        List<Integer> autoOffParams = (model.autoOff   ?: []) as List<Integer>
        List<Integer> unitParams    = (model.unitParam ?: []) as List<Integer>
        String style = detectSetParameterStyle(dev)
        // Guard: never set an auto-off shorter than the longest run this instance
        // drives on the controller — that would let the hardware cut our own
        // watering short. Raise to the safe floor if the requested value is below.
        Integer floorMin = requiredAutoOffMinForController(dev.id as String)
        Integer mins = Math.max(requested, floorMin)
        // Cross-instance heads-up: if the device already holds a larger auto-off
        // (e.g. another app instance set it higher for a longer relay on a shared
        // controller), warn before we lower it.
        Map cur = parseConfigVals(dev)
        Integer curMax = ((autoOffParams.collect { cur[it as int] }.findAll { it != null } + [0]).max()) as Integer
        try {
            // Timer unit = minutes
            unitParams.each { p -> callSetParameter(dev, style, p as int, 1, 0) }
            // Auto-off timer for each relay (size 4 — 32-bit minutes value)
            autoOffParams.each { p -> callSetParameter(dev, style, p as int, 4, mins) }
            // Power-fail state = OFF (P1) — same on all current Zooz multi-relays
            if (settings.hwPowerFailOff != false) callSetParameter(dev, style, 1, 1, 0)
            // DC-motor interlock = OFF (P24) — ZEN16 only; ZEN17 may not expose
            if (settings.hwForceDcMotorOff != false) {
                try { callSetParameter(dev, style, 24, 1, 0) } catch (ignored) {}
            }
            String note = (mins > requested) ? " — RAISED from ${requested}min (longest single cycle this instance drives here is ~${floorMin - 2}min)" : ""
            String warn = (curMax > 0 && mins < curMax) ? " — ⚠ this lowers the device's current ${curMax}min; if another instance drives a longer relay on this controller, confirm this won't cut it short" : ""
            log_ << "${dev.displayName} (${model.name}, ${style} order): sent auto-off ${mins}min to P${autoOffParams.join('/P')}${note}${warn}"
            expectedByDev[dev.id as String] = mins
            ok++
        } catch (e) {
            log_ << "${dev.displayName}: FAILED to send — ${e.message}"
            fail++
        }
    }
    state.hwExpectedMinsByDev = expectedByDev
    state.hwExpectedMins = requested
    String summary = "Sent to ${ok} controller(s)${fail ? ", ${fail} couldn't (no setParameter)" : ""} @ ${nowString()} — verifying the timers actually took; re-open this page in ~15s.\n" + log_.join("\n")
    state.hwLastPushSummary = summary
    // Per-controller values may differ once the safe-floor guard raises one;
    // report the largest actually pushed (falls back to the requested value).
    Integer reportMins = ((expectedByDev.values().collect { it as int } + [requested]).max()) as int
    log.info "${app.label}: hardware safety — sent to ${ok}, ${fail} skipped (${reportMins}min max); verifying in 15s"
    runIn(15, "verifyHardwareSafety")
    notify("hardware.push", [minutes: reportMins, count: ok])
}

// Read back the relay Auto-Off timers a few seconds after a push and report
// what the DEVICE actually holds — "sent OK" from setParameter does not mean
// the device accepted it (wrong arg order silently no-ops). This is the honest
// success check for the failsafe.
def verifyHardwareSafety() {
    def parents = settings.hwZen16Parents
    if (!parents) return
    Integer fallbackMins = (state.hwExpectedMins ?: (settings.hwAutoOffMinutes ?: (((settings.scheduleMaxRunMinutes ?: 60) as int) + 5))) as int
    Map expectedByDev = (state.hwExpectedMinsByDev ?: [:]) as Map
    List<String> lines = []
    int armed = 0, gaps = 0
    parents.each { dev ->
        Integer mins = (expectedByDev[dev.id as String] ?: fallbackMins) as int
        String modelKey = settings."hwModel_${dev.id}" ?: "3"
        Map model = (ZOOZ_RELAY_MODELS[modelKey] ?: ZOOZ_RELAY_MODELS["3"]) as Map
        List<Integer> autoOffParams = (model.autoOff ?: []) as List<Integer>
        Map actual = parseConfigVals(dev)
        List<String> vals = []
        boolean allok = true
        autoOffParams.each { p ->
            Integer v = actual[p as int]
            vals << (v == null ? "?" : "${v}")
            if (v != mins) allok = false
        }
        if (allok) { armed++; lines << "${dev.displayName}: ✓ auto-off ${vals.join('/')} min — armed" }
        else { gaps++; lines << "${dev.displayName}: ⚠ auto-off ${vals.join('/')} (want ${mins}) — did NOT take; flip the setParameter-order override above and push again" }
    }
    state.hwLastPushSummary = "Verified @ ${nowString()} — ${armed} armed, ${gaps} with gaps\n" + lines.join("\n")
    log.info "${app.label}: hardware safety verify — ${armed} armed, ${gaps} gaps"
    if (gaps > 0) notify("error", [detail: "hardware auto-off NOT set on ${gaps} relay controller(s) — open Hardware safety"])
}

// Parse the jtp10181 driver's "configVals" device data ("[1:1, 2:4, ...]")
// into a paramNum -> value map.
private Map parseConfigVals(dev) {
    Map out = [:]
    try {
        String cv = dev.getDataValue("configVals")
        if (cv) {
            java.util.regex.Matcher mm = (cv =~ /(\d+)\s*:\s*(\d+)/)
            while (mm.find()) { out[mm.group(1) as int] = mm.group(2) as int }
        }
    } catch (e) { }
    return out
}

// =========================================================================
// Per-zone exposed child Virtual Switches (manual trigger + timer)
// =========================================================================

private String zoneVsDni(int zid) { return "${app.id}-zone-${zid}" }

private getZoneChildVs(int zid) {
    return getChildDevice(zoneVsDni(zid))
}

private String zoneVsLabel(int zid) {
    String prefix = settings.zoneSwitchPrefix ?: ""
    String name   = settings."zone${zid}Name" ?: "Zone ${zid}"
    return (prefix + name).trim()
}

// Called from initialize() and after a "rebuild" button press. Creates a
// Virtual Switch child for each enabled zone; renames if labels changed;
// deletes children for disabled / removed zones; deletes all if the
// global toggle is off.
private void maintainZoneSwitches() {
    Integer n = zoneCount()
    if (!settings.zoneSwitchesEnabled) {
        // Remove every zone child VS we created
        getChildDevices().each { ch ->
            if (ch.deviceNetworkId?.startsWith("${app.id}-zone-")) {
                try { deleteChildDevice(ch.deviceNetworkId) }
                catch (e) { log.warn "remove ${ch.displayName}: ${e.message}" }
            }
        }
        return
    }
    for (int i = 1; i <= n; i++) {
        boolean shouldExist = (settings."zone${i}Enabled" != false) && settings."zone${i}Switch"
        def existing = getZoneChildVs(i)
        if (shouldExist) {
            String desiredLabel = zoneVsLabel(i)
            if (!existing) {
                try {
                    addChildDevice("hubitat", "Virtual Switch", zoneVsDni(i),
                                   [name: desiredLabel, label: desiredLabel, isComponent: false])
                    log.info "${app.label}: created zone child switch '${desiredLabel}'"
                } catch (e) {
                    log.warn "${app.label}: cannot create zone child for ${i}: ${e.message}"
                }
            } else if (existing.label != desiredLabel) {
                try { existing.setLabel(desiredLabel) } catch (e) { log.warn "rename: ${e.message}" }
            }
            // Always subscribe so external on/off lands on us
            def ch = getZoneChildVs(i)
            if (ch) {
                try { subscribe(ch, "switch", "zoneChildSwitchEvent") }
                catch (e) { log.warn "subscribe child ${i}: ${e.message}" }
            }
        } else if (existing) {
            try { deleteChildDevice(zoneVsDni(i)); log.info "${app.label}: removed zone child for ${i} (disabled or no switch)" }
            catch (e) { log.warn "remove zone child ${i}: ${e.message}" }
        }
    }
}

// Called when ANY zone child VS changes state. Distinguish app-initiated
// (scheduler turned it on/off) from external (HomeKit / dashboard).
def zoneChildSwitchEvent(evt) {
    // Hubitat events carry the device, not its network id — read the DNI off
    // the device wrapper (evt.deviceNetworkId is always null here).
    String dni = evt?.device?.deviceNetworkId ?: evt?.deviceNetworkId
    if (!dni) return
    String prefix = "${app.id}-zone-"
    if (!dni.startsWith(prefix)) return
    Integer zid = dni.substring(prefix.length()).toInteger()
    // Per-zone, time-bounded suppression. Each entry is [value, setAtMs]: we only
    // ignore an event that matches a command WE issued in the last few seconds.
    // A stale flag (e.g. left over from a previous run) can never swallow a real
    // user toggle, which is what made an off press get silently ignored before.
    Map sup = (state.suppressZoneChild ?: [:]) as Map
    long t = now()
    def entry = sup[zid.toString()]
    boolean suppressed = (entry instanceof List && entry[0] == evt.value && (t - (entry[1] as long)) < 4000)
    // Drop this zone's entry and prune any other expired ones so the map can't
    // accumulate stale flags.
    sup = sup.findAll { k, v -> k != zid.toString() && (v instanceof List) && (t - (v[1] as long)) < 4000 }
    state.suppressZoneChild = sup
    if (suppressed) return
    // During an active run the scheduler owns the relays. Don't fight per-event;
    // just reconcile every tile to its relay's real state shortly after.
    if (state.running) { runIn(2, "syncAllZoneChildren"); return }
    // External trigger while idle
    if (evt.value == "on") manualZoneStart(zid)
    else                   manualZoneStop(zid)
}

private void setZoneChildSwitch(int zid, String value) {
    def ch = getZoneChildVs(zid)
    if (!ch) return
    if (ch.currentValue("switch") == value) return  // no change needed
    Map sup = (state.suppressZoneChild ?: [:]) as Map
    sup[zid.toString()] = [value, now()]   // value + timestamp, honored only while fresh
    state.suppressZoneChild = sup
    try { if (value == "on") ch.on() else ch.off() }
    catch (e) { log.warn "setZoneChildSwitch(${zid}, ${value}): ${e.message}" }
}

// Reconcile every exposed zone tile to its relay's real on/off state.
// Idempotent and race-free — the reliable way to keep tiles correct across a
// sequential multi-zone run (and to clear finished-zone tiles at the end).
def syncAllZoneChildren() {
    if (!settings.zoneSwitchesEnabled) return
    Integer n = zoneCount()
    for (int i = 1; i <= n; i++) {
        def ch = getZoneChildVs(i)
        if (!ch) continue
        def sw = settings."zone${i}Switch"
        String desired = (sw?.currentValue("switch") == "on") ? "on" : "off"
        if (ch.currentValue("switch") != desired) setZoneChildSwitch(i, desired)
    }
}

// External "on" arrived on a zone child VS. Start the underlying relay
// for the manual timer duration; auto-off after that. Refuse if the
// scheduler is currently running its own plan.
def manualZoneStart(int zid) {
    if (state.running) {
        log.warn "${app.label}: manual zone ${zid} blocked — scheduler is running"
        notify("zone.manualBlocked", [zone: settings."zone${zid}Name" ?: "Zone ${zid}", reason: "scheduler is running"])
        setZoneChildSwitch(zid, "off")   // bounce the VS back off
        return
    }
    def sw = settings."zone${zid}Switch"
    if (!sw) {
        log.warn "${app.label}: manual zone ${zid} has no underlying switch — ignoring"
        setZoneChildSwitch(zid, "off")
        return
    }
    Integer mins = (settings."zone${zid}ManualTimerMin" ?: settings.defaultManualTimerMin ?: 10) as int
    String zname = settings."zone${zid}Name" ?: "Zone ${zid}"
    long expiresMs = now() + (mins * 60L * 1000L)
    Map active = (state.manualActive ?: [:]) as Map
    active[zid.toString()] = expiresMs
    state.manualActive = active

    log.info "${app.label}: MANUAL ▶ ${zname} for ${mins}m (via ${sw.displayName})"
    notify("zone.manualStart", [zone: zname, duration: "${mins}m", switch: sw.displayName])
    try { sw.on() } catch (e) { log.warn "manualZoneStart relay on: ${e.message}" }
    setZoneChildSwitch(zid, "on")   // reflect on the HomeKit/dashboard tile

    rescheduleManualTimers()
}

// Rebuild the per-zone auto-off timers from state.manualActive. Hubitat can't
// cancel a single runIn by data, so we clear them all and recreate one per
// still-active zone — this cancels a zone's timer the moment it's turned off
// (no stray "expired" notification) while preserving every other zone's timer.
private void rescheduleManualTimers() {
    unschedule("manualZoneTimeout")
    Map active = (state.manualActive ?: [:]) as Map
    long nowMs = now()
    active.each { k, v ->
        Long exp = v as Long
        if (exp == null) return
        Integer zid = k.toString().toInteger()
        int secs = (int) Math.max(1L, (long) ((exp - nowMs) / 1000L))
        runIn(secs, "manualZoneTimeout", [data: [zid: zid, exp: exp], overwrite: false])
    }
}

// User explicitly turned the child VS off, or scheduler stop-all hit us.
def manualZoneStop(int zid, boolean fromTimeout = false) {
    Map active = (state.manualActive ?: [:]) as Map
    if (!active.containsKey(zid.toString()) && !fromTimeout) {
        // Nothing manual was tracked; still make sure the relay's off
    }
    active.remove(zid.toString())
    state.manualActive = active
    // Cancel this zone's pending auto-off timer (kills the stray "expired"
    // notification when the user turns it off early) while keeping others.
    rescheduleManualTimers()
    def sw = settings."zone${zid}Switch"
    String zname = settings."zone${zid}Name" ?: "Zone ${zid}"
    if (sw) try { sw.off() } catch (e) { log.warn "manualZoneStop relay off: ${e.message}" }
    setZoneChildSwitch(zid, "off")
    if (fromTimeout) {
        if (descTextEnable) log.info "${app.label}: MANUAL ■ ${zname} (timer)"
        notify("zone.manualTimeout", [zone: zname])
    } else {
        if (descTextEnable) log.info "${app.label}: MANUAL ■ ${zname} (off)"
        notify("zone.manualEnd", [zone: zname])
    }
}

def manualZoneTimeout(data) {
    Integer zid = (data?.zid ?: 0) as int
    if (zid <= 0) return
    Map active = (state.manualActive ?: [:]) as Map
    Long curExp = active[zid.toString()] as Long
    // Already turned off manually → stale timer, ignore (no false "expired").
    if (curExp == null) return
    // A newer manual start replaced this timer → let the newer one fire instead.
    Long myExp = (data?.exp ?: 0L) as Long
    if (myExp && curExp != myExp) return
    manualZoneStop(zid, true)
}

// =========================================================================
// Dashboard child device (virtual switch with extra attributes)
// =========================================================================

private String dashboardDni() { return "${app.id}-tile" }

private getDashboardChild() {
    return getChildDevice(dashboardDni())
}

private void maintainDashboardChild() {
    def existing = getDashboardChild()
    if (settings.dashboardEnabled) {
        if (!existing) {
            try {
                String label = settings.dashboardLabel ?: "${app.label} Tile"
                addChildDevice("hubitat", "Virtual Switch", dashboardDni(),
                               [name: label, label: label, isComponent: false])
                log.info "${app.label}: created dashboard child device '${label}'"
            } catch (e) {
                log.warn "${app.label}: cannot create dashboard child — ${e.message}"
            }
        } else if (settings.dashboardLabel && existing.label != settings.dashboardLabel) {
            existing.setLabel(settings.dashboardLabel)
        }
        publishDashboardState()
    } else if (existing) {
        try { deleteChildDevice(dashboardDni()); log.info "${app.label}: removed dashboard child" }
        catch (e) { log.warn "remove dashboard child: ${e.message}" }
    }
}

def publishDashboardState() {
    def ch = getDashboardChild()
    if (!ch) return
    String swState = state.running ? "on" : "off"
    if (ch.currentValue("switch") != swState) {
        if (swState == "on") ch.on() else ch.off()
    }
    String nextRun = nextScheduledRunString()
    Integer zid = (state.currentZoneId ?: 0) as int
    String curZone = (zid > 0) ? (settings."zone${zid}Name" ?: "Zone ${zid}") : ""
    String lastFin = ""
    List h = (state.runHistory ?: []) as List
    if (h) lastFin = "${h[-1].finishedAt ?: h[-1].startedAt} — ${h[-1].outcome}"
    [   currentZone:  curZone,
        nextRun:      nextRun,
        lastFinish:   lastFin,
        seasonalMult: state.seasonalMult ?: "1.0",
        paused:       state.paused ? "yes" : "no",
        runStatus:    runStatusString()
    ].each { k, v ->
        try {
            ch.sendEvent(name: k, value: v as String, displayed: false)
        } catch (ignored) {}
    }
}

// =========================================================================
// "Run schedule" control switch — on-demand start/stop from HomeKit etc.
// =========================================================================

private String runCtlDni() { return "${app.id}-runctl" }
private getRunCtlChild()   { return getChildDevice(runCtlDni()) }

// Create / rename / remove the control switch device. Safe to call during page
// render (no event subscription here), so enabling the toggle creates it
// immediately instead of waiting for Done.
private void ensureRunSwitchDevice() {
    def existing = getRunCtlChild()
    if (settings.runSwitchEnabled) {
        String label = settings.runSwitchLabel ?: "${app.label} Run"
        if (!existing) {
            try {
                addChildDevice("hubitat", "Virtual Switch", runCtlDni(),
                               [name: label, label: label, isComponent: false])
                log.info "${app.label}: created run-schedule control switch '${label}'"
            } catch (e) {
                log.warn "${app.label}: cannot create run-schedule switch — ${e.message}"
            }
        } else if (existing.label != label) {
            try { existing.setLabel(label) } catch (e) { log.warn "rename run switch: ${e.message}" }
        }
        // Wire up exactly one event subscription. Unsubscribe-then-subscribe
        // keeps it to a single handler whether this runs from a page render or
        // from initialize(), so the switch works even before the next Done.
        def ch = getRunCtlChild()
        if (ch) {
            try { unsubscribe(ch); subscribe(ch, "switch", "runControlSwitchEvent") }
            catch (e) { log.warn "subscribe run switch: ${e.message}" }
        }
    } else if (existing) {
        try { unsubscribe(existing) } catch (ignored) {}
        try { deleteChildDevice(runCtlDni()); log.info "${app.label}: removed run-schedule control switch" }
        catch (e) { log.warn "remove run switch: ${e.message}" }
    }
}

// Full maintenance for initialize(): ensure the device exists + is subscribed,
// then reflect current running state onto it.
private void maintainRunSwitch() {
    ensureRunSwitchDevice()
    if (settings.runSwitchEnabled) syncRunControlSwitch()
}

// External on/off on the control switch → start (manual) / stop the schedule.
def runControlSwitchEvent(evt) {
    String dni = evt?.device?.deviceNetworkId ?: evt?.deviceNetworkId
    if (dni != runCtlDni()) return
    String guard = "run:${evt.value}"
    if (state.suppressRunCtlEvent == guard) { state.suppressRunCtlEvent = null; return }
    if (evt.value == "on") {
        if (state.running) return   // already running — nothing to do
        log.info "${app.label}: Run switch ON — starting schedule on demand"
        runIn(1, "runSchedule", [data: [manual: true]])
        // Reconcile the switch shortly after: if the run was skipped bounce it
        // back off so it stays honest, and log why so it's visible.
        runIn(8, "runSwitchReconcile")
    } else {
        log.info "${app.label}: Run switch OFF — stopping schedule on demand"
        stopAllZones()
        syncRunControlSwitch()
    }
}

// Run a few seconds after an on-demand start: keep the switch honest, and if no
// run began, say why in the app log so it's diagnosable at a glance.
def runSwitchReconcile() {
    syncRunControlSwitch()
    if (state.running) return
    if (state.deferredRunPending) {
        // Not a failure — the run is held by a pause sensor and will auto-start.
        log.info "${app.label}: on-demand run is HELD — waiting for ${externalPauseReason()} to clear, then it will start automatically"
        return
    }
    List<String> blockers = []
    if (externalPauseActive()) blockers << "a pause sensor is active (${externalPauseReason()})"
    if (rainSensorWet())       blockers << "a rain sensor reads wet (${rainSensorReason()})"
    if (modeShouldPause())     blockers << "Hubitat mode is ${location?.mode}"
    if (hsmShouldPause())      blockers << "HSM is ${location?.hsmStatus}"
    int n = (settings.zoneCountPref ?: 0) as int
    int withSw = 0
    for (int i = 1; i <= n; i++) if ((settings."zone${i}Enabled" != false) && settings."zone${i}Switch") withSw++
    if (withSw == 0) blockers << "no enabled zones have a relay assigned"
    String why = blockers ? blockers.join("; ") : "unknown — see the run log above"
    log.warn "${app.label}: on-demand run did not start — ${why}"
}

// Reflect the schedule's running state on the control switch (no event echo).
def syncRunControlSwitch() {
    def ch = getRunCtlChild()
    if (!ch) return
    String value = state.running ? "on" : "off"
    if (ch.currentValue("switch") == value) return
    state.suppressRunCtlEvent = "run:${value}"
    try { if (value == "on") ch.on() else ch.off() }
    catch (e) { log.warn "syncRunControlSwitch: ${e.message}" }
}

// =========================================================================
// Diagnostics & test runs
// =========================================================================

def testZoneRun(Integer zid) {
    def sw = settings."zone${zid}Switch"
    if (!sw) {
        log.warn "${app.label}: testZoneRun(${zid}) — no switch configured"
        return
    }
    Integer secs = Math.max(5, Math.min(600, (settings.testRunSeconds ?: 30) as int))
    String label = settings."zone${zid}Name" ?: "Zone ${zid}"
    log.info "${app.label}: TEST ▶ ${label} for ${secs}s"
    notify("test.run", [zone: label, duration: fmtDuration(secs as int)])
    state.testRunningZid = zid
    try {
        sw.on()
    } catch (e) {
        log.warn "test on failed: ${e.message}"
        return
    }
    runIn(secs, "testZoneRunStop")
}

def testZoneRunStop() {
    Integer zid = (state.testRunningZid ?: 0) as int
    if (zid <= 0) return
    def sw = settings."zone${zid}Switch"
    if (sw) try { sw.off() } catch (e) { log.warn "test off: ${e.message}" }
    String label = settings."zone${zid}Name" ?: "Zone ${zid}"
    if (descTextEnable) log.info "${app.label}: TEST ■ ${label}"
    state.testRunningZid = 0
}

def testAllZones() {
    log.info "${app.label}: TEST ALL — running each zone for ${settings.testRunSeconds ?: 30}s"
    state.testAllQueue = []
    Integer n = zoneCount()
    for (int i = 1; i <= n; i++) {
        if (settings."zone${i}Switch") state.testAllQueue << i
    }
    if (state.testAllQueue) advanceTestAll()
}

def advanceTestAll() {
    List q = state.testAllQueue as List
    if (!q) return
    Integer next = q.remove(0) as int
    state.testAllQueue = q
    Integer secs = Math.max(5, Math.min(600, (settings.testRunSeconds ?: 30) as int))
    testZoneRun(next)
    runIn(secs + 5, "advanceTestAll")
}

// =========================================================================
// Health check & validation
// =========================================================================

private String healthCheckReport() {
    List<String> ok   = []
    List<String> warn = []
    Integer n = zoneCount()
    if (n == 0) warn << "No zones configured — set up at least one on the Zones page."
    // Duplicate switch detection
    Map<String, List<Integer>> bySwitch = [:]
    for (int i = 1; i <= n; i++) {
        def sw = settings."zone${i}Switch"
        if (sw) bySwitch.computeIfAbsent(sw.id.toString(), { _ -> [] }) << i
    }
    bySwitch.each { swId, zones ->
        if (zones.size() > 1) warn << "Switch shared by zones ${zones.join(', ')} — same relay driving multiple zones is usually a config error."
    }
    // Per-zone checks
    Integer maxRun = (settings.scheduleMaxRunMinutes ?: 60) as int
    for (int i = 1; i <= n; i++) {
        def sw = settings."zone${i}Switch"
        Integer rt = (settings."zone${i}RunMinutes" ?: 0) as int
        String label = settings."zone${i}Name" ?: "Zone ${i}"
        if (!sw) {
            warn << "${label}: no Switch device assigned."
        } else {
            String cur = sw.currentValue("switch") ?: "unknown"
            ok << "${label} → ${sw.displayName} (currently ${cur})"
        }
        if (rt > maxRun) {
            warn << "${label}: runtime ${rt}min exceeds schedule max ${maxRun}min — will be clipped."
        }
    }
    // Location lat/lon for weather
    if (settings.rainDelayEnabled || settings.seasonalEnabled) {
        if (!location?.latitude || !location?.longitude) {
            warn << "Weather is enabled but Hubitat location latitude/longitude is not set (Settings → Location)."
        }
    }
    // Hardware safety reminder
    if (!settings.hwZen16Parents) {
        warn << "No ZEN16 parents picked on the Hardware safety page — hub-independent watchdog is OFF. Recommended."
    }
    // Skip-next / forced delay
    if (state.skipNextRun) warn << "Skip-next is armed: next scheduled run will be skipped."
    Long rd = (state.forcedRainDelayUntilMs ?: 0L) as long
    if (rd > now()) {
        String until = new Date(rd).format("yyyy-MM-dd HH:mm", location?.timeZone ?: TimeZone.getDefault())
        warn << "Forced rain delay active until ${until}."
    }

    StringBuilder sb = new StringBuilder()
    if (warn) {
        sb << "<b>⚠ Warnings (${warn.size()})</b><ul>"
        warn.each { sb << "<li>${it}</li>" }
        sb << "</ul>"
    } else {
        sb << "<b>✓ No warnings.</b><br>"
    }
    if (ok) {
        sb << "<b>Zones</b><ul style='font-size:0.9em'>"
        ok.each { sb << "<li>${it}</li>" }
        sb << "</ul>"
    }
    state.lastHealthCheck = nowString()
    return sb.toString()
}

private String stateDumpString() {
    List rows = []
    rows << "running=${state.running}, paused=${state.paused}"
    rows << "currentZoneId=${state.currentZoneId}, idx=${state.currentZoneIdx}, planSize=${(state.zonesPlan ?: []).size()}"
    rows << "seasonalMult=${state.seasonalMult}, skipNextRun=${state.skipNextRun}"
    Long rd = (state.forcedRainDelayUntilMs ?: 0L) as long
    rows << "forcedRainDelayUntilMs=${rd > 0 ? new Date(rd) : 'none'}"
    rows << "weatherCacheAge=${state.__omCacheAt ? ((now() - (state.__omCacheAt as long)) / 1000) + 's' : 'cold'}"
    rows << "lastHealthCheck=${state.lastHealthCheck ?: 'never'}"
    return "<pre style='font-size:0.85em'>${rows.join('\n')}</pre>"
}

private String nextScheduledRunString() {
    if (!settings.scheduleEnabled || !settings.scheduleStartTime || (!isIntervalMode() && !settings.scheduleDays)) return "schedule disabled"
    // Hubitat schedule list isn't directly readable from app context; just show
    // configured days + first window. Good enough for the dashboard.
    String t = settings.scheduleStartTime
    if (t?.contains("T")) t = t.tokenize("T")[1].substring(0,5)
    if (isIntervalMode()) return "every ${settings.scheduleIntervalDays ?: 2} day(s)  @ ${t}"
    return "${(settings.scheduleDays as List).join(',')}  @ ${t}"
}

// =========================================================================
// Smart-skip: frost / cold / wind via Open-Meteo
// =========================================================================

private Map smartSkipCheck() {
    boolean wantFrost = settings.smartSkipFrostF != null
    boolean wantCold  = settings.smartSkipColdHighF != null
    boolean wantWind  = settings.smartSkipWindMph != null
    if (!(wantFrost || wantCold || wantWind)) return [skip: false]
    Map om = fetchWeather()
    if (!om?.daily?.time) return [skip: false]
    String todayISO = new Date().format("yyyy-MM-dd", location?.timeZone ?: TimeZone.getDefault())
    int idx = (om.daily.time as List).findIndexOf { it == todayISO }
    if (idx < 0) idx = (om.daily.time.size() > 1) ? 1 : 0
    Number low  = (om.daily.temperature_2m_min ?: [])[idx]
    Number high = (om.daily.temperature_2m_max ?: [])[idx]
    Number wind = (om.daily.wind_speed_10m_max ?: [])[idx]
    if (wantFrost && low != null && (low as float) < (settings.smartSkipFrostF as float)) {
        return [skip: true, reason: "frost: low ${low}${tUnit()} < ${settings.smartSkipFrostF}${tUnit()}",
                eventKey: "skip.frost", ctx: [tempF: low, threshold: settings.smartSkipFrostF, tunit: tUnit()]]
    }
    if (wantCold && high != null && (high as float) < (settings.smartSkipColdHighF as float)) {
        return [skip: true, reason: "cold: high ${high}${tUnit()} < ${settings.smartSkipColdHighF}${tUnit()}",
                eventKey: "skip.cold", ctx: [tempF: high, threshold: settings.smartSkipColdHighF, tunit: tUnit()]]
    }
    if (wantWind && wind != null && (wind as float) > (settings.smartSkipWindMph as float)) {
        return [skip: true, reason: "wind: max ${wind} ${wUnit()} > ${settings.smartSkipWindMph} ${wUnit()}",
                eventKey: "skip.wind", ctx: [windMph: wind, threshold: settings.smartSkipWindMph, wunit: wUnit()]]
    }
    return [skip: false]
}

// =========================================================================
// Moisture-aware watering (skip / early-stop / adapt / learn)
// =========================================================================

private Float readZoneMoisture(int zid) {
    def dev = settings."zone${zid}MoistureSensor"
    if (!dev) return null
    String attr = settings."zone${zid}MoistureAttribute" ?: "humidity"
    def v = dev.currentValue(attr) ?: dev.currentValue(attr == "humidity" ? "moisture" : "humidity")
    return v == null ? null : (v as float)
}

// Mid-run moisture-sensor event. Subscribed only while a zone is running.
// On reaching target, stop the running cycle and advance.
def moistureEvent(evt) {
    if (!state.running) return
    Integer zid = (state.currentZoneId ?: 0) as int
    if (zid <= 0) return
    def expectedSensor = settings."zone${zid}MoistureSensor"
    if (!expectedSensor || evt?.deviceId != expectedSensor.id) return
    String mode = settings."zone${zid}MoistureMode" ?: "off"
    if (!(mode in ["earlyStop", "adapt", "learn"])) return
    Float cur
    try { cur = (evt.value as float) } catch (e) { return }
    Integer target = (settings."zone${zid}MoistureTarget" ?: 50) as int
    if (cur < target) return
    // Reached target — cut this cycle short
    String zname = settings."zone${zid}Name" ?: "Zone ${zid}"
    Long startMs = (state.currentPhaseStartMs ?: now()) as long
    Integer elapsedSec = ((now() - startMs) / 1000L) as int
    String dur = fmtDuration(elapsedSec)
    log.info "${app.label}: ${zname} early-stop — soil at ${cur}% (target ${target}%) after ${dur}"
    notify("moisture.earlyStop", [zone: zname, moisture: cur, target: target, duration: dur])
    // Mark this cycle as the last cycle so zoneCyclePhaseDone advances out.
    state.currentZoneCycleIdx = (state.currentZoneCycles ?: 1) - 1
    unschedule("zoneCyclePhaseDone")
    runIn(1, "zoneCyclePhaseDone")
}

private void unsubscribeZoneMoisture(int zid) {
    def dev = settings."zone${zid}MoistureSensor"
    if (dev) try { unsubscribe(dev) } catch (ignored) {}
}

// Adaptive multiplier: dryness in [0..2]
//   moisture <= moistureMin   → 2.0× baseline (very dry)
//   moisture == moistureTarget → 0.0× (already wet — skip handled upstream)
//   between                   → linear
private BigDecimal computeMoistureMultiplier(int zid, float current, int baseMin) {
    Integer target = (settings."zone${zid}MoistureTarget" ?: 50) as int
    Integer minPct = (settings."zone${zid}MoistureMin" ?: 20) as int
    if (target <= minPct) return 1.0  // misconfigured — no scaling
    if (current >= target) return 0.0
    float dryness = (target - current) / (float)(target - minPct)
    if (dryness < 0) dryness = 0
    if (dryness > 2) dryness = 2
    // "learn" mode further refines with learned %/min rate
    if ((settings."zone${zid}MoistureMode" ?: "") == "learn") {
        Float rate = learnedRate(zid)
        if (rate != null && rate > 0.0) {
            // Predict minutes to lift moisture from current to target
            float needed = (target - current) / rate
            if (needed > 0) {
                // Express prediction as a multiplier on baseline
                return new BigDecimal((needed / (float) baseMin).toString())
                       .setScale(2, BigDecimal.ROUND_HALF_UP)
            }
        }
    }
    return new BigDecimal((dryness as String)).setScale(2, BigDecimal.ROUND_HALF_UP)
}

// Capture a learning record. state.moistureLearning[zid] is a list of maps,
// capped at 20 entries per zone.
private void recordZoneLearning(int zid, Float pre, Float post, int runMin) {
    if (pre == null || post == null || runMin <= 0) return
    float delta = post - pre
    if (delta < 0) delta = 0  // skip evaporation cases
    float ratePerMin = delta / (float) runMin
    Map all = (state.moistureLearning ?: [:]) as Map
    String k = zid.toString()
    List recs = (all[k] ?: []) as List
    recs << [ts: nowString(), pre: pre, post: post, runMin: runMin,
             delta: delta.round(1), rate: ratePerMin.round(3)]
    while (recs.size() > 20) recs.remove(0)
    all[k] = recs
    state.moistureLearning = all
    String zname = settings."zone${zid}Name" ?: "Zone ${zid}"
    notify("moisture.learned", [zone: zname, delta: ratePerMin.round(3), runMin: runMin])
}

private Float learnedRate(int zid) {
    List recs = ((state.moistureLearning ?: [:])[zid.toString()] ?: []) as List
    if (!recs) return null
    // Weighted average — newest entries weigh more
    float sum = 0; float weight = 0
    recs.eachWithIndex { rec, i ->
        float w = (i + 1) as float  // older = lower weight
        sum += (rec.rate as float) * w
        weight += w
    }
    if (weight == 0) return null
    return (sum / weight) as Float
}

private String zoneAdaptiveStatusString(int zid) {
    Float cur = readZoneMoisture(zid)
    Integer target = (settings."zone${zid}MoistureTarget" ?: 50) as int
    Integer minPct = (settings."zone${zid}MoistureMin" ?: 20) as int
    if (cur == null) return "no reading"
    if (cur >= target) return "wet — would SKIP"
    int baseMin = resolveZoneBaseMinutes(zid)
    BigDecimal m = computeMoistureMultiplier(zid, cur, baseMin)
    Float rate = learnedRate(zid)
    String rateStr = rate ? ", learned rate ${rate}%/min" : ""
    return "dryness ${m}× (target ${target}%, min ${minPct}%${rateStr})"
}

// =========================================================================
// Runtime calc (fixed minutes vs. Spruce-style weekly) + weekly budget
// =========================================================================

private Integer resolveZoneBaseMinutes(int zid) {
    String mode = (settings."zone${zid}RuntimeMode" ?: "fixed") as String
    if (mode == "weekly") {
        Integer weeklyMin = (settings."zone${zid}WeeklyMinutes" ?: 30) as int
        Integer dpw       = Math.max(1, (settings."zone${zid}DaysPerWeek" ?: 3) as int)
        return Math.max(1, Math.round(weeklyMin / (float) dpw) as int)
    }
    return (settings."zone${zid}RunMinutes" ?: 10) as int
}

private String zoneEffectiveBaseString(int zid) {
    Integer baseMin = resolveZoneBaseMinutes(zid)
    return "${baseMin} min"
}

private void consumeWeeklyBudget(int zid, int minutes) {
    if (minutes <= 0) return
    Map m = (state.zoneMinutesThisWeek ?: [:]) as Map
    String k = zid.toString()
    m[k] = ((m[k] ?: 0) as int) + minutes
    state.zoneMinutesThisWeek = m
}

def rolloverWeeklyBudget() {
    state.zoneMinutesThisWeek = [:]
    state.weekStartIso = currentWeekIso()
    if (descTextEnable) log.info "${app.label}: weekly budget reset (week ${state.weekStartIso})"
}

private void rolloverWeeklyBudgetIfNeeded() {
    String cur = currentWeekIso()
    if (state.weekStartIso != cur) {
        rolloverWeeklyBudget()
    }
}

private String currentWeekIso() {
    Calendar c = Calendar.getInstance(location?.timeZone ?: TimeZone.getDefault())
    c.setFirstDayOfWeek(Calendar.MONDAY)
    c.setMinimalDaysInFirstWeek(4) // ISO
    int year = c.get(Calendar.YEAR)
    int week = c.get(Calendar.WEEK_OF_YEAR)
    return String.format("%04d-W%02d", year, week)
}

// =========================================================================
// Quiet hours / mode / HSM gating
// =========================================================================

private boolean quietHoursActive() {
    if (!settings.quietHoursEnabled) return false
    if (!settings.quietStartTime || !settings.quietEndTime) return false
    return timeOfDayIsBetween(
        toDateTime(settings.quietStartTime),
        toDateTime(settings.quietEndTime),
        new Date(),
        location?.timeZone
    )
}

private boolean modeShouldPause() {
    def modes = settings.pauseModes as List
    if (!modes) return false
    return modes.any { it == location?.mode }
}

private boolean hsmShouldPause() {
    String hsm = location?.hsmStatus
    if (!hsm) return false
    if (settings.hsmPauseEnabled) {
        if (hsm in ["intrusion", "intrusion-home", "intrusion-night", "smoke", "water", "rules"]) return true
        if (hsm?.endsWith("Alert")) return true
    }
    if (settings.hsmPauseArmedAway && hsm == "armedAway") return true
    return false
}

// Build a cron expression that fires `leadMin` minutes before `timeStr` on
// the configured days. Returns null if subtraction crosses midnight (we
// don't support that edge case for simplicity).
private String buildLeadCron(String timeStr, List daysList, int leadMin) {
    Date t = timeToday(timeStr, location?.timeZone)
    Calendar c = Calendar.getInstance(location?.timeZone ?: TimeZone.getDefault())
    c.setTime(t)
    c.add(Calendar.MINUTE, -leadMin)
    int hour = c.get(Calendar.HOUR_OF_DAY)
    int min  = c.get(Calendar.MINUTE)
    Map<String, Integer> mapDow = [SUN:1, MON:2, TUE:3, WED:4, THU:5, FRI:6, SAT:7]
    List<Integer> dowNums = daysList.collect { mapDow[it] }.findAll { it != null }
    String dowStr = dowNums.sort().join(",")
    return "0 ${min} ${hour} ? * ${dowStr}"
}

private String buildDailyCron(String timeStr) {
    Date t = timeToday(timeStr, location?.timeZone)
    Calendar c = Calendar.getInstance(location?.timeZone ?: TimeZone.getDefault())
    c.setTime(t)
    return "0 ${c.get(Calendar.MINUTE)} ${c.get(Calendar.HOUR_OF_DAY)} * * ?"
}

// =========================================================================
// 7-day schedule preview
// =========================================================================

private String previewNextSevenDaysHtml() {
    if (!settings.scheduleEnabled || !settings.scheduleStartTime || (!isIntervalMode() && !settings.scheduleDays)) {
        return "<i>Schedule disabled — nothing to preview.</i>"
    }
    Map<Integer, String> dowName = [1:"Sun",2:"Mon",3:"Tue",4:"Wed",5:"Thu",6:"Fri",7:"Sat"]
    Map<String, Integer> dowNum  = [SUN:1, MON:2, TUE:3, WED:4, THU:5, FRI:6, SAT:7]
    Set<Integer> wantDow = ((settings.scheduleDays ?: []) as List).collect { dowNum[it] } as Set
    Set<Integer> wantDow0 = wantDow.findAll { it != null } as Set

    List<String> windows = ["scheduleStartTime", "scheduleStartTime2", "scheduleStartTime3"]
        .collect { settings[it] }
        .findAll { it != null }
        .collect { timeFmt(it) }

    Long skipUntilMs = (state.forcedRainDelayUntilMs ?: 0L) as long
    boolean skipNextArmed = state.skipNextRun
    Calendar c = Calendar.getInstance(location?.timeZone ?: TimeZone.getDefault())
    StringBuilder sb = new StringBuilder("<table style='width:100%;font-family:monospace;font-size:0.9em'>")
    sb << "<tr><th align='left'>Date</th><th align='left'>Day</th><th align='left'>Windows</th><th align='left'>Status</th></tr>"
    for (int d = 0; d < 7; d++) {
        Date day = c.getTime()
        int dow = c.get(Calendar.DAY_OF_WEEK)
        String dateStr = day.format("yyyy-MM-dd", location?.timeZone ?: TimeZone.getDefault())
        boolean runsToday = isIntervalMode() ? isIntervalRunDayMs(day.getTime()) : wantDow0.contains(dow)
        String windowsStr = runsToday ? windows.join(", ") : "—"
        List<String> blockers = []
        if (!runsToday) blockers << (isIntervalMode() ? "off-cycle day" : "not a watering day")
        if (d == 0 && skipNextArmed) blockers << "next-run will be skipped"
        if (skipUntilMs > day.getTime() && skipUntilMs > now()) {
            String until = new Date(skipUntilMs).format("yyyy-MM-dd HH:mm", location?.timeZone ?: TimeZone.getDefault())
            blockers << "blocked by forced rain delay until ${until}"
        }
        if (settings.quietHoursEnabled && windows && windowsStr != "—") {
            // Cheap quiet-hours hint: just remind that quiet hours apply.
            blockers << "quiet hours apply"
        }
        String status = blockers.isEmpty() ? (runsToday ? "✓ will run" : "—") : "⚠ ${blockers.join('; ')}"
        sb << "<tr><td>${dateStr}</td><td>${dowName[dow]}</td><td>${windowsStr}</td><td>${status}</td></tr>"
        c.add(Calendar.DAY_OF_MONTH, 1)
    }
    sb << "</table>"
    return sb.toString()
}

private String timeFmt(String iso) {
    if (!iso) return ""
    if (iso.contains("T")) return iso.tokenize("T")[1].substring(0,5)
    return iso
}

// =========================================================================
// External API endpoints (OAuth)
// =========================================================================

def apiStatus() {
    Map body = [
        appLabel:        app.label,
        version:         getAppVersion(),
        status:          runStatusString(),
        running:         state.running ?: false,
        paused:          state.paused ?: false,
        currentZoneId:   state.currentZoneId ?: 0,
        currentZoneName: state.currentZoneId ? (settings."zone${state.currentZoneId}Name" ?: "Zone ${state.currentZoneId}") : null,
        zoneCount:       zoneCount(),
        nextRunHint:     nextScheduledRunString(),
        seasonalMult:    state.seasonalMult ?: "1.0",
        skipNextArmed:   (state.skipNextRun ?: false),
        forcedRainDelayUntil: (((state.forcedRainDelayUntilMs ?: 0L) as long) > now())
                              ? new Date(((state.forcedRainDelayUntilMs ?: 0L) as long)).format("yyyy-MM-dd HH:mm")
                              : null,
        quietHoursActive: quietHoursActive(),
        modeShouldPause:  modeShouldPause(),
        hsmShouldPause:   hsmShouldPause(),
        rainSensorWet:    rainSensorWet(),
        externalPause:    externalPauseActive(),
        recentRuns:       (state.runHistory ?: [])[-5..-1] ?: []
    ]
    render(contentType: "application/json", data: groovy.json.JsonOutput.toJson(body))
}

def apiRun() {
    log.info "${app.label}: API run requested"
    runIn(1, "runSchedule")
    render(contentType: "application/json", data: '{"ok":true,"action":"run"}')
}

def apiStop() {
    log.info "${app.label}: API stop requested"
    stopAllZones()
    render(contentType: "application/json", data: '{"ok":true,"action":"stop"}')
}

def apiSkip() {
    log.info "${app.label}: API skip requested"
    skipNext()
    render(contentType: "application/json", data: '{"ok":true,"action":"skip-next-armed"}')
}

def apiDelay() {
    int hours = (params?.hours ?: 24) as int
    if (hours <= 0) {
        clearForcedDelays()
        render(contentType: "application/json", data: '{"ok":true,"action":"cleared"}')
        return
    }
    forceRainDelayHours(hours)
    render(contentType: "application/json",
           data: groovy.json.JsonOutput.toJson([ok:true, action:"rain-delay", hours:hours]))
}

def apiDashboard() {
    render(contentType: "text/html", data: renderDashboardHtml())
}

def apiCalendar() {
    render(contentType: "text/calendar", data: renderCalendarIcs())
}

private String renderDashboardHtml() {
    String css = """
    body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;margin:0;background:#f5f5f7;color:#222}
    header{background:#1a73e8;color:white;padding:12px 16px}
    header h1{margin:0;font-size:1.2em}
    section{background:white;margin:10px;padding:12px;border-radius:6px;box-shadow:0 1px 3px rgba(0,0,0,.07)}
    section h2{margin:0 0 8px;font-size:1em;color:#555;text-transform:uppercase;letter-spacing:.5px}
    .status-banner{padding:10px;border-radius:6px;font-weight:bold;text-align:center}
    .banner-running{background:#e8f5e9;color:#2e7d32}
    .banner-paused {background:#fff8e1;color:#f57c00}
    .banner-idle   {background:#e3f2fd;color:#1565c0}
    .banner-blocked{background:#ffebee;color:#c62828}
    table{width:100%;border-collapse:collapse;font-size:.9em}
    th,td{padding:6px;text-align:left;border-bottom:1px solid #eee}
    th{background:#fafafa;font-weight:600;color:#555}
    .kv{display:grid;grid-template-columns:max-content 1fr;column-gap:12px;row-gap:4px}
    .kv b{color:#666;font-weight:500}
    footer{text-align:center;color:#888;font-size:.85em;padding:12px}
    """
    String banner, bClass
    if (state.running) { banner = "▶ RUNNING — ${runStatusString()}"; bClass = "banner-running" }
    else if (state.paused) { banner = "⏸ PAUSED — ${state.pausedReason ?: ''}"; bClass = "banner-paused" }
    else if (state.skipNextRun || ((state.forcedRainDelayUntilMs ?: 0L) as long) > now()) { banner = "⏸ BLOCKED — ${forcedDelayDisplayString()}"; bClass = "banner-blocked" }
    else { banner = "○ idle"; bClass = "banner-idle" }

    StringBuilder sb = new StringBuilder()
    sb << "<!DOCTYPE html><html><head>"
    sb << "<meta charset='utf-8'><meta http-equiv='refresh' content='30'>"
    sb << "<meta name='viewport' content='width=device-width,initial-scale=1'>"
    sb << "<title>${app.label}</title><style>${css}</style></head><body>"
    sb << "<header><h1>${app.label}</h1></header>"

    // Status banner
    sb << "<section><div class='status-banner ${bClass}'>${banner}</div></section>"

    // Key facts
    sb << "<section><h2>Status</h2><div class='kv'>"
    [ "Zones configured": zoneCount(),
      "Next scheduled":   nextScheduledRunString(),
      "Seasonal mult":    state.seasonalMult ?: "1.0",
      "Skip next":        state.skipNextRun ? "ARMED" : "no",
      "Forced rain delay": ((state.forcedRainDelayUntilMs ?: 0L) as long) > now()
                           ? new Date((state.forcedRainDelayUntilMs ?: 0L) as long).format("yyyy-MM-dd HH:mm")
                           : "—",
      "Quiet hours now":  quietHoursActive(),
      "Mode pause now":   modeShouldPause(),
      "HSM pause now":    hsmShouldPause(),
      "Rain sensor wet":  rainSensorWet(),
      "External pause":   externalPauseActive()
    ].each { k, v -> sb << "<b>${k}</b><span>${v}</span>" }
    sb << "</div></section>"

    // Zones
    sb << "<section><h2>Zones</h2><table><tr><th>#</th><th>Name</th><th>Switch</th><th>State</th><th>Last run</th><th>Wk min</th></tr>"
    Integer n = zoneCount()
    Map weekMin = (state.zoneMinutesThisWeek ?: [:]) as Map
    for (int i = 1; i <= n; i++) {
        String label = settings."zone${i}Name" ?: "Zone ${i}"
        def sw = settings."zone${i}Switch"
        String swLabel = sw ? sw.displayName : "—"
        String swState = sw ? (sw.currentValue('switch') ?: '?') : "—"
        String last   = (state.lastRunByZone ?: [:])[i.toString()] ?: "never"
        String used   = "${weekMin[i.toString()] ?: 0}m"
        sb << "<tr><td>${i}</td><td>${label}</td><td>${swLabel}</td><td>${swState}</td><td>${last}</td><td>${used}</td></tr>"
    }
    sb << "</table></section>"

    // Recent runs
    List h = (state.runHistory ?: []) as List
    if (h) {
        sb << "<section><h2>Last 10 runs</h2><table><tr><th>When</th><th>Outcome</th></tr>"
        h.reverse()[0..Math.min(9, h.size() - 1)].each { r ->
            sb << "<tr><td>${r.startedAt}</td><td>${r.outcome}</td></tr>"
        }
        sb << "</table></section>"
    }

    sb << "<footer>${getAppVersion()} · auto-refresh 30s</footer></body></html>"
    return sb.toString()
}

private String renderCalendarIcs() {
    StringBuilder sb = new StringBuilder()
    sb << "BEGIN:VCALENDAR\r\n"
    sb << "VERSION:2.0\r\n"
    sb << "PRODID:-//Zooz Sprinkler Scheduler//${getAppVersion()}//EN\r\n"
    sb << "CALSCALE:GREGORIAN\r\n"
    sb << "X-WR-CALNAME:${escapeIcs(app.label)}\r\n"
    if (!settings.scheduleEnabled || !settings.scheduleStartTime || (!isIntervalMode() && !settings.scheduleDays)) {
        sb << "END:VCALENDAR\r\n"
        return sb.toString()
    }
    Map<String, Integer> dowNum = [SUN:1, MON:2, TUE:3, WED:4, THU:5, FRI:6, SAT:7]
    Set<Integer> wantDow = ((settings.scheduleDays ?: []) as List).collect { dowNum[it] }.findAll { it != null } as Set
    List<String> windows = ["scheduleStartTime", "scheduleStartTime2", "scheduleStartTime3"]
        .collect { settings[it] }.findAll { it != null }

    // Estimate total minutes per run (sum of base runtimes)
    Integer estimateMin = 0
    Integer n = zoneCount()
    for (int i = 1; i <= n; i++) {
        if (settings."zone${i}Enabled" != false && settings."zone${i}Switch") {
            estimateMin += resolveZoneBaseMinutes(i) + ((settings.scheduleBetweenZoneSec ?: 10) as int) / 60
        }
    }
    if (estimateMin < 1) estimateMin = 15

    Calendar c = Calendar.getInstance(location?.timeZone ?: TimeZone.getDefault())
    for (int d = 0; d < 30; d++) {
        if (isIntervalMode() ? isIntervalRunDayMs(c.getTimeInMillis()) : wantDow.contains(c.get(Calendar.DAY_OF_WEEK))) {
            String dateStr = c.getTime().format("yyyyMMdd", location?.timeZone ?: TimeZone.getDefault())
            windows.each { String iso ->
                String hhmm = timeFmt(iso)
                if (hhmm) {
                    String dtStart = "${dateStr}T${hhmm.replace(':', '')}00"
                    Calendar end = (Calendar) c.clone()
                    String[] hm = hhmm.tokenize(':')
                    end.set(Calendar.HOUR_OF_DAY, hm[0].toInteger())
                    end.set(Calendar.MINUTE, hm[1].toInteger())
                    end.set(Calendar.SECOND, 0)
                    end.add(Calendar.MINUTE, estimateMin)
                    String endStr = end.getTime().format("yyyyMMdd'T'HHmmss", location?.timeZone ?: TimeZone.getDefault())
                    String uid = "${app.id}-${dateStr}-${hhmm.replace(':', '')}@zooz-sprinkler"
                    sb << "BEGIN:VEVENT\r\n"
                    sb << "UID:${uid}\r\n"
                    sb << "DTSTAMP:${new Date().format("yyyyMMdd'T'HHmmss'Z'", TimeZone.getTimeZone('UTC'))}\r\n"
                    sb << "DTSTART;TZID=${location?.timeZone?.ID ?: 'UTC'}:${dtStart}\r\n"
                    sb << "DTEND;TZID=${location?.timeZone?.ID ?: 'UTC'}:${endStr}\r\n"
                    sb << "SUMMARY:${escapeIcs(app.label)}\r\n"
                    sb << "DESCRIPTION:${escapeIcs("Estimated ${estimateMin}min — ${windows.size()} window(s)/day")}\r\n"
                    sb << "END:VEVENT\r\n"
                }
            }
        }
        c.add(Calendar.DAY_OF_MONTH, 1)
    }
    sb << "END:VCALENDAR\r\n"
    return sb.toString()
}

private String escapeIcs(String s) {
    if (!s) return ""
    return s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n")
}

// =========================================================================
// Configuration backup / restore
// =========================================================================

private String exportConfigJson() {
    Integer n = zoneCount()
    Map data = [
        exportedAt: nowString(),
        version:    getAppVersion(),
        uiMode:                 settings.uiMode,
        scheduleEnabled:        settings.scheduleEnabled,
        scheduleMode:           settings.scheduleMode,
        scheduleIntervalDays:   settings.scheduleIntervalDays,
        scheduleIntervalAnchor: settings.scheduleIntervalAnchor,
        scheduleDays:           settings.scheduleDays,
        scheduleStartTime:      settings.scheduleStartTime,
        scheduleStartTime2:     settings.scheduleStartTime2,
        scheduleStartTime3:     settings.scheduleStartTime3,
        scheduleOrder:          settings.scheduleOrder,
        scheduleBetweenZoneSec: settings.scheduleBetweenZoneSec,
        scheduleMaxRunMinutes:  settings.scheduleMaxRunMinutes,
        rainDelayEnabled:       settings.rainDelayEnabled,
        rainPopThreshold:       settings.rainPopThreshold,
        rainAmountThreshold:    settings.rainAmountThreshold,
        seasonalEnabled:        settings.seasonalEnabled,
        seasonalMaxPct:         settings.seasonalMaxPct,
        quietHoursEnabled:      settings.quietHoursEnabled,
        quietStartTime:         settings.quietStartTime,
        quietEndTime:           settings.quietEndTime,
        quietStopInProgress:    settings.quietStopInProgress,
        preRunLeadMinutes:      settings.preRunLeadMinutes,
        pauseResumeDelaySec:    settings.pauseResumeDelaySec,
        zoneCountPref:          settings.zoneCountPref,
        zones: (1..n).collect { i -> [
            id:           i,
            name:         settings."zone${i}Name",
            portLabel:    settings."zone${i}PortLabel",
            switchLabel:  settings."zone${i}Switch"?.displayName,
            enabled:      settings."zone${i}Enabled",
            runMinutes:   settings."zone${i}RunMinutes",
            cycleSoak:    settings."zone${i}CycleSoak",
            soakMinutes:  settings."zone${i}SoakMinutes",
            plant:        settings."zone${i}Plant",
            sprinkler:    settings."zone${i}Sprinkler",
            soil:         settings."zone${i}Soil"
        ] }
    ]
    return groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(data))
}

private void importConfig(String json) {
    if (!json?.trim()) {
        state.lastImportSummary = "Nothing pasted."
        return
    }
    Map data
    try {
        data = new groovy.json.JsonSlurper().parseText(json) as Map
    } catch (e) {
        state.lastImportSummary = "Could not parse JSON: ${e.message}"
        return
    }
    int applied = 0
    int skipped = 0
    data.each { k, v ->
        if (k in ["exportedAt", "version", "zones"]) { skipped++; return }
        try { app.updateSetting(k as String, v); applied++ } catch (e) { skipped++ }
    }
    (data.zones ?: []).each { Map z ->
        int i = (z.id ?: 0) as int
        if (i <= 0) return
        ["name":"Name","portLabel":"PortLabel","enabled":"Enabled","runMinutes":"RunMinutes",
         "cycleSoak":"CycleSoak","soakMinutes":"SoakMinutes","plant":"Plant","sprinkler":"Sprinkler","soil":"Soil"
        ].each { src, sfx ->
            def val = z[src]
            if (val != null) {
                try { app.updateSetting("zone${i}${sfx}" as String, val); applied++ } catch (e) { skipped++ }
            }
        }
    }
    state.lastImportSummary = "Applied ${applied} setting(s), skipped ${skipped} @ ${nowString()}. Re-pick device references for the affected zones."
    log.info "${app.label}: import ${state.lastImportSummary}"
}

// =========================================================================
// ZEN16 reachability watchdog
// =========================================================================

// How long these relays are *expected* to sit silent between waterings. A
// mains relay only reports when it's actuated, so the watchdog must not flag it
// as unreachable just for being idle between runs — only if it stays silent
// longer than a full watering cycle (i.e. it likely missed a scheduled run).
private int expectedQuietHours() {
    if (!settings.scheduleEnabled) return 8 * 24
    if (isIntervalMode()) {
        int n = (settings.scheduleIntervalDays ?: 2) as int
        return (n + 1) * 24
    }
    Map<String,Integer> dow = [SUN:1, MON:2, TUE:3, WED:4, THU:5, FRI:6, SAT:7]
    List<Integer> days = ((settings.scheduleDays ?: []) as List).collect { dow[it] }.findAll { it != null }.sort()
    if (!days) return 8 * 24
    int maxGap = 0
    for (int i = 0; i < days.size(); i++) {
        int next = (i + 1 < days.size()) ? days[i + 1] : days[0] + 7
        maxGap = Math.max(maxGap, next - days[i])
    }
    return (maxGap + 1) * 24   // longest gap between watering days + a buffer
}

def zen16Watchdog() {
    def parents = settings.hwZen16Parents
    if (!parents) return
    long now = now()
    // Default the stale threshold to the schedule's longest quiet stretch so an
    // idle-but-reachable relay isn't false-flagged between waterings.
    long staleMs = ((settings.zen16StaleHours ?: expectedQuietHours()) as int) * 3600L * 1000L
    // After an active ping we wait this long for an answer before declaring the
    // device unreachable. lastActivity only tracks when the relay last *reported*
    // — an idle-but-reachable relay can be silent for hours, so silence alone is
    // not "unreachable". We confirm with a ping first.
    long probeGraceMs = 15L * 60L * 1000L
    Map probeAt = (state.watchdogProbeAt ?: [:]) as Map
    Map alerted = (state.watchdogAlerted ?: [:]) as Map
    parents.each { dev ->
        String id = dev.id as String
        try {
            Long last = null
            if (dev.respondsTo("getLastActivity")) {
                Date la = dev.getLastActivity()
                if (la) last = la.getTime()
            }
            // A successful watering actuation by the app is itself proof THIS
            // controller is reachable — some Zooz drivers don't bump the parent
            // device's lastActivity when a child relay is toggled, so without this
            // a relay the app just drove would be false-flagged as unreachable.
            // Keyed per-parent so a run on one controller can't mask an outage on
            // another that didn't water this cycle.
            Long appActed = ((state.lastActuationByParent ?: [:]) as Map)[id] as Long
            if (appActed && (last == null || appActed > last)) last = appActed
            // Recent chatter (or no lastActivity support) → treat as reachable.
            if (last == null || (now - last) <= staleMs) {
                probeAt.remove(id); alerted.remove(id)
                return
            }
            Long probed = probeAt[id] as Long
            if (!probed) {
                // First time we notice the silence: poke the device, don't alert yet.
                pokeDevice(dev)
                probeAt[id] = now
                if (descTextEnable) log.info "${app.label}: '${dev.displayName}' quiet ${(now - last) / 3600000L}h — pinging to confirm reachability"
                return
            }
            if (last >= probed) {           // device answered after our ping → reachable
                probeAt.remove(id); alerted.remove(id)
                return
            }
            if ((now - probed) < probeGraceMs) return   // still within grace window
            // Silent well after an active ping → genuinely unreachable. Alert once.
            if (!alerted[id]) {
                long hrs = (now - last) / 3600000L
                log.warn "${app.label}: ZEN16 '${dev.displayName}' did not answer a ping — unreachable (${hrs}h since last report)"
                notify("watchdog.stale", [sensor: dev.displayName, hours: hrs])
                alerted[id] = now
            }
            pokeDevice(dev)   // keep probing so recovery is detected next cycle
        } catch (e) {
            // ignore — driver may not expose lastActivity / ping / refresh
        }
    }
    state.watchdogProbeAt = probeAt
    state.watchdogAlerted = alerted
}

// Record that the app successfully drove a relay, attributed to the controller
// that owns it, so the reachability watchdog treats that one parent as reachable.
// Human-friendly "x ago (absolute)" for an epoch-ms timestamp, for diagnostics.
private String agoString(Long ms) {
    if (!ms) return "never"
    long deltaSec = (now() - ms) / 1000L
    String abs = new Date(ms).format("yyyy-MM-dd HH:mm", location?.timeZone ?: TimeZone.getDefault())
    return "${fmtDuration(deltaSec as int)} ago (${abs})"
}

private void markControllerReachable(sw, int actuationSec = 0) {
    String pkey = controllerKeyFor(sw)
    if (!pkey) return
    Map m = (state.lastActuationByParent ?: [:]) as Map
    m[pkey] = now()
    state.lastActuationByParent = m
    // Remember the longest single continuous on-time we've driven on this
    // controller (seasonal-adjusted, real) so the hardware push can refuse to set
    // an auto-off timer shorter than a run we actually perform here.
    if (actuationSec > 0) {
        Map mx = (state.maxActuationSecByParent ?: [:]) as Map
        if (actuationSec > ((mx[pkey] ?: 0) as int)) {
            mx[pkey] = actuationSec
            state.maxActuationSecByParent = mx
        }
    }
}

// Minimum safe auto-off (minutes, buffer included) for a controller: the longest
// single continuous relay on-time THIS instance drives on it — from the schedule
// and from any longer run actually observed — plus a 2-minute margin. 0 means this
// instance drives nothing on that controller, so it imposes no floor.
private Integer requiredAutoOffMinForController(String pkey) {
    int maxSec = 0
    int n = (settings.zoneCountPref ?: 0) as int
    for (int i = 1; i <= n; i++) {
        if (settings."zone${i}Enabled" == false) continue
        def sw = settings."zone${i}Switch"
        if (!sw || controllerKeyFor(sw) != pkey) continue
        Integer base   = (resolveZoneBaseMinutes(i) ?: 0) as int
        Integer cycles = Math.max(1, ((settings."zone${i}CycleSoak" ?: "1") as int))
        int perCycleSec = Math.max(1, (base / cycles) as int) * 60
        if (perCycleSec > maxSec) maxSec = perCycleSec
    }
    Integer observed = (((state.maxActuationSecByParent ?: [:]) as Map)[pkey] ?: 0) as int
    if (observed > maxSec) maxSec = observed
    if (maxSec <= 0) return 0
    return ((int) Math.ceil(maxSec / 60.0d)) + 2
}

// Resolve a zone switch to its owning parent controller's device id (as String),
// matching the ids of the devices picked under hwZen16Parents. Child component
// relays report their parent via getParentDeviceId(); if the switch is itself a
// top-level device, its own id is used.
private String controllerKeyFor(sw) {
    if (!sw) return null
    try {
        def pid = sw.respondsTo("getParentDeviceId") ? sw.getParentDeviceId() : null
        return ((pid ?: sw.id) as String)
    } catch (e) {
        try { return sw.id as String } catch (e2) { return null }
    }
}

// Actively prod a device so a reachable-but-idle relay reports in (updating
// lastActivity). Prefer ping (Z-Wave NOP/ack); fall back to refresh.
private void pokeDevice(dev) {
    try {
        if (dev.respondsTo("ping"))         dev.ping()
        else if (dev.respondsTo("refresh")) dev.refresh()
    } catch (e) { /* driver may not support either */ }
}

// =========================================================================
// UI summary strings & helpers
// =========================================================================

private Integer zoneCount() { return (settings.zoneCountPref ?: 0) as int }

private String zoneSummaryString() {
    Integer n = zoneCount()
    if (n == 0) return "No zones configured yet — tap to set up"
    int enabled = 0
    for (int i = 1; i <= n; i++) if (settings."zone${i}Enabled" != false && settings."zone${i}Switch") enabled++
    return "${n} zone${n == 1 ? '' : 's'} configured, ${enabled} enabled with valid switch"
}

private String zoneOneLineSummary(int i) {
    def sw = settings."zone${i}Switch"
    String dev   = sw ? sw.displayName : "(no switch)"
    String port  = settings."zone${i}PortLabel" ?: ""
    Integer mins = resolveZoneBaseMinutes(i)
    Integer cycles = ((settings."zone${i}CycleSoak" ?: "1") as int)
    String plant = settings."zone${i}Plant" ?: ""
    String enabled = (settings."zone${i}Enabled" == false) ? " — DISABLED" : ""
    String runStr
    if (cycles > 1) {
        Integer soakMin   = (settings."zone${i}SoakMinutes" ?: 10) as int
        Integer perCycle  = Math.max(1, (mins / cycles) as int)
        Integer soakTotal = (cycles - 1) * soakMin
        Integer total     = mins + soakTotal
        runStr = "${mins}m water (${cycles}× ${perCycle}m) + ${soakTotal}m soak = ${total}m total"
    } else {
        runStr = "${mins}m"
    }
    String parts = "${runStr} · ${plant} · ${dev}"
    if (port) parts += " (${port})"
    return parts + enabled
}

private String zoneTypeIcon(String plant) {
    switch (plant) {
        case "Lawn":       return "1F33F"
        case "Garden":     return "1F96C"
        case "Flowers":    return "1F338"
        case "Shrubs":     return "1F333"
        case "Trees":      return "1F332"
        case "Xeriscape":  return "1F335"
        case "New Plants": return "1F331"
        default:           return "1F4A7"
    }
}

private String scheduleSummaryString() {
    if (!settings.scheduleEnabled) return "Disabled"
    String t = settings.scheduleStartTime ?: "not set"
    if (isIntervalMode()) return "${t} every ${settings.scheduleIntervalDays ?: 2} day(s)"
    List d = (settings.scheduleDays ?: []) as List
    return "${t} on ${d.join(', ') ?: 'no days'}"
}

private String weatherSummaryString() {
    List parts = []
    parts << (settings.rainDelayEnabled ? "Rain delay ON" : "Rain delay OFF")
    parts << (settings.seasonalEnabled  ? "Seasonal ON"   : "Seasonal OFF")
    if (settings.rainSensorDevice) parts << "rain gauge: ${settings.rainSensorDevice.displayName}"
    return parts.join(" · ")
}

private String pumpSummaryString() {
    return settings.pumpSwitch ? "Pump: ${settings.pumpSwitch.displayName}" : "No pump configured"
}

private String rainSensorSummaryString() {
    int w = (settings.rainSensorWaterDevices   ?: []).size()
    int c = (settings.rainSensorContactDevices ?: []).size()
    if (w == 0 && c == 0) return "No rain sensors configured"
    String state = rainSensorWet() ? "⛈ WET now" : "dry"
    return "${w} water + ${c} contact — ${state}"
}

private String pauseSensorSummaryString() {
    int c = (settings.pauseContacts ?: []).size()
    int s = (settings.pauseSwitches ?: []).size()
    if (c == 0 && s == 0) return "No pause sensors configured"
    String state = externalPauseActive() ? "PAUSED NOW" : "ready"
    return "${c} contact(s), ${s} switch(es) — ${state}"
}

private String hardwareSafetySummaryString() {
    def parents = settings.hwZen16Parents
    if (!parents) return "Not configured — hub-independent watchdog OFF (recommended ON)"
    Integer mins = (settings.hwAutoOffMinutes ?: (((settings.scheduleMaxRunMinutes ?: 60) as int) + 5)) as int
    return "${parents.size()} controller(s) · auto-off ${mins}min"
}

private String historySummaryString() {
    List h = (state.runHistory ?: []) as List
    if (!h) return "No runs recorded yet"
    return "${h.size()} record${h.size() == 1 ? '' : 's'} · last: ${h[-1]?.outcome ?: 'unknown'}"
}

private String dashboardSummaryString() {
    if (!settings.dashboardEnabled) return "Disabled (no child device)"
    def ch = getDashboardChild()
    return ch ? "Child device: ${ch.displayName}" : "Will be created on save"
}

private String diagnosticsSummaryString() {
    String status = forcedDelayDisplayString()
    return "Health, test runs, skip-next/rain-delay — ${status}"
}

private String restrictionsSummaryString() {
    List parts = []
    if (settings.quietHoursEnabled) parts << "quiet ${timeFmt(settings.quietStartTime)}-${timeFmt(settings.quietEndTime)}"
    if (settings.pauseModes) parts << "mode-pause: ${(settings.pauseModes as List).join(',')}"
    if (settings.hsmPauseEnabled) parts << "HSM-pause"
    Integer lead = (settings.preRunLeadMinutes ?: 0) as int
    if (lead > 0) parts << "pre-run ${lead}min"
    return parts.isEmpty() ? "None configured" : parts.join(" · ")
}

private String previewSummaryString() {
    return settings.scheduleEnabled ? "Calendar of next 7 days" : "(schedule disabled)"
}

private String backupSummaryString() {
    return state.lastImportSummary ? "Last import: ${state.lastImportSummary.take(80)}…" : "Export / restore JSON"
}

private String apiSummaryString() {
    return state.apiAccessToken ? "Token active" : "No token — tap to generate"
}

private String notificationSummaryString() {
    int regular = (settings.notifyDevices ?: []).size()
    int push    = (settings.pushoverDevice ?: []).size()
    if (regular == 0 && push == 0) return "No notification channels configured"
    List parts = []
    if (regular > 0) parts << "${regular} regular"
    if (push > 0)    parts << "${push} Pushover"
    return parts.join(" + ") + " channel(s)"
}

private String notifyEventsSummaryString() {
    int total = NOTIFY_EVENTS.size()
    int overridden = 0
    NOTIFY_EVENTS.each { k, _ ->
        String safe = (k as String).replace(".", "_").replace("-", "_")
        if (settings."notifyMsg_${safe}") overridden++
    }
    return "${total} event types · ${overridden} custom message override(s)"
}

private String runStatusString() {
    if (state.running) {
        Integer zid = (state.currentZoneId ?: 0) as int
        String zname = settings."zone${zid}Name" ?: "Zone ${zid}"
        Integer cycleIdx = (state.currentZoneCycleIdx ?: 0) as int
        Integer cycles   = (state.currentZoneCycles   ?: 1) as int
        return "RUNNING — ${zname} (cycle ${cycleIdx + 1}/${cycles})"
    }
    if (state.paused) {
        Integer zid = (state.currentZoneId ?: 0) as int
        String zname = settings."zone${zid}Name" ?: "Zone ${zid}"
        Integer remaining = (state.pausedRemainingSec ?: 0) as int
        String reason = state.pausedReason ?: "external sensor"
        return "PAUSED at ${zname} (${remaining}s remaining) — ${reason}"
    }
    if (isPaused()) {
        Long until = (state.pauseUntilMs ?: 0L) as long
        return "MANUAL PAUSE until ${new Date(until).format('HH:mm', location?.timeZone ?: TimeZone.getDefault())}"
    }
    return "idle"
}

// =========================================================================
// Page-button handler (Hubitat calls this for input type:"button")
// =========================================================================

def appButtonHandler(String btn) {
    switch (btn) {
        case "btnRunNow":
            log.info "${app.label}: manual run requested"
            runIn(1, "runSchedule", [data: [manual: true]])
            break
        case "btnStopAll":
            stopAllZones()
            break
        case "btnPushHardwareSafety":
            pushHardwareSafety()
            break
        case "btnRefreshDashboard":
            publishDashboardState()
            break
        case "btnRebuildZoneSwitches":
            // Delete every zone child and re-create from scratch
            getChildDevices().each { ch ->
                if (ch.deviceNetworkId?.startsWith("${app.id}-zone-")) {
                    try { deleteChildDevice(ch.deviceNetworkId) } catch (e) { log.warn "rebuild remove: ${e.message}" }
                }
            }
            maintainZoneSwitches()
            log.info "${app.label}: zone switches rebuilt"
            break
        case "btnClearHistory":
            state.runHistory = []
            log.info "${app.label}: history cleared"
            break
        case "btnRunHealthCheck":
            // healthCheckReport() runs every time the page renders; this just
            // touches state.lastHealthCheck so the user sees a fresh timestamp.
            healthCheckReport()
            break
        case "btnTestAllZones":
            testAllZones()
            break
        case "btnSkipNext":
            skipNext()
            break
        case "btnRainDelay6h":
            forceRainDelayHours(6)
            break
        case "btnRainDelay24h":
            forceRainDelayHours(24)
            break
        case "btnRainDelay48h":
            forceRainDelayHours(48)
            break
        case "btnRainDelay72h":
            forceRainDelayHours(72)
            break
        case "btnClearRainDelay":
            clearForcedDelays()
            break
        case "btnRefreshExport":
            // No-op; rendering exportConfigJson() is what refreshes the page.
            break
        case "btnImportConfig":
            importConfig(settings.importJson as String)
            break
        case "btnGenerateApiToken":
            try {
                state.apiAccessToken = createAccessToken()
                log.info "${app.label}: API token generated"
            } catch (e) {
                log.warn "${app.label}: createAccessToken failed (is OAuth enabled in app settings?) — ${e.message}"
            }
            break
        case "btnRevokeApiToken":
            state.apiAccessToken = null
            log.info "${app.label}: API token revoked"
            break
        case "btnNotifyTest":
            sendNotificationTest()
            break
        case "btnRefreshForecast":
            state.__omCacheAt = 0L
            state.__omCacheData = null
            log.info "${app.label}: forecast cache cleared"
            break
        case "btnResetEventDefaults":
            // Wipe every per-event override
            NOTIFY_EVENTS.each { k, _ ->
                String safe = (k as String).replace(".", "_").replace("-", "_")
                app.removeSetting("notifyMsg_${safe}")
                app.removeSetting("notifyEvent_${safe}")
                app.removeSetting("notifyPriority_${safe}")
            }
            log.info "${app.label}: per-event notification overrides cleared"
            break
        default:
            // Per-zone test buttons: "btnTestZone_<N>"
            if (btn?.startsWith("btnTestZone_")) {
                Integer zid = btn.replaceFirst("btnTestZone_", "").toInteger()
                testZoneRun(zid)
            } else if (btn?.startsWith("btnClearMoisture_")) {
                Integer zid = btn.replaceFirst("btnClearMoisture_", "").toInteger()
                Map all = (state.moistureLearning ?: [:]) as Map
                all.remove(zid.toString())
                state.moistureLearning = all
                log.info "${app.label}: cleared moisture learning for zone ${zid}"
            } else {
                log.warn "unknown button: ${btn}"
            }
    }
}
