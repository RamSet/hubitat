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
    singleInstance: false
)

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
    page(name: "notificationPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "<b>Zooz Sprinkler Scheduler</b> — ${getAppVersion()}",
                install: true, uninstall: true) {
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
            href name: "notificationPage", title: "Notifications", page: "notificationPage",
                 image: openmoji("1F4E2"),
                 description: notificationSummaryString()
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
    }
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
            input name: "scheduleStartTime", type: "time",
                  title: "Start time",
                  required: true
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
        section("<b>Manual run failsafe</b>") {
            input name: "scheduleMaxRunMinutes", type: "number",
                  title: "Maximum single zone run time (minutes) — failsafe to prevent stuck-on relays",
                  description: "<i>The driver-side watchdog will force the relay off after this many minutes regardless of schedule state.</i>",
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
        String cron = buildCron(settings.scheduleStartTime, settings.scheduleDays)
        if (descTextEnable) log.info "${app.label}: scheduling at cron='${cron}'"
        schedule(cron, "runSchedule")
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

def runSchedule() {
    if (isPaused()) {
        if (descTextEnable) log.info "${app.label}: manually paused — skipping run"
        notify("warning", "${app.label} paused — skipped scheduled run")
        return
    }
    if (externalPauseActive()) {
        String who = externalPauseReason()
        log.info "${app.label}: skipped by pause sensor (${who})"
        if (settings.notifyOnSkip) notify("pause-skip", "${app.label}: skipped — pause sensor active (${who})")
        return
    }
    if (rainSensorWet()) {
        String who = rainSensorReason()
        log.info "${app.label}: skipped by binary rain sensor (${who})"
        if (settings.notifyOnSkip) notify("rain-skip", "${app.label}: skipped — rain sensor wet (${who})")
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
    startPumpIfNeeded()
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
    state.running = false
    state.currentZoneIdx = 0
    Integer postSec = (settings.pumpSwitch && settings.pumpPostSec) ? (settings.pumpPostSec as int) : 0
    runIn(postSec, "stopPumpIfNeeded")
    if (settings.notifyOnFinish) notify("finish", "${app.label}: schedule complete")
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
        default:
            log.warn "unknown button: ${btn}"
    }
}
