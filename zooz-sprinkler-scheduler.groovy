/**
 *  Zooz Sprinkler Scheduler — Hubitat app
 *  IMPORT URL: https://raw.githubusercontent.com/RamSet/hubitat/main/zooz-sprinkler-scheduler.groovy
 *
 *  Runs sprinkler zones via Zooz ZEN16 800LR (3-relay) controllers, or any
 *  Hubitat device exposing the Switch capability. Inspired by Plaid Systems'
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
    importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/main/zooz-sprinkler-scheduler.groovy",
    description: "Sprinkler schedule using Zooz ZEN16 (or any switch) relays — Spruce-style logic, hardware-agnostic",
    category: "Green Living",
    iconUrl:   "https://raw.githubusercontent.com/hfg-gmuend/openmoji/master/color/618x618/1F4A7.png",
    iconX2Url: "https://raw.githubusercontent.com/hfg-gmuend/openmoji/master/color/618x618/1F4A7.png",
    iconX3Url: "https://raw.githubusercontent.com/hfg-gmuend/openmoji/master/color/618x618/1F4A7.png",
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

String getAppVersion() { return "v0.5.0 (2026-06)" }

// =========================================================================
// Notification event keys & defaults
// =========================================================================
// Every notification flows through notify(key, context). Each event has:
//   - An on/off toggle  : settings."notifyEvent_${key}"      (default true unless noted)
//   - A custom message  : settings."notifyMsg_${key}"        (overrides DEFAULT)
//   - A Pushover priority override : settings."notifyPriority_${key}"
// Default messages support ${app}, ${zone}, ${reason}, ${duration}, ${cycle},
// ${remaining}, ${sensor}, ${count}, ${minutes}, ${hours}, ${detail}, ${planSize},
// ${seasonalMult}, ${until}, ${mode}, ${hsm}, ${delay}. Missing variables
// render as the empty string.
private static final Map NOTIFY_EVENTS = [
    // Lifecycle
    "schedule.start"   : [section: "Lifecycle",  default: '${app}: ▶ schedule starting — ${planSize} zone(s), seasonal ×${seasonalMult}'],
    "schedule.finish"  : [section: "Lifecycle",  default: '${app}: ■ schedule complete'],
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
    "skip.frost"       : [section: "Skips",      default: '${app}: skipped — overnight low ${tempF}°F (threshold ${threshold}°F)'],
    "skip.cold"        : [section: "Skips",      default: '${app}: skipped — today\'s high ${tempF}°F (threshold ${threshold}°F)'],
    "skip.wind"        : [section: "Skips",      default: '${app}: skipped — max wind ${windMph} mph (threshold ${threshold} mph)'],
    "skip.coord"       : [section: "Skips",      default: '${app}: skipped — coordination switch held by another schedule (gave up after ${count} retries)'],

    // Pause & resume
    "pause.activate"   : [section: "Pause",      default: '${app}: PAUSED at ${zone} (${remaining}s remaining) — ${reason}'],
    "pause.clear"      : [section: "Pause",      default: '${app}: pause sensors clear — resuming in ${delay}s'],
    "pause.resume"     : [section: "Pause",      default: '${app}: resumed ${zone} (${remaining}s left)'],
    "rain.mid.stop"    : [section: "Pause",      default: '${app}: rain detected (${sensor}) — stopped mid-run'],

    // Sensors
    "sensor.rain.wet"  : [section: "Sensors",    default: '${app}: rain sensor ${sensor} → WET',  defaultOff: true],
    "sensor.rain.dry"  : [section: "Sensors",    default: '${app}: rain sensor ${sensor} → DRY',  defaultOff: true],
    "sensor.pause.on"  : [section: "Sensors",    default: '${app}: pause sensor ${sensor} active', defaultOff: true],
    "sensor.pause.off" : [section: "Sensors",    default: '${app}: pause sensor ${sensor} clear',  defaultOff: true],

    // Hardware & watchdog
    "hardware.push"    : [section: "Hardware",   default: '${app}: ZEN16 watchdog set to ${minutes}min on ${count} controller(s)'],
    "watchdog.stale"   : [section: "Hardware",   default: '${app}: ${sensor} unreachable for ${hours}h'],

    // Test / manual
    "test.run"         : [section: "Test",       default: '${app}: testing ${zone} for ${duration}', defaultOff: true],

    // Moisture-aware
    "moisture.earlyStop": [section: "Sensors",    default: '${app}: ${zone} early-stopped — soil reached ${moisture}% (target ${target}%) after ${duration}'],
    "moisture.adapted":   [section: "Sensors",    default: '${app}: ${zone} adapted ${baseMin}m → ${adjMin}m (soil ${moisture}%, dryness ${dryness}×)', defaultOff: true],
    "moisture.learned":   [section: "Sensors",    default: '${app}: ${zone} learning — ${delta}%/min over ${runMin}m run', defaultOff: true],

    // Fertilizer
    "fertilizer.armed":    [section: "Lifecycle",  default: '${app}: ${zone} starting in fertilizer mode (${cycles}× ${perCycleMin}m, soak ${soakMin}m)'],
    "fertilizer.disarmed": [section: "Lifecycle",  default: '${app}: ${zone} fertilizer mode complete and disarmed', defaultOff: true]
]

private static final List NOTIFY_PRIORITIES = ["default", "-2 silent", "-1 quiet", "0 normal", "1 high", "2 emergency"]
private String openmoji(String code) {
    return "https://raw.githubusercontent.com/hfg-gmuend/openmoji/master/color/618x618/${code}.png"
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
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "<b>Zooz Sprinkler Scheduler</b> — ${getAppVersion()}",
                install: true, uninstall: true) {
        // Warnings banner: surface the most actionable config issues at the
        // top of every visit to the main page so they aren't buried.
        List<String> banners = quickWarnings()
        if (banners) {
            section {
                paragraph "<div style='background:#fff8e1;border-left:4px solid #f39c12;padding:8px;'>" +
                          "<b>⚠ Attention</b><ul style='margin:4px 0'>" +
                          banners.collect { "<li>${it}</li>" }.join("") +
                          "</ul></div>"
            }
        }
        section {
            label title: "<b>Schedule name</b> (shown in the Apps list)",
                  required: true
        }
        section("<b>Configuration</b>") {
            href name: "zoneListPage", title: "Zones (${zoneCount()})", page: "zoneListPage",
                 image: openmoji("1F33F"),
                 description: zoneSummaryString()
            href name: "schedulePage", title: "Schedule", page: "schedulePage",
                 image: openmoji("23F1"),
                 description: scheduleSummaryString()
            href name: "weatherPage", title: "Weather (rain delay & seasonal adjust)", page: "weatherPage",
                 image: openmoji("1F327"),
                 description: weatherSummaryString()
            href name: "rainSensorPage", title: "Rain sensors (binary wet/dry)", page: "rainSensorPage",
                 image: openmoji("1F4A7"),
                 description: rainSensorSummaryString()
            href name: "pauseSensorPage", title: "Pause sensors (contacts / switches)", page: "pauseSensorPage",
                 image: openmoji("23F8"),
                 description: pauseSensorSummaryString()
            href name: "pumpPage", title: "Pump / Master valve", page: "pumpPage",
                 image: openmoji("1F527"),
                 description: pumpSummaryString()
            href name: "hardwarePage", title: "Hardware safety (ZEN16 watchdog)", page: "hardwarePage",
                 image: openmoji("1F6E1"),
                 description: hardwareSafetySummaryString()
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
            href name: "aboutPage", title: "About / changelog", page: "aboutPage",
                 image: openmoji("2139"),
                 description: "Version ${getAppVersion()}"
        }
        section("<b>Run now / pause</b>") {
            input name: "btnRunNow",  type: "button", title: "Run schedule now"
            input name: "btnStopAll", type: "button", title: "Stop all zones now"
            paragraph "<i>Status: ${runStatusString()}</i>"
            input name: "pauseHours", type: "number", title: "Pause schedule for N hours (0 = active)",
                  range: "0..72", required: false, defaultValue: 0, submitOnChange: true
        }
        section("<b>Logging</b>") {
            input name: "debugOutput", type: "bool", title: "Enable debug logging",
                  description: "<i>Auto-turns off after 30 minutes</i>", defaultValue: false
            input name: "descTextEnable", type: "bool", title: "Enable description text logging",
                  defaultValue: true
        }
        section {
            paragraph "<div style='font-size:0.85em;color:#666'>" +
                      "<b>Zooz Sprinkler Scheduler</b> ${getAppVersion()} · " +
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
    if (settings.scheduleEnabled && (!settings.scheduleStartTime || !settings.scheduleDays)) {
        w << "Schedule is enabled but a start time or day list is missing."
    }
    if ((settings.rainDelayEnabled || settings.seasonalEnabled) && (!location?.latitude || !location?.longitude)) {
        w << "Weather is enabled but Hubitat location lat/long is not set (Settings → Location)."
    }
    if (!settings.hwZen16Parents) {
        w << "Hub-independent hardware watchdog is OFF. Recommended: open Hardware safety and pick the ZEN16 parent device(s)."
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
            paragraph "Add as many zones as you have relays. Each zone maps to a single Hubitat <b>Switch</b> device — typically a child of your Zooz ZEN16 (one of the three relays per controller), but anything with the Switch capability works."
            input name: "zoneCountPref", type: "number", title: "<b>Number of zones</b>",
                  range: "0..64", defaultValue: 0, submitOnChange: true, required: true
        }
        Integer n = (settings.zoneCountPref ?: 0) as int
        if (n > 0) {
            section("<b>Configure each zone</b>") {
                paragraph "<i>Tap a zone to set its switch device, run time, plant/soil/sprinkler type and the physical port label so you know which Zooz relay drives it.</i>"
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
        section("<b>Identity</b>") {
            input name: "zone${zid}Name",      type: "text",   title: "Zone name (e.g. \"Front Lawn N\")",
                  required: true, submitOnChange: true
            input name: "zone${zid}PortLabel", type: "text",
                  title: "Physical port label (free text — e.g. \"ZEN16 #1 Port 2 (15A)\")",
                  description: "<i>Useful for matching this zone to a relay during wiring audits.</i>",
                  required: false
            input name: "zone${zid}Enabled",   type: "bool",   title: "Zone enabled in schedule",
                  defaultValue: true
        }
        section("<b>Relay (switch device)</b>") {
            input name: "zone${zid}Switch", type: "capability.switch",
                  title: "Switch device for this zone",
                  description: "Pick the ZEN16 relay child device that drives this valve",
                  required: true, multiple: false, submitOnChange: true
            def sw = settings."zone${zid}Switch"
            if (sw) {
                paragraph "<i>Selected device:</i> <b>${sw.displayName}</b><br>" +
                          "<i>Current state:</i> <b>${sw.currentValue('switch') ?: 'unknown'}</b>"
            }
        }
        section("<b>Run time</b>") {
            input name: "zone${zid}RuntimeMode", type: "enum",
                  title: "How to compute base runtime",
                  options: [
                    "fixed":  "Fixed: N minutes per cycle",
                    "weekly": "Weekly target: total minutes per week ÷ days per week (Spruce-style)"
                  ],
                  defaultValue: "fixed", submitOnChange: true
            if ((settings."zone${zid}RuntimeMode" ?: "fixed") == "weekly") {
                input name: "zone${zid}WeeklyMinutes", type: "number",
                      title: "Total minutes per week (target)",
                      description: "<i>Distributed across the days-per-week below. Seasonal scaling still applies.</i>",
                      range: "1..1000", defaultValue: 30, required: true
                input name: "zone${zid}DaysPerWeek", type: "number",
                      title: "Days per week (how often this zone gets watered)",
                      range: "1..7", defaultValue: 3, required: true
                paragraph "<i>Effective base = ${zoneEffectiveBaseString(zid)} per cycle.</i>"
            } else {
                input name: "zone${zid}RunMinutes", type: "number",
                      title: "Base run time per cycle (minutes)",
                      description: "<i>Seasonal weather adjust will scale this if enabled in Weather settings.</i>",
                      range: "1..240", defaultValue: 10, required: true
            }
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
        section("<b>Fertilizer mode (one-shot)</b>") {
            paragraph "Arming fertilizer mode changes the NEXT run only: zone runs as <b>short bursts</b> with <b>long soak intervals</b> for dilution and uniform uptake. Auto-disarms after the run completes."
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

        section("<b>Weekly budget cap (optional)</b>") {
            input name: "zone${zid}WeeklyCapMinutes", type: "number",
                  title: "Maximum minutes per ISO week (0 = no cap)",
                  description: "<i>Hard ceiling tracked per zone. If a run would exceed, the cycle is clipped; if no minutes remain, the zone is skipped (notified via the skip.budget event).</i>",
                  range: "0..1000", defaultValue: 0
            paragraph "<i>Used this week: <b>${(state.zoneMinutesThisWeek ?: [:])[zid.toString()] ?: 0} min</b></i>"
        }
        section("<b>Landscape (informational + seasonal adjust)</b>") {
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
        section("<b>Moisture-aware watering (optional)</b>") {
            input name: "zone${zid}MoistureSensor", type: "capability.relativeHumidityMeasurement",
                  title: "Soil moisture sensor",
                  description: "<i>Any device exposing the <code>humidity</code> attribute (most Hubitat soil moisture sensors), or a <code>moisture</code> attribute for some niche drivers.</i>",
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
                      description: "<i>Used by adaptive modes to scale runtime. Lower = more aggressive watering when the soil is dry.</i>",
                      range: "0..100", defaultValue: 20, required: false
                String attrPick = settings."zone${zid}MoistureAttribute" ?: "humidity"
                input name: "zone${zid}MoistureAttribute", type: "enum",
                      title: "Sensor attribute to read",
                      options: ["humidity": "humidity (default — most Hubitat soil moisture sensors)",
                                "moisture": "moisture (some specialised drivers)"],
                      defaultValue: "humidity"
                def sw = settings."zone${zid}MoistureSensor"
                def cur = sw?.currentValue(attrPick)
                paragraph "<i>Current reading: <b>${cur != null ? "${cur}%" : "n/a"}</b> · " +
                          "${zoneAdaptiveStatusString(zid)}</i>"
            }
        }
        section {
            paragraph "<i>Last run: ${state.lastRunByZone?.get(zid as String) ?: 'never'}</i>"
        }
    }
}

def schedulePage() {
    dynamicPage(name: "schedulePage", title: "Schedule") {
        section("<b>When to run</b>") {
            input name: "scheduleEnabled", type: "bool",
                  title: "Schedule enabled",
                  defaultValue: true
            input name: "scheduleDays", type: "enum",
                  title: "Days of week",
                  options: ["MON","TUE","WED","THU","FRI","SAT","SUN"],
                  multiple: true, required: true,
                  defaultValue: ["MON","WED","FRI"]
            paragraph "<b>Watering windows.</b> Each window starts a full sweep through all enabled zones. Skip slots 2 and 3 if you only want one start time."
            input name: "scheduleStartTime",  type: "time",
                  title: "Window 1 start time", required: true
            input name: "scheduleStartTime2", type: "time",
                  title: "Window 2 start time (optional)", required: false
            input name: "scheduleStartTime3", type: "time",
                  title: "Window 3 start time (optional)", required: false
        }
        section("<b>Behaviour</b>") {
            input name: "scheduleOrder", type: "enum",
                  title: "Zone ordering",
                  options: ["sequential": "Sequential (by zone number)",
                            "random":     "Random order each run"],
                  defaultValue: "sequential"
            input name: "scheduleBetweenZoneSec", type: "number",
                  title: "Delay between zones (seconds)",
                  range: "0..600", defaultValue: 10
        }
        section("<b>Auto-stagger across schedules</b>") {
            paragraph "If you have multiple instances of this app (one per yard area), they'd all start at their configured times — possibly overlapping and overwhelming pipe pressure. Pick a single <b>Virtual Switch</b> shared between every instance: each instance checks it before starting, turns it ON for the duration of its run, and OFF at the end. Other instances scheduled at the same time see the lock and defer."
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

        section("<b>Software failsafe (per-zone cap)</b>") {
            input name: "scheduleMaxRunMinutes", type: "number",
                  title: "Maximum single zone run time (minutes)",
                  description: "<i>Caps every zone's seasonally-adjusted runtime. Set the matching <b>Hardware safety</b> auto-off timer to this value + 5min for hub-independent protection.</i>",
                  range: "1..240", defaultValue: 60
        }
    }
}

def weatherPage() {
    dynamicPage(name: "weatherPage", title: "Weather (Open-Meteo)") {
        section("<b>Provider</b>") {
            paragraph "Weather data comes from <a href='https://open-meteo.com'>Open-Meteo</a>. " +
                      "No API key needed. Uses your hub's configured location " +
                      "(Settings → Location → ${location?.latitude}, ${location?.longitude})."
        }
        section("<b>Rain delay</b>") {
            input name: "rainDelayEnabled", type: "bool",
                  title: "Skip schedule when rain is expected or has fallen recently",
                  defaultValue: true
            input name: "rainPopThreshold", type: "number",
                  title: "Skip if today's precipitation probability is above this % (0 = skip on any chance)",
                  range: "0..100", defaultValue: 60
            input name: "rainAmountThreshold", type: "decimal",
                  title: "Skip if forecast or past-24h rain exceeds this many inches",
                  range: "0..5", defaultValue: 0.2
        }
        section("<b>Smart skips (temperature / wind)</b>") {
            paragraph "Skip runs based on forecast extremes — useful for off-season frost protection and avoiding water loss in high wind."
            input name: "smartSkipFrostF", type: "number",
                  title: "Skip if overnight low (today) is below this °F (frost protection)",
                  description: "<i>Typical: 36 (below freezing risk) or 32 (hard freeze).</i>",
                  range: "-40..60", required: false
            input name: "smartSkipColdHighF", type: "number",
                  title: "Skip if today's high is below this °F (winter mode)",
                  description: "<i>Typical: 50 (cool-season grass stops growing) or 40 (no point watering anything).</i>",
                  range: "-40..120", required: false
            input name: "smartSkipWindMph", type: "number",
                  title: "Skip if today's max wind is above this mph (atomized spray loss)",
                  description: "<i>Typical: 15 mph causes significant drift on spray heads; 25 mph is severe.</i>",
                  range: "0..100", required: false
        }

        section("<b>Seasonal adjust</b>") {
            input name: "seasonalEnabled", type: "bool",
                  title: "Scale base run times by upcoming weather (hotter/drier → longer; cooler/wetter → shorter)",
                  defaultValue: true
            input name: "seasonalMaxPct", type: "number",
                  title: "Cap the seasonal scaling at ±%",
                  description: "<i>Prevents extreme adjustments. 50 means run time can be scaled from 50% to 150% of baseline.</i>",
                  range: "0..100", defaultValue: 50
        }
        section("<b>Optional external rain gauge</b>") {
            input name: "rainSensorDevice", type: "capability.relativeHumidityMeasurement",
                  title: "Local weather station / rain gauge (must expose a numeric \"rainToday\" attribute, in inches)",
                  required: false
        }
        section("<b>Today's forecast (live from Open-Meteo)</b>") {
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
    sb << "<tr><th align='left'>Day</th><th align='left'>High °F</th><th align='left'>POP %</th><th align='left'>Precip in</th><th align='left'>Sunrise</th><th align='left'>Sunset</th></tr>"
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
    sb << "<br><i>Past-24h precipitation: ${String.format('%.2f', omPrecipLast24h(om))} in"
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
            paragraph "Binary rain sensors (Water Sensor capability). If ANY of the selected sensors reports <b>wet</b>, the schedule is skipped, and if a run is already in progress it is stopped immediately. Use this for hard-wired rain sensors (e.g. the rain input on a controller that exposes wet/dry) or wireless leak/rain detectors."
            input name: "rainSensorWaterDevices", type: "capability.waterSensor",
                  title: "Rain sensors (wet/dry)",
                  multiple: true, required: false, submitOnChange: true
            input name: "rainSensorStopRunning", type: "bool",
                  title: "Stop a running schedule if a rain sensor goes wet mid-run",
                  defaultValue: true
            input name: "rainSensorClearMinutes", type: "number",
                  title: "After all rain sensors return to dry, wait this many minutes before un-blocking the schedule",
                  range: "0..720", defaultValue: 60
        }
        if (settings.rainSensorWaterDevices) {
            section("<b>Current state</b>") {
                settings.rainSensorWaterDevices.each { dev ->
                    paragraph "• ${dev.displayName} — <b>${dev.currentValue('water') ?: 'unknown'}</b>"
                }
            }
        }
    }
}

def pauseSensorPage() {
    dynamicPage(name: "pauseSensorPage", title: "Pause sensors") {
        section {
            paragraph "External devices that pause the schedule when active. Useful for: contact sensors on a back door (pause when door open so the kids don't get sprayed), a manual override switch, a garage door, a presence sensor, etc."
        }
        section("<b>Contact sensors</b>") {
            input name: "pauseContacts", type: "capability.contactSensor",
                  title: "Pause when ANY of these contacts are in the trigger state",
                  multiple: true, required: false, submitOnChange: true
            input name: "pauseContactsState", type: "enum",
                  title: "Trigger state for contacts",
                  options: ["open":"open", "closed":"closed"],
                  defaultValue: "open"
        }
        section("<b>Switches</b>") {
            input name: "pauseSwitches", type: "capability.switch",
                  title: "Pause when ANY of these switches are in the trigger state",
                  description: "<i>e.g. a virtual switch tied to Alexa, a wall override, a tablet button</i>",
                  multiple: true, required: false, submitOnChange: true
            input name: "pauseSwitchesState", type: "enum",
                  title: "Trigger state for switches",
                  options: ["on":"on", "off":"off"],
                  defaultValue: "on"
        }
        section("<b>Behaviour</b>") {
            input name: "pauseMode", type: "enum",
                  title: "What happens mid-run when a pause sensor activates",
                  options: ["pause":  "Pause the current zone immediately, resume from the exact moment when all sensors clear (recommended)",
                            "stop":   "Stop everything and skip the rest of the schedule (wait for next scheduled time)"],
                  defaultValue: "pause"
            input name: "pauseResumeDelaySec", type: "number",
                  title: "After all pause sensors clear, wait this many seconds before resuming",
                  description: "<i>Lets the door fully close / person walk away before water comes back on.</i>",
                  range: "0..600", defaultValue: 30
        }
        if (settings.pauseContacts || settings.pauseSwitches) {
            section("<b>Current state</b>") {
                (settings.pauseContacts ?: []).each { dev ->
                    paragraph "• ${dev.displayName} (contact) — <b>${dev.currentValue('contact') ?: 'unknown'}</b>"
                }
                (settings.pauseSwitches ?: []).each { dev ->
                    paragraph "• ${dev.displayName} (switch) — <b>${dev.currentValue('switch') ?: 'unknown'}</b>"
                }
                paragraph "<i>Aggregate pause-active right now: <b>${externalPauseActive() ? 'YES — schedule blocked' : 'no — schedule allowed'}</b></i>"
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
                      description: "<i>e.g. \"ZEN16 #2 Port 1 (20A)\"</i>", required: false
                input name: "pumpPreSec",  type: "number",
                      title: "Pre-delay (seconds) — pump on, wait, then first zone",
                      range: "0..120", defaultValue: 5
                input name: "pumpPostSec", type: "number",
                      title: "Post-delay (seconds) — last zone off, wait, then pump off",
                      range: "0..120", defaultValue: 5
                paragraph "<i>Current state of ${p.displayName}: <b>${p.currentValue('switch') ?: 'unknown'}</b></i>"
            }
        }
    }
}

def hardwarePage() {
    Integer maxRun = (settings.scheduleMaxRunMinutes ?: 60) as int
    Integer targetMin = Math.max(1, maxRun + 5)   // app max + 5min buffer
    dynamicPage(name: "hardwarePage", title: "Hardware safety — ZEN16 watchdog") {
        section {
            paragraph "<b>Why this matters.</b> If the hub crashes mid-cycle, dies, " +
                      "loses Z-Wave, or the app errors out, the relay stays ON until " +
                      "someone notices. That's how a stuck sprinkler floods a yard. " +
                      "Zooz ZEN16 has per-relay <b>hardware auto-off timers</b> " +
                      "(parameters 6 / 8 / 10) that fire <i>inside the relay itself</i> — " +
                      "no hub required. We push them once and forget."
            paragraph "<b>Recommended target for this schedule:</b> <code>${targetMin} minutes</code> " +
                      "(the schedule's max-zone-run-minutes plus a 5-minute safety buffer)."
        }
        section("<b>Pick the ZEN16 parent controller(s)</b>") {
            paragraph "<i>Pick the parent ZEN16 device (the one with name containing \"ZEN16\" — not its child relays). Hub will push parameters 6/8/10 (auto-off timer for R1/R2/R3), 15/17/19 (timer unit = minutes), 1 (power-fail state = OFF), and verify 24 (DC motor mode) is OFF.</i>"
            input name: "hwZen16Parents", type: "capability.actuator",
                  title: "ZEN16 parent device(s)",
                  multiple: true, required: false, submitOnChange: true
        }
        section("<b>Recommended values</b>") {
            input name: "hwAutoOffMinutes", type: "number",
                  title: "Auto-off timer (minutes) to push to P6/P8/P10",
                  description: "<i>Default = schedule max-run + 5min buffer. Set 0 to disable hardware watchdog (NOT recommended).</i>",
                  range: "0..1440", defaultValue: targetMin
            input name: "hwPowerFailOff", type: "bool",
                  title: "Set P1 (power-fail state) = OFF so relays don't auto-resume after a power blip",
                  defaultValue: true
            input name: "hwForceDcMotorOff", type: "bool",
                  title: "Confirm P24 (DC motor interlock mode) = OFF — interlocks R1/R2 if accidentally enabled",
                  defaultValue: true
        }
        if (settings.hwZen16Parents) {
            section("<b>Push now</b>") {
                input name: "btnPushHardwareSafety", type: "button",
                      title: "Push recommended Z-Wave parameters to selected ZEN16(s)"
                paragraph "<i>${state.hwLastPushSummary ?: 'No push performed yet.'}</i>"
            }
            section("<b>Selected controllers</b>") {
                settings.hwZen16Parents.each { dev ->
                    paragraph "• <b>${dev.displayName}</b> (id ${dev.id}) — supports setParameter: " +
                              "<b>${dev.hasCommand('setParameter') ? 'yes' : 'NO — push will fail; install krlaframboise driver or use the built-in ZEN16 driver that exposes setParameter'}</b>"
                }
            }
        }
        section {
            paragraph "<i>References:</i> " +
                      "<a href='https://www.support.getzooz.com/kb/article/371'>Zooz KB #371 — Sprinkler use on Hubitat</a> · " +
                      "<a href='https://www.support.getzooz.com/kb/article/376'>KB #376 — Advanced settings</a>"
        }
    }
}

def historyPage() {
    dynamicPage(name: "historyPage", title: "Run history") {
        List runs = (state.runHistory ?: []) as List
        section {
            paragraph "Last ${runs.size()} run${runs.size() == 1 ? '' : 's'} (newest first). Capped at 50."
            if (!runs) {
                paragraph "<i>No runs recorded yet.</i>"
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
            paragraph "Optionally create a child <b>Virtual Switch</b> that reflects this schedule's state. " +
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
                paragraph "<i>Child device: <b>${existing ? existing.displayName : 'will be created on save'}</b></i>"
                if (existing) {
                    input name: "btnRefreshDashboard", type: "button", title: "Refresh tile state now"
                }
            } else {
                def existing = getDashboardChild()
                if (existing) {
                    paragraph "<i>Existing child device <b>${existing.displayName}</b> will be removed on save.</i>"
                }
            }
        }
    }
}

def diagnosticsPage() {
    dynamicPage(name: "diagnosticsPage", title: "Diagnostics & test runs") {
        section("<b>Health check</b>") {
            paragraph healthCheckReport()
            input name: "btnRunHealthCheck", type: "button", title: "Re-run health check"
        }
        section("<b>Per-zone test runs</b>") {
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
        section("<b>Force rain delay</b>") {
            paragraph "Skip the next scheduled run, or block runs for a fixed number of hours."
            input name: "btnSkipNext",       type: "button", title: "Skip next scheduled run"
            input name: "btnRainDelay6h",    type: "button", title: "Force rain delay: 6 hours"
            input name: "btnRainDelay24h",   type: "button", title: "Force rain delay: 24 hours"
            input name: "btnRainDelay48h",   type: "button", title: "Force rain delay: 48 hours"
            input name: "btnRainDelay72h",   type: "button", title: "Force rain delay: 72 hours"
            input name: "btnClearRainDelay", type: "button", title: "Clear all forced delays"
            paragraph "<i>Currently: ${forcedDelayDisplayString()}</i>"
        }
        section("<b>Live state dump</b>") {
            paragraph stateDumpString()
        }
    }
}

def restrictionsPage() {
    dynamicPage(name: "restrictionsPage", title: "Restrictions") {
        section("<b>Quiet hours blackout</b>") {
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

        section("<b>Hubitat mode pause</b>") {
            paragraph "Pause/skip the schedule when the Hubitat location is in any of these modes."
            input name: "pauseModes", type: "mode",
                  title: "Pause when mode is", multiple: true, required: false
        }

        section("<b>HSM (Hubitat Safety Monitor) pause</b>") {
            paragraph "Pause if HSM enters an alarmed state (intrusion / water / smoke). Re-enables when HSM clears."
            input name: "hsmPauseEnabled", type: "bool",
                  title: "Pause schedule when HSM is in any alarmed state",
                  defaultValue: false
            input name: "hsmPauseArmedAway", type: "bool",
                  title: "Also pause when HSM is in armedAway",
                  defaultValue: false
        }

        section("<b>Pre-run lead notification</b>") {
            paragraph "Send a notification N minutes BEFORE each scheduled window starts. Lets people clear the yard. Uses the notification devices selected on the Notifications page."
            input name: "preRunLeadMinutes", type: "number",
                  title: "Minutes before scheduled start (0 = disabled)",
                  range: "0..60", defaultValue: 0
        }

        if (settings.quietHoursEnabled || settings.pauseModes || settings.hsmPauseEnabled) {
            section("<b>Current state</b>") {
                paragraph quietHoursActive() ? "🌙 In quiet hours right now — runs blocked" : "✓ Outside quiet hours"
                if (settings.pauseModes) paragraph "Current mode: <b>${location?.mode ?: '?'}</b> — " +
                                                    "${modeShouldPause() ? '⏸ in pause list' : '✓ not in pause list'}"
                if (settings.hsmPauseEnabled) paragraph "HSM state: <b>${location?.hsmStatus ?: 'unknown'}</b> — " +
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
        section("<b>Schedule</b>") {
            paragraph previewNextSevenDaysHtml()
        }
    }
}

def backupPage() {
    dynamicPage(name: "backupPage", title: "Backup / restore configuration") {
        section("<b>Export</b>") {
            paragraph "<i>Copy the JSON below to back up this schedule. Device references (switches, sensors, controllers) are stored as labels — you'll have to re-pick the actual devices after restore on a different hub.</i>"
            input name: "btnRefreshExport", type: "button", title: "Refresh export"
            paragraph "<textarea readonly style='width:100%;height:240px;font-family:monospace;font-size:0.85em'>${exportConfigJson()}</textarea>"
        }
        section("<b>Import</b>") {
            paragraph "<i>Paste a previously exported JSON and tap Apply. Non-device settings (run times, weather thresholds, days, etc.) are restored. Existing zones are preserved unless overwritten by the import.</i>"
            input name: "importJson", type: "text",
                  title: "Paste JSON here",
                  required: false
            input name: "btnImportConfig", type: "button", title: "Apply import"
            if (state.lastImportSummary) {
                paragraph "<i>${state.lastImportSummary}</i>"
            }
        }
    }
}

def apiPage() {
    String token = state.apiAccessToken ?: ""
    String localUri = ""
    try { localUri = getFullLocalApiServerUrl() } catch (ignored) {}
    dynamicPage(name: "apiPage", title: "External JSON API") {
        section("<b>What this is</b>") {
            paragraph "Read-only status JSON plus a small set of POST endpoints (run / stop / skip / delay). Token-protected. Use it for a phone widget, a Grafana panel, a script that triggers watering after sunset, etc."
        }
        section("<b>Endpoints</b>") {
            if (!token) {
                paragraph "<i>Token not yet generated. Tap below to generate one (Hubitat will create an OAuth access token for this app).</i>"
                input name: "btnGenerateApiToken", type: "button", title: "Generate access token"
            } else {
                paragraph "<b>Token:</b> <code>${token}</code><br>" +
                          "<b>Local base URL:</b> <code>${localUri}</code><br><br>" +
                          "<b>GET</b>  <code>${localUri}/status?access_token=${token}</code> — JSON status<br>" +
                          "<b>GET</b>  <code>${localUri}/dashboard?access_token=${token}</code> — HTML auto-refreshing dashboard<br>" +
                          "<b>GET</b>  <code>${localUri}/calendar.ics?access_token=${token}</code> — iCalendar feed (next 30 days)<br>" +
                          "<b>POST</b> <code>${localUri}/run?access_token=${token}</code><br>" +
                          "<b>POST</b> <code>${localUri}/stop?access_token=${token}</code><br>" +
                          "<b>POST</b> <code>${localUri}/skip?access_token=${token}</code><br>" +
                          "<b>POST</b> <code>${localUri}/delay?access_token=${token}&hours=24</code>"
                input name: "btnRevokeApiToken", type: "button", title: "Revoke / regenerate token"
            }
        }
        section("<b>Example</b>") {
            paragraph "<pre style='font-size:0.85em;background:#f4f4f4;padding:6px'>" +
                      "curl -s '${localUri}/status?access_token=${token ?: 'TOKEN'}'\n" +
                      "curl -X POST '${localUri}/run?access_token=${token ?: 'TOKEN'}'\n" +
                      "curl -X POST '${localUri}/delay?access_token=${token ?: 'TOKEN'}&hours=24'" +
                      "</pre>"
        }
    }
}

def aboutPage() {
    dynamicPage(name: "aboutPage", title: "About") {
        section {
            paragraph "<h2>Zooz Sprinkler Scheduler</h2>" +
                      "<b>Version:</b> ${getAppVersion()}<br>" +
                      "<b>Author:</b> RamSet<br>" +
                      "<b>License:</b> Apache License, Version 2.0<br>" +
                      "<b>Source:</b> <a href='https://github.com/RamSet/hubitat'>github.com/RamSet/hubitat</a><br>" +
                      "<b>Import URL:</b> <code>https://raw.githubusercontent.com/RamSet/hubitat/main/zooz-sprinkler-scheduler.groovy</code>"
        }
        section("<b>What this is</b>") {
            paragraph "A Hubitat app for running sprinkler zones via Zooz ZEN16 800LR multi-relay controllers — or any Hubitat device exposing the Switch capability. Hardware-agnostic, multi-instance, with Spruce-style weather adaptation, per-zone moisture-aware watering, restrictions (quiet hours / mode / HSM), pause-and-resume from external sensors, hub-independent hardware watchdog via Z-Wave parameters, full external JSON/HTML/iCal API, and granular templated notifications with Pushover support."
        }
        section("<b>Changelog</b>") {
            paragraph "<b>v0.5</b> — Smart skips (frost/cold/wind via Open-Meteo), per-zone fertilizer mode (one-shot short-burst-with-soak), auto-stagger across instances via shared coordination switch, HTML dashboard endpoint, iCalendar export endpoint, About page."
            paragraph "<b>v0.4</b> — Per-zone moisture modes (off / skip / earlyStop / adapt / learn), pre/post moisture capture with rolling rate, learned-rate prediction, Moisture page with per-zone history table."
            paragraph "<b>v0.3</b> — Per-event notification system with 25 named events + templates + Pushover. Spruce-style weekly-minutes runtime. Per-zone weekly budget cap. Today's forecast preview on weather page."
            paragraph "<b>v0.2</b> — Quiet hours, mode/HSM pause, pre-run lead notification, 7-day schedule preview, OAuth JSON API, backup/restore JSON, ZEN16 reachability watchdog."
            paragraph "<b>v0.1</b> — Initial release: zones, schedule, weather (Open-Meteo), rain sensors, pause sensors with true pause-and-resume, pump/master, hardware safety push, history, test runs, diagnostics, dashboard child device."
        }
        section("<b>Acknowledgements</b>") {
            paragraph "Inspired by the Plaid Systems <b>Spruce Scheduler</b> — same overall approach (per-zone plant/sprinkler/soil typing, weather-aware seasonal adjust, rain delay, optional pump/master, moisture sensors)."
            paragraph "• <a href='https://www.support.getzooz.com/kb/article/371'>Zooz KB #371 — sprinkler use on Hubitat</a><br>" +
                      "• <a href='https://www.support.getzooz.com/kb/article/376'>Zooz KB #376 — advanced settings</a><br>" +
                      "• Weather by <a href='https://open-meteo.com'>Open-Meteo</a> (no API key required)<br>" +
                      "• Icons by <a href='https://openmoji.org'>OpenMoji</a> (CC-BY-SA 4.0)"
        }
        section("<b>License (Apache 2.0)</b>") {
            paragraph "<pre style='font-size:0.8em'>Licensed under the Apache License, Version 2.0 (the \"License\"); you may not use this file except in compliance with the License. You may obtain a copy of the License at\n\n  http://www.apache.org/licenses/LICENSE-2.0\n\nUnless required by applicable law or agreed to in writing, software distributed under the License is distributed on an \"AS IS\" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.</pre>"
        }
    }
}

def moisturePage() {
    dynamicPage(name: "moisturePage", title: "Moisture learning") {
        section {
            paragraph "Per-zone moisture status. The scheduler captures a pre-moisture reading at zone start and a post-moisture reading at zone end. From the pre/post delta and the actual run minutes, it derives a soil-response rate (% per minute). In <b>learn</b> mode, future runs use this rate to predict the runtime needed to lift moisture from current level up to target."
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
            section("<b>${i}. ${label}</b>") {
                paragraph "<i>Mode:</i> <b>${mode}</b> · " +
                          "<i>Current:</i> <b>${cur != null ? "${cur}%" : 'n/a'}</b> · " +
                          "<i>Target:</i> ${target}% · <i>Min:</i> ${minPct}% · " +
                          "<i>Status:</i> ${status}"
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
                    paragraph "<i>Weighted-average rate (recent runs weigh more): <b>${rate ?: 'insufficient data'}</b></i>"
                    input name: "btnClearMoisture_${i}", type: "button", title: "Clear learning history for this zone"
                } else {
                    paragraph "<i>No learning records yet. Run the zone at least once with a moisture sensor in any mode to start collecting data.</i>"
                }
            }
        }
        if (!any) {
            section {
                paragraph "<i>No zones have a moisture sensor assigned. Pick one on each zone's detail page to enable moisture-aware watering.</i>"
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
        section("<b>Where to send notifications</b>") {
            input name: "notifyDevices", type: "capability.notification",
                  title: "Notification devices (any Hubitat capability.notification)",
                  multiple: true, required: false
        }
        section("<b>Pushover (optional)</b>") {
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
                paragraph "<i>If your Pushover driver supports the bracketed prefix format <code>[priority|sound|message]</code>, the app will use it; otherwise messages go through as plain deviceNotification.</i>"
            }
        }
        section("<b>Per-event configuration</b>") {
            href name: "notifyEventsPage", title: "Configure per-event toggles and custom messages →",
                 page: "notifyEventsPage",
                 description: notifyEventsSummaryString()
        }
        section("<b>Test</b>") {
            input name: "btnNotifyTest", type: "button",
                  title: "Send a test notification to every selected channel"
        }
    }
}

def notifyEventsPage() {
    dynamicPage(name: "notifyEventsPage", title: "Per-event notifications") {
        section {
            paragraph "Every notification the scheduler can emit is listed below, grouped by category. Toggle individual events on/off. To customise the wording, paste your own message in the override field — leave blank to use the default. Custom messages support these template variables (any missing variable renders as empty):"
            paragraph "<code>\${app}</code> · <code>\${zone}</code> · <code>\${duration}</code> · <code>\${remaining}</code> · " +
                      "<code>\${reason}</code> · <code>\${sensor}</code> · <code>\${cycle}</code> · <code>\${totalCycles}</code> · " +
                      "<code>\${count}</code> · <code>\${minutes}</code> · <code>\${hours}</code> · <code>\${detail}</code> · " +
                      "<code>\${planSize}</code> · <code>\${seasonalMult}</code> · <code>\${until}</code> · " +
                      "<code>\${mode}</code> · <code>\${hsm}</code> · <code>\${delay}</code>"
        }
        // Group events by section preserving NOTIFY_EVENTS insertion order
        Map<String, List<Map>> grouped = [:]
        NOTIFY_EVENTS.each { key, meta ->
            String sec = meta.section as String
            grouped[sec] = (grouped[sec] ?: []) + [[key: key, meta: meta]]
        }
        grouped.each { sec, items ->
            section("<b>${sec}</b>") {
                items.each { item ->
                    String key = item.key
                    Map meta = item.meta
                    String safeKey = key.replace(".", "_").replace("-", "_")
                    boolean defOn = !(meta.defaultOff == true)
                    input name: "notifyEvent_${safeKey}", type: "bool",
                          title: "<b>${key}</b> — ${escapeForUi(meta.default as String)}",
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

    if (settings.scheduleEnabled && settings.scheduleStartTime && settings.scheduleDays) {
        ["scheduleStartTime", "scheduleStartTime2", "scheduleStartTime3"].eachWithIndex { key, i ->
            String t = settings[key]
            if (t) {
                String cron = buildCron(t, settings.scheduleDays)
                if (descTextEnable) log.info "${app.label}: window ${i + 1} scheduled at cron='${cron}'"
                schedule(cron, "runSchedule")
                // Pre-run lead notification (N minutes before the window)
                Integer lead = (settings.preRunLeadMinutes ?: 0) as int
                if (lead > 0) {
                    String leadCron = buildLeadCron(t, settings.scheduleDays, lead)
                    if (leadCron) {
                        schedule(leadCron, "preRunNotify")
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
    runEvery1Hour("publishDashboardState")
    runEvery1Hour("zen16Watchdog")
    // Weekly-budget rollover every Monday 00:01 local
    schedule("0 1 0 ? * 2", "rolloverWeeklyBudget")
    // Lazy check at every wake: if we crossed a week boundary while powered off
    rolloverWeeklyBudgetIfNeeded()
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

    // Subscribe to rain sensors so a "wet" event during a run can stop it.
    if (settings.rainSensorWaterDevices) {
        subscribe(settings.rainSensorWaterDevices, "water", "rainSensorEvent")
    }
    // Subscribe to pause sensors (contacts + switches) for mid-run safety stop.
    if (settings.pauseContacts) subscribe(settings.pauseContacts, "contact", "pauseSensorEvent")
    if (settings.pauseSwitches) subscribe(settings.pauseSwitches, "switch",  "pauseSensorEvent")
}

// ---- Mid-run safety event handlers ----

def rainSensorEvent(evt) {
    if (evt?.value != "wet") return
    if (descTextEnable) log.info "${app.label}: ${evt.displayName} → wet"
    if (state.running && settings.rainSensorStopRunning != false) {
        log.warn "${app.label}: rain detected mid-run (${evt.displayName}) — stopping all zones"
        notify("rain.mid.stop", [sensor: evt.displayName, reason: "rain sensor wet"])
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
                notify("pause.activate", [zone: "schedule", remaining: 0, reason: "${evt?.displayName} → ${evt?.value} (stop mode)"])
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
            notify("pause.clear", [delay: delaySec])
            runIn(Math.max(1, delaySec), "doResumeAfterPause")
        }
    }
}

// ---- True pause / resume of an in-progress watering run ----

private void pauseRunningSchedule(String reason) {
    if (!state.running) return
    Integer zid = (state.currentZoneId ?: 0) as int
    if (zid == 0) {
        // Between zones; just stop and remember plan position.
        log.info "${app.label}: pause requested between zones — holding plan"
    } else {
        // Compute remaining seconds in the current cycle.
        Long startMs = (state.currentPhaseStartMs ?: now()) as long
        Integer phaseDurSec = (state.currentPhaseDurationSec ?: 0) as int
        long elapsedMs = now() - startMs
        long remainingMs = Math.max(0L, (phaseDurSec * 1000L) - elapsedMs)
        Integer remainingSec = (remainingMs / 1000L) as int

        state.pausedRemainingSec = remainingSec

        def sw = settings."zone${zid}Switch"
        if (sw) try { sw.off() } catch (e) { log.warn "pause: ${e.message}" }
        log.warn "${app.label}: PAUSED at ${settings."zone${zid}Name" ?: "zone ${zid}"} — ${remainingSec}s remaining in cycle ${((state.currentZoneCycleIdx ?: 0) as int) + 1}/${state.currentZoneCycles}. Reason: ${reason}"
        notify("pause.activate", [zone: (settings."zone${zid}Name" ?: "Zone ${zid}"), remaining: remainingSec, reason: reason])
    }

    // Cancel any pending phase / soak / next-zone callbacks.
    unschedule("startNextZone")
    unschedule("zoneCyclePhaseDone")
    unschedule("zoneCycleResume")
    unschedule("doResumeAfterPause")

    state.paused = true
    state.running = false  // schedule is no longer actively running
    state.pausedReason = reason
}

def doResumeAfterPause() {
    if (!state.paused) return
    if (externalPauseActive()) {
        log.info "${app.label}: resume aborted — pause sensor activated again during resume delay"
        return
    }
    Integer zid = (state.currentZoneId ?: 0) as int
    Integer remainingSec = (state.pausedRemainingSec ?: 0) as int
    state.paused = false
    state.running = true

    if (zid == 0 || remainingSec <= 0) {
        // Resume by moving to the next zone in the plan.
        if (descTextEnable) log.info "${app.label}: resuming with next zone in plan"
        runIn(1, "startNextZone")
        return
    }

    String zname = settings."zone${zid}Name" ?: "Zone ${zid}"
    def sw = settings."zone${zid}Switch"
    if (sw) try { sw.on() } catch (e) { log.warn "resume: ${e.message}" }
    log.info "${app.label}: RESUMED ${zname} — ${remainingSec}s left in this cycle"
    notify("pause.resume", [zone: zname, remaining: remainingSec])
    state.currentPhaseStartMs = now()
    state.currentPhaseDurationSec = remainingSec
    runIn(Math.max(1, remainingSec), "zoneCyclePhaseDone")
}

def logsOff() {
    log.warn "${app.label}: debug logging disabled"
    app.updateSetting("debugOutput", [value: "false", type: "bool"])
}

// =========================================================================
// Cron from time + days-of-week enum list
// =========================================================================

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

// =========================================================================
// Schedule entry — fires at the configured time
// =========================================================================

def runSchedule(Map opts = [:]) {
    boolean manual = (opts?.manual == true)
    if (isPaused()) {
        if (descTextEnable) log.info "${app.label}: manually paused — skipping run"
        notify("skip.manual")
        recordRunSkip("manual pause")
        return
    }
    if (state.skipNextRun) {
        log.info "${app.label}: skip-next-run flag consumed"
        state.skipNextRun = false
        recordRunSkip("skip-next requested")
        notify("skip.next")
        return
    }
    Long rd = (state.forcedRainDelayUntilMs ?: 0L) as long
    if (rd > now()) {
        String until = new Date(rd).format("yyyy-MM-dd HH:mm", location?.timeZone ?: TimeZone.getDefault())
        log.info "${app.label}: forced rain delay active until ${until}"
        recordRunSkip("forced rain delay until ${until}")
        notify("skip.forced", [until: until])
        return
    }
    if (quietHoursActive()) {
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
    if (externalPauseActive()) {
        String who = externalPauseReason()
        log.info "${app.label}: skipped by pause sensor (${who})"
        notify("skip.pause", [sensor: who])
        recordRunSkip("pause sensor active (${who})")
        return
    }
    if (rainSensorWet()) {
        String who = rainSensorReason()
        log.info "${app.label}: skipped by binary rain sensor (${who})"
        notify("skip.rain.sensor", [sensor: who])
        recordRunSkip("rain sensor wet (${who})")
        return
    }
    // Smart-skip: frost / cold / wind from forecast
    Map smartSkipResult = smartSkipCheck()
    if (smartSkipResult?.skip) {
        String reason = smartSkipResult.reason as String
        log.info "${app.label}: smart-skip — ${reason}"
        recordRunSkip(reason)
        notify(smartSkipResult.eventKey as String, smartSkipResult.ctx as Map)
        return
    }
    if (state.running) {
        log.warn "${app.label}: previous run still active (zone ${state.currentZoneIdx}). Skipping new trigger."
        return
    }
    // Auto-stagger: if another instance holds the coordination switch, defer.
    if (settings.coordSwitch && settings.coordSwitch.currentValue("switch") == "on") {
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

    // Weather gate
    Map weather = settings.rainDelayEnabled || settings.seasonalEnabled ? fetchWeather() : null
    if (settings.rainDelayEnabled && weather && shouldSkipForRain(weather)) {
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
    if (plan.isEmpty()) {
        log.info "${app.label}: no zones to run after filtering"
        return
    }

    // Compute seasonal multiplier once per run
    BigDecimal seasonalMult = (settings.seasonalEnabled && weather) ? computeSeasonalMultiplier(weather) : 1.0
    state.seasonalMult = seasonalMult.toString()

    if (descTextEnable) {
        log.info "${app.label}: starting run — plan=${plan}, seasonal=×${seasonalMult}"
    }
    notify("schedule.start", [planSize: plan.size(), seasonalMult: seasonalMult])

    state.zonesPlan = plan
    state.currentZoneIdx = 0
    state.running = true
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
    runIn(perCycleMin * 60, "zoneCyclePhaseDone")
}

def zoneCyclePhaseDone() {
    if (!state.running) return
    Integer zid = state.currentZoneId as int
    def sw = settings."zone${zid}Switch"
    String zname = settings."zone${zid}Name" ?: "Zone ${zid}"
    if (sw) sw.off()

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
        runIn(betweenSec, "startNextZone")
    } else {
        // Soak then resume same zone
        if (descTextEnable) log.info "${app.label}: ${zname} — soak ${state.currentZoneSoakMin}m (cycle ${cycleIdx + 1}/${cycles})"
        Integer soakMin = state.currentZoneSoakMin as int
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
        runIn(perCycleMin * 60, "zoneCyclePhaseDone")
    }
}

def finishRun() {
    if (descTextEnable) log.info "${app.label}: schedule finished"
    recordRunFinish("completed")
    state.running = false
    state.currentZoneIdx = 0
    Integer postSec = (settings.pumpSwitch && settings.pumpPostSec) ? (settings.pumpPostSec as int) : 0
    runIn(postSec, "stopPumpIfNeeded")
    // Release the shared auto-stagger coordination lock
    if (settings.coordSwitch) {
        try { settings.coordSwitch.off() }
        catch (e) { log.warn "coordSwitch.off(): ${e.message}" }
    }
    notify("schedule.finish")
    publishDashboardState()
}

def stopAllZones() {
    log.warn "${app.label}: stop-all invoked"
    unschedule("startNextZone")
    unschedule("zoneCyclePhaseDone")
    unschedule("zoneCycleResume")
    unschedule("doResumeAfterPause")
    Integer n = (settings.zoneCountPref ?: 0) as int
    for (int i = 1; i <= n; i++) {
        def sw = settings."zone${i}Switch"
        if (sw) try { sw.off() } catch (e) { log.warn "stop zone ${i}: ${e.message}" }
        unsubscribeZoneMoisture(i)
    }
    state.running = false
    state.paused = false
    state.pausedRemainingSec = 0
    state.currentZoneIdx = 0
    stopPumpIfNeeded()
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
    if (!om || !cachedAt || (nowMs - cachedAt) > (15L * 60L * 1000L)) {
        om = omFetch()
        if (om) {
            state.__omCacheData = om
            state.__omCacheAt = nowMs
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
            temperature_unit: "fahrenheit",
            precipitation_unit: "inch",
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
    // Baseline: at 70°F we run baseline. Each 10°F above adds 15%, below subtracts 10%.
    float pctDelta = (avg - 70.0f) * (avg > 70.0f ? 1.5f : 1.0f)
    BigDecimal cap = ((settings.seasonalMaxPct ?: 50) as BigDecimal) / 100.0
    BigDecimal scale = (1.0 + (pctDelta / 100.0)).setScale(2, BigDecimal.ROUND_HALF_UP)
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
        String prio = settings."notifyPriority_${safeKey}" ?: settings.pushoverDefaultPriority ?: "0 normal"
        if (key == "error" && settings.pushoverEmergencyOnError) prio = "2 emergency"
        String sound = settings.pushoverDefaultSound
        sendPushover(msg, prio, sound)
    }
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
    def devs = settings.rainSensorWaterDevices
    if (!devs) return false
    return devs.any { it.currentValue("water") == "wet" }
}

private String rainSensorReason() {
    def devs = settings.rainSensorWaterDevices ?: []
    def wet = devs.findAll { it.currentValue("water") == "wet" }
    return wet*.displayName.join(", ") ?: "unknown"
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

private void recordRunStart(List plan, BigDecimal seasonalMult) {
    state.currentRunRecord = [
        startedAt: nowString(),
        startedMs: now(),
        plan:      plan,
        seasonal:  seasonalMult.toString(),
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

def pushHardwareSafety() {
    def parents = settings.hwZen16Parents
    if (!parents) {
        log.warn "${app.label}: no ZEN16 parents picked — nothing to push"
        state.hwLastPushSummary = "No parents selected"
        return
    }
    Integer mins = (settings.hwAutoOffMinutes ?: ((settings.scheduleMaxRunMinutes ?: 60) as int + 5)) as int
    int ok = 0, fail = 0
    List<String> log_ = []
    parents.each { dev ->
        if (!dev.hasCommand("setParameter")) {
            log_ << "${dev.displayName}: setParameter() unsupported"
            fail++
            return
        }
        try {
            // Timer unit = minutes (P15, P17, P19)
            dev.setParameter(15, 1, 0)
            dev.setParameter(17, 1, 0)
            dev.setParameter(19, 1, 0)
            // Auto-off timer for R1/R2/R3 (P6, P8, P10)
            dev.setParameter(6,  4, mins)
            dev.setParameter(8,  4, mins)
            dev.setParameter(10, 4, mins)
            // Power-fail state = OFF (P1) for safety
            if (settings.hwPowerFailOff != false) dev.setParameter(1, 1, 0)
            // DC motor interlock = OFF (P24)
            if (settings.hwForceDcMotorOff != false) dev.setParameter(24, 1, 0)
            log_ << "${dev.displayName}: pushed P6/P8/P10=${mins}min, P15/17/19=min, P1=off, P24=off"
            ok++
        } catch (e) {
            log_ << "${dev.displayName}: FAILED — ${e.message}"
            fail++
        }
    }
    String summary = "Pushed to ${ok} controller(s), ${fail} failed @ ${nowString()}\n" + log_.join("\n")
    state.hwLastPushSummary = summary
    log.info "${app.label}: hardware safety push — ${ok} ok, ${fail} failed (${mins}min auto-off)"
    notify("hardware.push", [minutes: mins, count: ok])
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
    notify("test.run", [zone: label, duration: "${secs}s"])
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
    if (!settings.scheduleEnabled || !settings.scheduleDays || !settings.scheduleStartTime) return "schedule disabled"
    // Hubitat schedule list isn't directly readable from app context; just show
    // configured days + first window. Good enough for the dashboard.
    String t = settings.scheduleStartTime
    if (t?.contains("T")) t = t.tokenize("T")[1].substring(0,5)
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
        return [skip: true, reason: "frost: low ${low}°F < ${settings.smartSkipFrostF}°F",
                eventKey: "skip.frost", ctx: [tempF: low, threshold: settings.smartSkipFrostF]]
    }
    if (wantCold && high != null && (high as float) < (settings.smartSkipColdHighF as float)) {
        return [skip: true, reason: "cold: high ${high}°F < ${settings.smartSkipColdHighF}°F",
                eventKey: "skip.cold", ctx: [tempF: high, threshold: settings.smartSkipColdHighF]]
    }
    if (wantWind && wind != null && (wind as float) > (settings.smartSkipWindMph as float)) {
        return [skip: true, reason: "wind: max ${wind} mph > ${settings.smartSkipWindMph} mph",
                eventKey: "skip.wind", ctx: [windMph: wind, threshold: settings.smartSkipWindMph]]
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
    String dur = "${(int)(elapsedSec/60)}m${String.format('%02d', (int)(elapsedSec%60))}s"
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
    if (!settings.scheduleEnabled || !settings.scheduleStartTime || !settings.scheduleDays) {
        return "<i>Schedule disabled — nothing to preview.</i>"
    }
    Map<Integer, String> dowName = [1:"Sun",2:"Mon",3:"Tue",4:"Wed",5:"Thu",6:"Fri",7:"Sat"]
    Map<String, Integer> dowNum  = [SUN:1, MON:2, TUE:3, WED:4, THU:5, FRI:6, SAT:7]
    Set<Integer> wantDow = (settings.scheduleDays as List).collect { dowNum[it] } as Set
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
        boolean runsToday = wantDow0.contains(dow)
        String windowsStr = runsToday ? windows.join(", ") : "—"
        List<String> blockers = []
        if (!runsToday) blockers << "not a watering day"
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
        forcedRainDelayUntil: ((state.forcedRainDelayUntilMs ?: 0L) as long > now())
                              ? new Date((state.forcedRainDelayUntilMs ?: 0L) as long).format("yyyy-MM-dd HH:mm")
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
    if (!settings.scheduleEnabled || !settings.scheduleDays || !settings.scheduleStartTime) {
        sb << "END:VCALENDAR\r\n"
        return sb.toString()
    }
    Map<String, Integer> dowNum = [SUN:1, MON:2, TUE:3, WED:4, THU:5, FRI:6, SAT:7]
    Set<Integer> wantDow = (settings.scheduleDays as List).collect { dowNum[it] }.findAll { it != null } as Set
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
        if (wantDow.contains(c.get(Calendar.DAY_OF_WEEK))) {
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
        scheduleEnabled:        settings.scheduleEnabled,
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

def zen16Watchdog() {
    def parents = settings.hwZen16Parents
    if (!parents) return
    long now = now()
    long staleMs = ((settings.zen16StaleHours ?: 6) as int) * 3600L * 1000L
    parents.each { dev ->
        try {
            // Hubitat exposes lastActivity as Date for many drivers; fall back to currentValue("switch") time.
            Long last = null
            if (dev.respondsTo("getLastActivity")) {
                Date la = dev.getLastActivity()
                if (la) last = la.getTime()
            }
            if (last && (now - last) > staleMs) {
                long hrs = (now - last) / 3600000L
                log.warn "${app.label}: ZEN16 '${dev.displayName}' has been quiet for ${hrs}h"
                notify("watchdog.stale", [sensor: dev.displayName, hours: hrs])
            }
        } catch (e) {
            // ignore — driver may not expose lastActivity
        }
    }
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
    Integer mins = (settings."zone${i}RunMinutes" ?: 10) as int
    String plant = settings."zone${i}Plant" ?: ""
    String enabled = (settings."zone${i}Enabled" == false) ? " — DISABLED" : ""
    String parts = "${mins}m · ${plant} · ${dev}"
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
    def devs = settings.rainSensorWaterDevices
    if (!devs) return "No rain sensors configured"
    int wet = devs.count { it.currentValue("water") == "wet" }
    return "${devs.size()} sensor(s) — ${wet} currently wet"
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
    Integer mins = (settings.hwAutoOffMinutes ?: ((settings.scheduleMaxRunMinutes ?: 60) as int + 5)) as int
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
            runIn(1, "runSchedule")
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
