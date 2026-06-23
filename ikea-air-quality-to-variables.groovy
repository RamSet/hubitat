/**
 *  IKEA Air Quality -> Hub Variables
 *
 *  Reads two IKEA Vindstyrka (E2112) air quality sensors (an indoor and an
 *  outdoor unit, here Hub Mesh-linked from a C7) and publishes their values
 *  into Hub Variables so Rule Machine / dashboards / notification apps can
 *  consume them.
 *
 *  Mapping (per sensor):
 *    pm25            -> <Prefix>P2.5Value         (Number)
 *    airQualityPlain -> <Prefix>AirQuality        (String)
 *    airQualityPlain -> <Prefix>P2.5Translation   (String)
 *    temperature     -> <Prefix>Temperature       (Decimal)
 *    humidity        -> <Prefix>Humidity          (Number)
 *
 *  Any named variable that does not yet exist is created automatically with
 *  the correct type. Values are only written when they actually change.
 */

definition(
    name:        "IKEA Air Quality to Hub Variables",
    namespace:   "ramset",
    author:      "RamSet",
    description: "Publishes IKEA Vindstyrka sensor values into Hub Variables",
    category:    "Convenience",
    iconUrl:     "",
    iconX2Url:   "",
    importUrl:   "https://raw.githubusercontent.com/RamSet/hubitat/main/ikea-air-quality-to-variables.groovy",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "IKEA Air Quality to Hub Variables", install: true, uninstall: true) {
        section("Current readings") {
            paragraph statusHtml()
        }
        section("Sensors") {
            input "indoorSensor",  "capability.airQuality", title: "Indoor IKEA sensor",  required: false, submitOnChange: true
            input "outdoorSensor", "capability.airQuality", title: "Outdoor IKEA sensor", required: false, submitOnChange: true
        }
        section("Indoor variable names (created automatically if missing)") {
            input "vInPm25",  "text", title: "PM2.5 value var (Number)",        defaultValue: "IndoorP2.5Value",       required: false
            input "vInAQ",    "text", title: "Air quality text var (String)",   defaultValue: "IndoorAirQuality",      required: false
            input "vInTrans", "text", title: "PM2.5 translation var (String)",  defaultValue: "IndoorP2.5Translation", required: false
            input "vInTemp",  "text", title: "Temperature var (Decimal)",       defaultValue: "IndoorTemperature",     required: false
            input "vInHum",   "text", title: "Humidity var (Number)",           defaultValue: "IndoorHumidity",        required: false
        }
        section("Outdoor variable names (created automatically if missing)") {
            input "vOutPm25",  "text", title: "PM2.5 value var (Number)",       defaultValue: "OutsideP2.5Value",       required: false
            input "vOutAQ",    "text", title: "Air quality text var (String)",  defaultValue: "OutsideAirQuality",      required: false
            input "vOutTrans", "text", title: "PM2.5 translation var (String)", defaultValue: "OutsideP2.5Translation", required: false
            input "vOutTemp",  "text", title: "Temperature var (Decimal)",      defaultValue: "OutsideTemperature",     required: false
            input "vOutHum",   "text", title: "Humidity var (Number)",          defaultValue: "OutsideHumidity",        required: false
        }
        section("Options") {
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: true
        }
    }
}

def installed() { initialize() }

def updated() {
    unsubscribe()
    initialize()
}

def initialize() {
    createMissingVars()

    def names = allVarNames()
    addInUseGlobalVar(names)

    ["pm25", "airQualityPlain", "temperature", "humidity"].each { attr ->
        if (indoorSensor)  subscribe(indoorSensor,  attr, indoorHandler)
        if (outdoorSensor) subscribe(outdoorSensor, attr, outdoorHandler)
    }

    syncIndoor()
    syncOutdoor()
}

def uninstalled() {
    removeAllInUseGlobalVar()
}

/* ------------------------------------------------------------------ */
/* Event handlers                                                     */
/* ------------------------------------------------------------------ */

def indoorHandler(evt)  { syncIndoor() }
def outdoorHandler(evt) { syncOutdoor() }

def syncIndoor() {
    if (!indoorSensor) return
    publish(indoorSensor, settings.vInPm25, settings.vInAQ, settings.vInTrans, settings.vInTemp, settings.vInHum, "Indoor")
    state.lastIndoor = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
}

def syncOutdoor() {
    if (!outdoorSensor) return
    publish(outdoorSensor, settings.vOutPm25, settings.vOutAQ, settings.vOutTrans, settings.vOutTemp, settings.vOutHum, "Outdoor")
    state.lastOutdoor = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
}

/* ------------------------------------------------------------------ */
/* Core publish + helpers                                             */
/* ------------------------------------------------------------------ */

private publish(dev, pm25Var, aqVar, transVar, tempVar, humVar, label) {
    def aq    = plainAQ(dev)
    def pm25  = num(dev.currentValue("pm25"))
    def temp  = num(dev.currentValue("temperature"))
    def hum   = num(dev.currentValue("humidity"))

    int changed = 0
    changed += writeVar(pm25Var,  pm25?.toInteger(), false)
    changed += writeVar(aqVar,    aq,                true)
    changed += writeVar(transVar, aq,                true)
    changed += writeVar(tempVar,  temp,              false)
    changed += writeVar(humVar,   hum?.toInteger(),  false)

    if (logEnable) log.debug "${label}: pm25=${pm25} aq=${aq} temp=${temp} hum=${hum} (${changed} var(s) updated)"
}

/* Write only when the value actually differs from what is already stored. Returns 1 if written. */
private writeVar(name, val, asString) {
    if (!name || val == null) return 0
    def newVal = asString ? val.toString() : val
    def cur = getGlobalVar(name)?.value
    if (cur?.toString() == newVal?.toString()) return 0
    setGlobalVar(name, newVal)
    return 1
}

private plainAQ(dev) {
    def v = dev.currentValue("airQualityPlain")
    if (v == null) v = stripHtml(dev.currentValue("airQuality"))
    return v
}

private stripHtml(v) { v == null ? null : v.toString().replaceAll("<[^>]*>", "") }

private num(v) {
    if (v == null) return null
    try { return new BigDecimal(v.toString()) } catch (e) { return null }
}

/* Create any named variable that does not exist yet, with the right type. */
private createMissingVars() {
    def existing = (getAllGlobalVars() ?: [:]).keySet()
    def numVars = [settings.vInPm25, settings.vInHum, settings.vOutPm25, settings.vOutHum]
    def decVars = [settings.vInTemp, settings.vOutTemp]
    def strVars = [settings.vInAQ, settings.vInTrans, settings.vOutAQ, settings.vOutTrans]

    numVars.findAll { it && !existing.contains(it) }.each { make(it, 0,    "Number") }
    decVars.findAll { it && !existing.contains(it) }.each { make(it, 0.0G, "Decimal") }
    strVars.findAll { it && !existing.contains(it) }.each { make(it, "",   "String") }
}

private make(name, init, typeLabel) {
    if (createGlobalVar(name, init)) log.info "Created ${typeLabel} hub variable: ${name}"
    else log.warn "Could not create hub variable: ${name}"
}

private allVarNames() {
    return [settings.vInPm25, settings.vInAQ, settings.vInTrans, settings.vInTemp, settings.vInHum,
            settings.vOutPm25, settings.vOutAQ, settings.vOutTrans, settings.vOutTemp, settings.vOutHum].findAll { it }
}

/* Live readings panel shown on the app page. */
private String statusHtml() {
    def rows = []
    rows << sensorRow("Indoor",  indoorSensor,  state.lastIndoor)
    rows << sensorRow("Outdoor", outdoorSensor, state.lastOutdoor)
    return rows.join("<br>")
}

private String sensorRow(label, dev, last) {
    if (!dev) return "<b>${label}:</b> (no sensor selected)"
    def aq   = plainAQ(dev)
    def pm   = dev.currentValue("pm25")
    def t    = dev.currentValue("temperature")
    def h    = dev.currentValue("humidity")
    def when = last ? " &nbsp;|&nbsp; last sync: ${last}" : ""
    return "<b>${label}:</b> AQ=${aq}, PM2.5=${pm} µg/m³, ${t}°, ${h}% RH${when}"
}
