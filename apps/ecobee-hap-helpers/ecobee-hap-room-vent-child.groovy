/**
 *  Local Ecobee Room Vent  (child app)
 *
 *  Per-room vent control following the local Ecobee HAP Thermostat.
 *  While the HVAC is heating/cooling, opens the room's vent(s) proportionally
 *  toward (thermostat setpoint + per-room offset), closing to a minimum floor
 *  once the room is satisfied. Fully offline.
 *
 *  Child of: Local Ecobee Helpers (RamSet)
 *
 *  Author: RamSet
 *  Version: 1.1.1 (2026-09-28)
 *  Version history:
 *    1.1.1 - The room-temperature pill is coloured by where the room sits against its target: blue below,
 *            green within 0.5°, orange above. While idle the target is the setpoint of the thermostat mode
 *            (both edges in auto); with the thermostat off there is no target and the pill is grey.
 *    1.1.0 - Current-status block at the top of the page: HVAC state, room temperature vs target, the vent
 *            level the app wants, each vent's actual level, when it last evaluated and when the periodic
 *            re-check is due (flags a timer that has stopped, so a stalled helper is visible).
 *    1.0.0 - Initial release. Proportional vent control with adjustable periodic re-evaluation.
 *
 *  DISCLAIMER: Provided as-is, without warranty of any kind. You are solely
 *  responsible for the safe operation of your HVAC system and connected devices.
 *  Closing too many vents can damage your system — use at your own risk.
 */
definition(
    name:        "Local Ecobee Room Vent",
    namespace:   "RamSet",
    author:      "RamSet",
    description: "Per-room vent control following the local Ecobee HAP Thermostat.",
    category:    "Convenience",
    parent:      "RamSet:Local Ecobee Helpers",
    iconUrl:     "",
    iconX2Url:   "",
    importUrl:   "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/apps/ecobee-hap-helpers/ecobee-hap-room-vent-child.groovy"
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Room Vent", install: true, uninstall: true) {
        section("<b>Current status</b>") {
            paragraph currentStatus()
        }
        section("Naming & sensors") {
            label title: "Name for this Room Vent", required: true
            input "tempSensors", "capability.temperatureMeasurement", title: "Room temperature sensor(s)", multiple: true, required: true, submitOnChange: true
            if (tempSensors) paragraph "Current room temperature: <b>${avgTemp()}°</b>"
        }
        section("Vents") {
            paragraph "Modulating vents (Keen / dimmer) are set to a % open. On/off switch vents are turned on while conditioning, off otherwise."
            input "ventLevels",   "capability.switchLevel", title: "Modulating vent(s) — Keen / dimmer", multiple: true, required: false, submitOnChange: true
            input "ventSwitches", "capability.switch",      title: "On/off vent switch(es)",             multiple: true, required: false, submitOnChange: true
        }
        section("Targeting") {
            input "heatOffset", "decimal", title: "Heating setpoint offset (added to thermostat heat setpoint)", defaultValue: 0.0, required: true
            input "coolOffset", "decimal", title: "Cooling setpoint offset (added to thermostat cool setpoint)", defaultValue: 0.0, required: true
            input "band",       "decimal", title: "Proportional band — degrees from target at which the vent is fully open", defaultValue: 2.0, required: true
            input "alwaysAdjust", "bool",  title: "Always adjust (modulate on room temp even when the thermostat is idle)", defaultValue: false
            input "reEvalMinutes", "number", title: "Re-evaluation interval (minutes) — periodic safety re-check even when sensors are quiet", defaultValue: 5, range: "1..120", required: true
        }
        section("Minimum open floor") {
            input "floor", "number", title: "Per-vent minimum open % (0–100)", defaultValue: 10, range: "0..100", required: true, submitOnChange: true
            if ((floor ?: 0) < 10) {
                paragraph "<span style='color:red;font-weight:bold'>WARNING: floor is set to ${floor ?: 0}% — below the 10% safe minimum. This can dangerously restrict airflow.</span>"
            }
            paragraph disclaimer()
        }
    }
}

private String disclaimer() {
    "<b>Airflow warning.</b> Setting a vent below 10% (and especially to 0%) can dangerously restrict airflow. " +
    "When cooling, this can freeze the evaporator coil and ice the lines; when heating, it can overheat the heat exchanger, " +
    "trip the high-limit, and short-cycle; either way it strains the blower motor. Never let too many vents close at once. " +
    "Ensuring adequate open airflow is your responsibility — use at your own risk."
}

def installed() { initialize() }
def updated()   { unsubscribe(); unschedule(); initialize() }

def initialize() {
    def t = parent?.getThermostat()
    if (t) {
        subscribe(t, "thermostatOperatingState", evtHandler)
        subscribe(t, "heatingSetpoint", evtHandler)
        subscribe(t, "coolingSetpoint", evtHandler)
        subscribe(t, "thermostatMode",  evtHandler)
    }
    subscribe(tempSensors, "temperature", evtHandler)
    schedulePeriodic()
    evaluateVent()
}

private void schedulePeriodic() {
    int mins = Math.max(1, (reEvalMinutes ?: 5) as int)
    state.nextEvalMs = now() + mins * 60000L
    runIn(mins * 60, periodicEval)
}

def periodicEval() {
    schedulePeriodic()       // reschedule first so an eval error can't break the chain
    evaluateVent()
}

def evtHandler(evt) { evaluateVent() }

// reported to the parent for the system-wide airflow warning
Map ventReport() {
    [name: app.label, floor: (floor ?: 0) as Integer,
     ventCount: ((ventLevels?.size() ?: 0) + (ventSwitches?.size() ?: 0))]
}

private avgTemp() {
    def temps = tempSensors?.collect { it.currentValue("temperature") }?.findAll { it != null }
    if (!temps) return null
    return (temps.sum() / temps.size())
}

// One computation for both the control loop and the status page, so the page shows
// exactly what evaluateVent() would do.
private Map ventPlan() {
    def t = parent?.getThermostat()
    if (!t) return [ok: false, why: "no thermostat selected in the parent"]
    def room = avgTemp()
    if (room == null) return [ok: false, why: "no room temperature yet", thermostat: t]

    def opState = t.currentValue("thermostatOperatingState")
    String mode = t.currentValue("thermostatMode")
    double flr  = (floor ?: 0) as double
    double b    = (band ?: 2.0) as double
    if (b <= 0) b = 0.1

    boolean heating = opState in ["heating", "pending heat"]
    boolean cooling = opState in ["cooling", "pending cool"]
    def heatSp = t.currentValue("heatingSetpoint")
    def coolSp = t.currentValue("coolingSetpoint")
    double level
    def target = null
    String action

    if (heating || (alwaysAdjust && mode == "heat")) {
        target = ((heatSp as double) + (heatOffset ?: 0.0))
        level = scale((target as double) - (room as double), b, flr)   // room below target → open
        action = "heating"
    } else if (cooling || (alwaysAdjust && mode == "cool")) {
        target = ((coolSp as double) + (coolOffset ?: 0.0))
        level = scale((room as double) - (target as double), b, flr)   // room above target → open
        action = "cooling"
    } else {
        level = flr                                         // idle/off → close to floor
        action = "idle"
    }
    int pct = Math.max(0, Math.min(100, (int) Math.round(level)))

    // Band the room temperature is coloured against: the active target while conditioning,
    // otherwise the setpoint(s) of the thermostat mode (both edges in auto). Off → no band.
    def lo = target, hi = target
    if (target == null) {
        if (mode == "heat" && heatSp != null)      { lo = hi = (heatSp as double) + (heatOffset ?: 0.0) }
        else if (mode == "cool" && coolSp != null) { lo = hi = (coolSp as double) + (coolOffset ?: 0.0) }
        else if (mode == "auto" && heatSp != null && coolSp != null) {
            lo = (heatSp as double) + (heatOffset ?: 0.0)
            hi = (coolSp as double) + (coolOffset ?: 0.0)
        }
    }
    return [ok: true, thermostat: t, room: room, opState: opState, mode: mode, action: action,
            conditioning: (action != "idle"), byThermostat: (heating || cooling),
            target: target, level: level, pct: pct, floor: flr, band: b,
            lo: lo, hi: hi, roomColor: tempColor(room, lo, hi)]
}

def evaluateVent() {
    Map p = ventPlan()
    state.lastEvalMs = now()
    if (!p.ok) { if (p.thermostat) log.warn "Room Vent '${app.label}': ${p.why}"; return }
    applyLevel(p.level as double, p.conditioning as boolean)
}

// --- current status (page top) ---
private String currentStatus() {
    Map p = ventPlan()
    def rows = []
    if (!p.ok) {
        rows << row("HVAC", pill(p.why, "red"))
    } else {
        String lbl = p.action == "idle" ? (p.opState in [null, 'idle'] ? "idle" : "idle (${p.opState})")
                   : p.action + (p.byThermostat ? "" : " — always adjust, thermostat ${p.opState ?: 'idle'}")
        rows << row("HVAC", pill(lbl, actionColor(p.action)) + " <small>mode ${p.mode}</small>")
        rows << row("Room temperature", pill("${fmt1(p.room)}°", p.roomColor) + " <small>${bandText(p)}</small>")
        rows << row("Vent target", pill("${p.pct}%", p.conditioning ? "green" : "grey") + " <small>floor ${p.floor as int}%</small>")
    }
    ventLevels?.each { v ->
        def lv = v.currentValue('level')
        rows << row("Vent — ${v.displayName}", pill(lv != null ? "${lv}%" : "unknown", "indigo"))
    }
    ventSwitches?.each { v ->
        String sw = v.currentValue('switch') ?: 'unknown'
        rows << row("Vent — ${v.displayName}", pill(sw, sw == 'on' ? 'green' : 'grey'))
    }
    if (!ventLevels && !ventSwitches) rows << row("Vents", pill("none selected", "grey"))
    long last = (state.lastEvalMs ?: 0L) as long
    long next = (state.nextEvalMs ?: 0L) as long
    rows << row("Last evaluated", last ? pill("${span(last)} ago", "grey") : pill("not yet", "grey"))
    rows << row("Next periodic re-check",
                (next + 60000L) > now() ? pill("in ${span(next)}", "grey")
                    : pill(next ? "overdue — press Done to restart the timer" : "not scheduled yet — press Done", "amber"))
    return rows.join("<br>")
}

// called by the parent app for its overview line
Map statusSummary() {
    Map p = ventPlan()
    String html = p.ok
        ? pill(p.action, actionColor(p.action)) + " " + pill("${fmt1(p.room)}°", p.roomColor) +
          " <small>" + (p.target != null ? "target ${fmt1(p.target)}° · " : "") + "vents ${p.pct}%</small>"
        : pill(p.why, "red")
    return [kind: "Room Vent", html: html]
}

private String actionColor(String action) {
    action == "heating" ? "amber" : (action == "cooling" ? "blue" : "grey")
}

private String fmt1(x) { x == null ? "?" : String.format("%.1f", x as double) }

// blue = colder than the band, green = within 0.5° of it, amber (orange) = warmer; grey = no band
private String tempColor(room, lo, hi) {
    if (room == null || lo == null || hi == null) return "grey"
    double r = room as double
    if (r < (lo as double) - 0.5d) return "blue"
    if (r > (hi as double) + 0.5d) return "amber"
    return "green"
}

private String bandText(Map p) {
    String legend = " — blue below · green on target · orange above"
    if (p.target != null) return "target ${fmt1(p.target)}° · fully open ${fmt1(p.band)}° from target" + legend
    if (p.lo == null) return "no target — thermostat mode ${p.mode ?: 'unknown'}"
    if (p.lo == p.hi) return "target ${fmt1(p.lo)}° while idle" + legend
    return "comfort band ${fmt1(p.lo)}°–${fmt1(p.hi)}° (auto)" + legend
}

// "45s" / "3 min" / "2 h 5 min" between now and a past or future instant
private String span(long ms) {
    long secs = Math.abs(now() - ms).intdiv(1000L)
    if (secs < 60) return "${secs}s"
    long mins = secs.intdiv(60L)
    if (mins < 60) return "${mins} min"
    long rem = mins % 60
    return "${mins.intdiv(60L)} h" + (rem ? " ${rem} min" : "")
}

// --- status helpers (same look as the Blinds Dusk Automation status block) ---
private String row(String label, String value) {
    "<b>${label}:</b> ${value}"
}

private String pill(String text, String color) {
    def bg = [green:'#2e7d32', red:'#c62828', amber:'#ef6c00',
              blue:'#1565c0', indigo:'#4527a0', grey:'#616161'][color] ?: '#616161'
    "<span style='background:${bg};color:#fff;padding:2px 8px;border-radius:10px;font-size:0.85em;white-space:nowrap'>${text}</span>"
}

private double scale(double delta, double band, double flr) {
    if (delta <= 0) return flr
    double frac = delta / band
    if (frac > 1) frac = 1
    return Math.round(flr + (100.0d - flr) * frac) as double
}

private applyLevel(double level, boolean conditioning) {
    int lv = Math.max(0, Math.min(100, (int) Math.round(level)))
    ventLevels?.each { it.setLevel(lv) }
    ventSwitches?.each { (conditioning && lv > 0) ? it.on() : it.off() }
    log.debug "Room Vent '${app.label}': vents → ${lv}% (conditioning=${conditioning})"
}
