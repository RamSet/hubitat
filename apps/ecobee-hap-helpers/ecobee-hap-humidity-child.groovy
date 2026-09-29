/**
 *  Local Ecobee Humidity  (child app)
 *
 *  Offline humidifier control for the local Ecobee HAP Thermostat setup.
 *  Runs a humidifier outlet/socket ONLY while the heater is running:
 *    - heater on  AND  humidity < maximum desired  ->  humidifier ON
 *    - humidity >= maximum desired, OR heater not running  ->  humidifier OFF
 *  Optional frost control lowers the maximum automatically as it gets colder
 *  outside (prevents window condensation/ice) using a local outdoor sensor.
 *
 *  Temperature scale (C/F) is taken from the hub automatically.
 *
 *  Child of: Local Ecobee Helpers (RamSet)
 *
 *  Author: RamSet
 *  Version: 1.1.0 (2026-09-28)
 *  Version history:
 *    1.1.0 - Current-status block at the top of the page: the humidifier decision and why, each outlet's
 *            actual state, heater state, humidity vs the effective maximum, outdoor temperature.
 *    1.0.0 - Initial release. Heater-gated humidifier socket, max desired humidity, optional frost control, C/F auto-detect.
 *
 *  DISCLAIMER: Provided as-is, without warranty of any kind. You are solely
 *  responsible for the safe operation of your HVAC system and connected devices.
 *  Use at your own risk.
 */
definition(
    name:        "Local Ecobee Humidity",
    namespace:   "RamSet",
    author:      "RamSet",
    description: "Offline: runs a humidifier socket while the heater is on, up to a maximum desired humidity.",
    category:    "Convenience",
    parent:      "RamSet:Local Ecobee Helpers",
    iconUrl:     "",
    iconX2Url:   "",
    importUrl:   "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/apps/ecobee-hap-helpers/ecobee-hap-humidity-child.groovy"
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Humidity", install: true, uninstall: true) {
        section("<b>Current status</b>") {
            paragraph currentStatus()
        }
        section("Hardware") {
            label title: "Name for this Humidity helper", required: true
            input "humidifier", "capability.switch", title: "Humidifier switch / powered socket", multiple: true, required: true
            input "humSensor", "capability.relativeHumidityMeasurement", title: "Humidity sensor (optional — defaults to the thermostat's humidity)", required: false, submitOnChange: true
            paragraph "Measured humidity: <b>${measuredHumidity()}%</b>"
        }
        section("Maximum desired humidity") {
            input "maxHumidity", "number", title: "Maximum desired humidity %", defaultValue: 50, range: "10..60", required: true, submitOnChange: true
            paragraph "While the heater is running, the humidifier turns on whenever measured humidity is below this. At or above it, the humidifier stays off. It is always off when the heater is not running."
        }
        section("Frost control (optional)") {
            input "frostControl", "bool", title: "Automatically lower the maximum when it is cold outside", defaultValue: false, submitOnChange: true
            if (frostControl) {
                input "outdoorSensor", "capability.temperatureMeasurement", title: "Outdoor temperature sensor", required: true, submitOnChange: true
                if (outdoorSensor) {
                    paragraph "Outdoor: <b>${outdoorSensor.currentValue('temperature')}°${getTemperatureScale()}</b> → effective max: <b>${effectiveMax()}%</b> " +
                              "(°F→max RH%: ≥50→50, ≥40→45, ≥30→40, ≥20→35, ≥10→30, ≥0→25, ≥-10→20, else 15; never above your maximum)"
                }
            }
        }
    }
}

def installed() { initialize() }
def updated()   { unsubscribe(); unschedule(); initialize() }

def initialize() {
    def t = parent?.getThermostat()
    if (t) {
        subscribe(t, "thermostatOperatingState", evtHandler)   // heater on/off
        if (!humSensor) subscribe(t, "humidity", evtHandler)
    }
    if (humSensor) subscribe(humSensor, "humidity", evtHandler)
    if (frostControl && outdoorSensor) subscribe(outdoorSensor, "temperature", evtHandler)
    runEvery10Minutes(evtHandler)   // safety re-evaluation
    apply()
}

def evtHandler(evt = null) { apply() }

private measuredHumidity() {
    if (humSensor) return humSensor.currentValue("humidity")
    return parent?.getThermostat()?.currentValue("humidity")
}

// the maximum desired humidity, optionally lowered by frost control
Integer effectiveMax() {
    int m = (maxHumidity ?: 50) as int
    if (frostControl && outdoorSensor) {
        def ot = outdoorSensor.currentValue("temperature")
        if (ot != null) {
            double f = ot as double
            if (getTemperatureScale() == "C") f = f * 9.0d / 5.0d + 32.0d   // table is in °F
            int fm
            if (f >= 50) fm = 50
            else if (f >= 40) fm = 45
            else if (f >= 30) fm = 40
            else if (f >= 20) fm = 35
            else if (f >= 10) fm = 30
            else if (f >= 0)  fm = 25
            else if (f >= -10) fm = 20
            else fm = 15
            m = Math.min(m, fm)
        }
    }
    return m
}

// One computation for both the control loop and the status page, so the page shows
// exactly what apply() would do.
private Map decide() {
    def t = parent?.getThermostat()
    String op = t?.currentValue("thermostatOperatingState")
    boolean heating = (op == "heating")
    def rh = measuredHumidity()
    int maxH = effectiveMax()
    boolean on = heating && rh != null && (rh as int) < maxH
    String why = !t ? "no thermostat selected in the parent" :
                 !heating ? "heater not running (${op ?: 'unknown'})" :
                 rh == null ? "no humidity reading" :
                 on ? "heater on, ${rh}% < ${maxH}%" : "${rh}% is at or above the ${maxH}% maximum"
    return [thermostat: t, op: op, heating: heating, rh: rh, maxH: maxH, on: on, why: why]
}

def apply() {
    Map d = decide()
    if (d.on) {
        humidifier?.on()
        log.info "Humidity '${app.label}': ${d.why} → humidifier ON"
    } else {
        humidifier?.off()
        log.debug "Humidity '${app.label}': humidifier OFF (${d.why})"
    }
}

// --- current status (page top) ---
private String currentStatus() {
    Map d = decide()
    def rows = []
    rows << row("Humidifier", d.on ? pill("ON — ${d.why}", "green") : pill("OFF — ${d.why}", d.thermostat ? "grey" : "red"))
    humidifier?.each { h ->
        String sw = h.currentValue('switch') ?: 'unknown'
        rows << row("Outlet — ${h.displayName}", pill(sw, sw == 'on' ? 'green' : 'grey'))
    }
    rows << row("Heater", d.thermostat ? pill(d.heating ? "running" : "not running (${d.op ?: 'unknown'})", d.heating ? "amber" : "grey")
                                       : pill("no thermostat selected in the parent", "red"))
    int cfg = (maxHumidity ?: 50) as int
    rows << row("Humidity", (d.rh != null ? pill("${d.rh}%", "blue") : pill("no reading", "red")) +
                " <small>max ${d.maxH}%" + (d.maxH < cfg ? " (frost control lowered it from ${cfg}%)" : "") + "</small>")
    if (frostControl && outdoorSensor) {
        rows << row("Outdoor — ${outdoorSensor.displayName}",
                    pill("${outdoorSensor.currentValue('temperature')}°${getTemperatureScale()}", "blue"))
    }
    return rows.join("<br>")
}

// called by the parent app for its overview line
Map statusSummary() {
    Map d = decide()
    String rh = d.rh != null ? "${d.rh}%" : "no reading"
    return [kind: "Humidity",
            html: (d.on ? pill("humidifier ON", "green") : pill("humidifier off", d.thermostat ? "grey" : "red")) +
                  " <small>${rh} / max ${d.maxH}%</small>"]
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
