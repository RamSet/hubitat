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
    path("/status") { action: [GET: "apiStatus"] }
    path("/run")    { action: [POST: "apiRun"]   }
    path("/stop")   { action: [POST: "apiStop"]  }
    path("/skip")   { action: [POST: "apiSkip"]  }
    path("/delay")  { action: [POST: "apiDelay"] }
}

String getAppVersion() { return "v0.1.0 (2026-06)" }
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
            href name: "diagnosticsPage", title: "Diagnostics & test runs", page: "diagnosticsPage",
                 image: openmoji("1F50D"),
                 description: diagnosticsSummaryString()
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
            input name: "zone${zid}RunMinutes", type: "number",
                  title: "Base run time per cycle (minutes)",
                  description: "<i>Seasonal weather adjust will scale this if enabled in Weather settings.</i>",
                  range: "1..240", defaultValue: 10, required: true
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
        section("<b>Optional moisture sensor</b>") {
            input name: "zone${zid}MoistureSensor", type: "capability.relativeHumidityMeasurement",
                  title: "Moisture sensor (skip zone if reading is above target)",
                  required: false, multiple: false
            input name: "zone${zid}MoistureTarget", type: "number",
                  title: "Skip if moisture reading is above this %",
                  range: "0..100", defaultValue: 50, required: false
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
    }
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
                          "<b>GET</b>  <code>${localUri}/status?access_token=${token}</code><br>" +
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

def notificationPage() {
    dynamicPage(name: "notificationPage", title: "Notifications") {
        section {
            input name: "notifyDevices", type: "capability.notification",
                  title: "Notification devices",
                  multiple: true, required: false
            input name: "notifyOnStart",  type: "bool", title: "Notify on schedule start", defaultValue: false
            input name: "notifyOnFinish", type: "bool", title: "Notify on schedule finish", defaultValue: true
            input name: "notifyOnSkip",   type: "bool", title: "Notify on rain-skip", defaultValue: true
            input name: "notifyOnError",  type: "bool", title: "Notify on errors", defaultValue: true
        }
    }
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
}

// ---- Restriction-edge handlers ----

def quietHoursStart() {
    if (!state.running) return
    if (settings.quietStopInProgress == false) return
    log.warn "${app.label}: quiet hours starting — stopping in-progress run"
    notify("quiet", "${app.label}: quiet hours — stopped in-progress run")
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
        notify("rain-stop", "${app.label}: rain detected (${evt.displayName}) — stopped mid-run")
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
                notify("pause", "${app.label}: paused by ${evt?.displayName} (${evt?.value}) — schedule stopped")
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
            notify("pause-clear", "${app.label}: pause sensors clear — resuming in ${delaySec}s")
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
        notify("pause", "${app.label}: paused at ${settings."zone${zid}Name" ?: "zone ${zid}"} (${remainingSec}s remaining)")
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
    notify("resume", "${app.label}: resumed ${zname} (${remainingSec}s left)")
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
        notify("warning", "${app.label} paused — skipped scheduled run")
        recordRunSkip("manual pause")
        return
    }
    if (state.skipNextRun) {
        log.info "${app.label}: skip-next-run flag consumed"
        state.skipNextRun = false
        recordRunSkip("skip-next requested")
        notify("skip", "${app.label}: skipped (user requested skip-next)")
        return
    }
    Long rd = (state.forcedRainDelayUntilMs ?: 0L) as long
    if (rd > now()) {
        String until = new Date(rd).format("yyyy-MM-dd HH:mm", location?.timeZone ?: TimeZone.getDefault())
        log.info "${app.label}: forced rain delay active until ${until}"
        recordRunSkip("forced rain delay until ${until}")
        if (settings.notifyOnSkip) notify("rain-delay", "${app.label}: forced rain delay until ${until}")
        return
    }
    if (quietHoursActive()) {
        log.info "${app.label}: skipped — in quiet hours"
        recordRunSkip("quiet hours")
        if (settings.notifyOnSkip) notify("quiet", "${app.label}: skipped — quiet hours active")
        return
    }
    if (modeShouldPause()) {
        log.info "${app.label}: skipped — Hubitat mode is ${location?.mode}"
        recordRunSkip("mode=${location?.mode}")
        if (settings.notifyOnSkip) notify("mode", "${app.label}: skipped — mode is ${location?.mode}")
        return
    }
    if (hsmShouldPause()) {
        log.info "${app.label}: skipped — HSM is ${location?.hsmStatus}"
        recordRunSkip("HSM=${location?.hsmStatus}")
        if (settings.notifyOnSkip) notify("hsm", "${app.label}: skipped — HSM is ${location?.hsmStatus}")
        return
    }
    if (externalPauseActive()) {
        String who = externalPauseReason()
        log.info "${app.label}: skipped by pause sensor (${who})"
        if (settings.notifyOnSkip) notify("pause-skip", "${app.label}: skipped — pause sensor active (${who})")
        recordRunSkip("pause sensor active (${who})")
        return
    }
    if (rainSensorWet()) {
        String who = rainSensorReason()
        log.info "${app.label}: skipped by binary rain sensor (${who})"
        if (settings.notifyOnSkip) notify("rain-skip", "${app.label}: skipped — rain sensor wet (${who})")
        recordRunSkip("rain sensor wet (${who})")
        return
    }
    if (state.running) {
        log.warn "${app.label}: previous run still active (zone ${state.currentZoneIdx}). Skipping new trigger."
        return
    }
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
        if (settings.notifyOnSkip) notify("rain-skip", "${app.label}: skipped — ${reason}")
        return
    }

    // Build the run plan
    List<Integer> plan = []
    for (int i = 1; i <= n; i++) {
        if (settings."zone${i}Enabled" != false && settings."zone${i}Switch") {
            // Moisture gate per-zone
            def moist = settings."zone${i}MoistureSensor"
            Integer target = (settings."zone${i}MoistureTarget" ?: 50) as int
            if (moist) {
                def v = moist.currentValue("humidity") ?: moist.currentValue("moisture")
                if (v != null && (v as float) > target) {
                    if (descTextEnable) log.info "${app.label}: zone ${i} skipped (moisture ${v}% > ${target}%)"
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
    if (settings.notifyOnStart) notify("start", "${app.label}: starting (${plan.size()} zones, seasonal ×${seasonalMult})")

    state.zonesPlan = plan
    state.currentZoneIdx = 0
    state.running = true
    recordRunStart(plan, seasonalMult)
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

    Integer baseMin = (settings."zone${zid}RunMinutes" ?: 10) as int
    BigDecimal mult = (state.seasonalMult ?: "1.0") as BigDecimal
    Integer adjMin = Math.max(1, Math.min((settings.scheduleMaxRunMinutes ?: 60) as int,
                                          Math.round((baseMin * mult).floatValue())))

    Integer cycles = ((settings."zone${zid}CycleSoak" ?: "1") as int)
    Integer perCycleMin = Math.max(1, (adjMin / cycles) as int)
    Integer soakMin     = (settings."zone${zid}SoakMinutes" ?: 10) as int

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
        Integer totalSec = ((state.currentZoneTotalAdjMin ?: 0) as int) * 60
        recordZoneRun(zid, totalSec, "ok")
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
    if (settings.notifyOnFinish) notify("finish", "${app.label}: schedule complete")
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
    }
    state.running = false
    state.paused = false
    state.pausedRemainingSec = 0
    state.currentZoneIdx = 0
    stopPumpIfNeeded()
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
            daily: "temperature_2m_max,precipitation_sum,precipitation_probability_mean,sunrise,sunset",
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

private void notify(String type, String message) {
    def devs = settings.notifyDevices
    if (!devs) return
    try {
        devs.each { it.deviceNotification("[${type}] ${message}") }
    } catch (e) {
        log.warn "notify(${type}): ${e.message}"
    }
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
    notify("rain-delay", "${app.label}: forced rain delay until ${untilStr}")
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
    notify("hardware-push", "${app.label}: ZEN16 watchdog set to ${mins}min on ${ok} controller(s)")
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
    notify("test", "${app.label}: testing ${label} for ${secs}s")
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
                notify("watchdog", "${app.label}: ${dev.displayName} unreachable for ${hrs}h")
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
    if (!settings.notifyDevices) return "No notification devices"
    List flags = []
    if (settings.notifyOnStart)  flags << "start"
    if (settings.notifyOnFinish) flags << "finish"
    if (settings.notifyOnSkip)   flags << "rain-skip"
    if (settings.notifyOnError)  flags << "errors"
    return "${settings.notifyDevices.size()} device(s) · ${flags.join(', ')}"
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
        default:
            // Per-zone test buttons: "btnTestZone_<N>"
            if (btn?.startsWith("btnTestZone_")) {
                Integer zid = btn.replaceFirst("btnTestZone_", "").toInteger()
                testZoneRun(zid)
            } else {
                log.warn "unknown button: ${btn}"
            }
    }
}
