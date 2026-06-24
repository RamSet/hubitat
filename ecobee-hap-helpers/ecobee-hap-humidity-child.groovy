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
 */
definition(
    name:        "Local Ecobee Humidity",
    namespace:   "RamSet",
    author:      "RamSet",
    description: "Offline: runs a humidifier socket while the heater is on, up to a maximum desired humidity.",
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

def apply() {
    def t = parent?.getThermostat()
    boolean heating = (t?.currentValue("thermostatOperatingState") == "heating")
    def rh = measuredHumidity()
    int maxH = effectiveMax()

    if (heating && rh != null && (rh as int) < maxH) {
        humidifier?.on()
        log.info "Humidity '${app.label}': heater on, ${rh}% < ${maxH}% → humidifier ON"
    } else {
        humidifier?.off()
        log.debug "Humidity '${app.label}': humidifier OFF (heating=${heating}, rh=${rh}, max=${maxH})"
    }
}
