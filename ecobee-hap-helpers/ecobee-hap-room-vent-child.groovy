/**
 *  Local Ecobee Room Vent  (child app)
 *
 *  Per-room vent control following the local Ecobee HAP Thermostat.
 *  While the HVAC is heating/cooling, opens the room's vent(s) proportionally
 *  toward (thermostat setpoint + per-room offset), closing to a minimum floor
 *  once the room is satisfied. Fully offline.
 *
 *  Child of: Local Ecobee Helpers (RamSet)
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
    importUrl:   "https://raw.githubusercontent.com/RamSet/hubitat/main/ecobee-hap-helpers/ecobee-hap-room-vent-child.groovy?v=20260624a"
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Room Vent", install: true, uninstall: true) {
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

def evaluateVent() {
    def t = parent?.getThermostat()
    if (!t) return
    def room = avgTemp()
    if (room == null) { log.warn "Room Vent '${app.label}': no room temperature yet"; return }

    def opState = t.currentValue("thermostatOperatingState")
    String mode = t.currentValue("thermostatMode")
    double flr  = (floor ?: 0) as double
    double b    = (band ?: 2.0) as double
    if (b <= 0) b = 0.1

    boolean heating = opState in ["heating", "pending heat"]
    boolean cooling = opState in ["cooling", "pending cool"]
    double level
    boolean conditioning = heating || cooling

    if (heating || (alwaysAdjust && mode == "heat")) {
        double target = ((t.currentValue("heatingSetpoint") as double) + (heatOffset ?: 0.0))
        level = scale(target - (room as double), b, flr)   // room below target → open
        conditioning = true
    } else if (cooling || (alwaysAdjust && mode == "cool")) {
        double target = ((t.currentValue("coolingSetpoint") as double) + (coolOffset ?: 0.0))
        level = scale((room as double) - target, b, flr)   // room above target → open
        conditioning = true
    } else {
        level = flr                                         // idle/off → close to floor
        conditioning = false
    }
    applyLevel(level, conditioning)
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
