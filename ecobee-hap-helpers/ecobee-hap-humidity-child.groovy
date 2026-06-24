/**
 *  Local Ecobee Humidity  (child app)
 *
 *  Offline hygrostat for the local Ecobee HAP Thermostat setup. Switches a
 *  humidifier outlet/socket on/off to hold a humidity target. The target is
 *  either fixed, or frost-controlled (lowered as it gets colder outside to
 *  prevent window condensation/ice) using a local outdoor temperature sensor.
 *
 *  Temperature scale (C/F) is taken from the hub automatically.
 *
 *  Child of: Local Ecobee Helpers (RamSet)
 */
definition(
    name:        "Local Ecobee Humidity",
    namespace:   "RamSet",
    author:      "RamSet",
    description: "Offline hygrostat: switches a humidifier socket to a target humidity (frost control or fixed).",
    category:    "Convenience",
    parent:      "RamSet:Local Ecobee Helpers",
    iconUrl:     "",
    iconX2Url:   ""
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Humidity", install: true, uninstall: true) {
        section("Naming & hardware") {
            label title: "Name for this Humidity helper", required: true
            input "humidifier", "capability.switch", title: "Humidifier switch / powered socket", multiple: true, required: true
            input "humSensor", "capability.relativeHumidityMeasurement", title: "Humidity sensor (optional — defaults to the thermostat's humidity)", required: false, submitOnChange: true
            paragraph "Measured humidity: <b>${measuredHumidity()}%</b>"
        }
        section("Target") {
            input "mode", "enum", title: "Target mode", required: true, submitOnChange: true,
                  options: ["frost": "Frost control (auto by outdoor temperature)", "fixed": "Fixed target"]
            if (mode == "frost") {
                input "outdoorSensor", "capability.temperatureMeasurement", title: "Outdoor temperature sensor", required: true, submitOnChange: true
                if (outdoorSensor) {
                    paragraph "Outdoor: <b>${outdoorSensor.currentValue('temperature')}°${getTemperatureScale()}</b> → target: <b>${targetHumidity()}%</b> " +
                              "(table, °F→max RH%: ≥50→50, ≥40→45, ≥30→40, ≥20→35, ≥10→30, ≥0→25, ≥-10→20, else 15)"
                }
            } else if (mode == "fixed") {
                input "fixedTarget", "number", title: "Humidity target %", range: "10..60", required: true, submitOnChange: true
            }
        }
        section("Bounds & behavior") {
            input "minHum", "number", title: "Minimum target % (never below)", defaultValue: 20, range: "10..60", required: true
            input "maxHum", "number", title: "Maximum target % (never above)", defaultValue: 50, range: "10..60", required: true
            input "hysteresis", "number", title: "Hysteresis % (turn on below target−this, off at target)", defaultValue: 3, range: "1..15", required: true
            input "onlyWhenHeating", "bool", title: "Only run while the thermostat mode is 'heat'", defaultValue: false
        }
    }
}

def installed() { initialize() }
def updated()   { unsubscribe(); unschedule(); initialize() }

def initialize() {
    if (humSensor) subscribe(humSensor, "humidity", evtHandler)
    def t = parent?.getThermostat()
    if (t) {
        if (!humSensor) subscribe(t, "humidity", evtHandler)
        subscribe(t, "thermostatMode", evtHandler)
    }
    if (mode == "frost" && outdoorSensor) subscribe(outdoorSensor, "temperature", evtHandler)
    runEvery10Minutes(evtHandler)   // safety re-evaluation
    apply()
}

def evtHandler(evt = null) { apply() }

private measuredHumidity() {
    if (humSensor) return humSensor.currentValue("humidity")
    return parent?.getThermostat()?.currentValue("humidity")
}

// frost/fixed target, clamped to bounds
Integer targetHumidity() {
    Integer raw
    if (mode == "fixed") {
        raw = (fixedTarget ?: 30) as Integer
    } else if (mode == "frost") {
        def ot = outdoorSensor?.currentValue("temperature")
        if (ot == null) return null
        double f = ot as double
        if (getTemperatureScale() == "C") f = f * 9.0d / 5.0d + 32.0d   // table is in °F
        if (f >= 50) raw = 50
        else if (f >= 40) raw = 45
        else if (f >= 30) raw = 40
        else if (f >= 20) raw = 35
        else if (f >= 10) raw = 30
        else if (f >= 0)  raw = 25
        else if (f >= -10) raw = 20
        else raw = 15
    } else {
        return null
    }
    int lo = (minHum ?: 20) as int
    int hi = (maxHum ?: 50) as int
    if (lo > hi) { int s = lo; lo = hi; hi = s }
    return Math.max(lo, Math.min(hi, raw as int))
}

def apply() {
    def t = parent?.getThermostat()
    if (onlyWhenHeating && t?.currentValue("thermostatMode") != "heat") {
        humidifier?.off()
        return
    }
    def target = targetHumidity()
    def rh = measuredHumidity()
    if (target == null || rh == null) { log.warn "Humidity '${app.label}': missing humidity/target"; return }

    int h = rh as int
    int tgt = target as int
    int hyst = (hysteresis ?: 3) as int

    if (h <= tgt - hyst) {
        humidifier?.on()
        log.info "Humidity '${app.label}': ${h}% ≤ ${tgt - hyst}% → humidifier ON (target ${tgt}%)"
    } else if (h >= tgt) {
        humidifier?.off()
        log.info "Humidity '${app.label}': ${h}% ≥ ${tgt}% → humidifier OFF"
    }
    // inside the hysteresis band: leave the humidifier as-is
}
