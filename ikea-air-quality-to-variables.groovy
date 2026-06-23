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
 *  Optionally also pushes into existing devices (e.g. the Virtual AQI driver)
 *  via airQualityIndex(): AQI devices receive the sensor's airQualityIndex;
 *  TVOC devices receive the VOC Index remapped onto the EPA AQI 0-500 scale
 *  (so dashboard color thresholds line up — see vocToAqi).
 *
 *  The app does NOT create Hub Variables or devices (apps cannot). Every
 *  target must already exist and is selected from a dropdown; a missing Hub
 *  Variable is logged and skipped. Values are only written when they change.
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
        section("Hub Variables") {
            paragraph "This app does NOT create Hub Variables — Hubitat apps cannot. " +
                      "Each variable you want to populate must already exist. Create them " +
                      "first under Settings &gt; Hub Variables (with the type shown below), " +
                      "then pick them from the dropdowns. The dropdowns only list variables " +
                      "that currently exist; leave one blank to not publish that value."
        }
        def vars = varOptions()
        section("Indoor variables") {
            input "vInPm25",  "enum", title: "PM2.5 value var (Number)",        options: vars, required: false
            input "vInAQ",    "enum", title: "Air quality text var (String)",   options: vars, required: false
            input "vInTrans", "enum", title: "PM2.5 translation var (String)",  options: vars, required: false
            input "vInTemp",  "enum", title: "Temperature var (Decimal)",       options: vars, required: false
            input "vInHum",   "enum", title: "Humidity var (Number)",           options: vars, required: false
        }
        section("Outdoor variables") {
            input "vOutPm25",  "enum", title: "PM2.5 value var (Number)",       options: vars, required: false
            input "vOutAQ",    "enum", title: "Air quality text var (String)",  options: vars, required: false
            input "vOutTrans", "enum", title: "PM2.5 translation var (String)", options: vars, required: false
            input "vOutTemp",  "enum", title: "Temperature var (Decimal)",      options: vars, required: false
            input "vOutHum",   "enum", title: "Humidity var (Number)",          options: vars, required: false
        }
        section("Devices (optional)") {
            paragraph "Same as above, the app does NOT create devices — each must " +
                      "already exist and is selected from the dropdown. Selected devices " +
                      "are updated via their airQualityIndex() command: AQI devices get the " +
                      "sensor's AQI (airQualityIndex) directly; TVOC devices get the VOC Index " +
                      "remapped onto the EPA AQI 0-500 scale so dashboard colors match " +
                      "(strict bands: VOC 100=Good cap, 150=Moderate, 200=USG, 250=Unhealthy, " +
                      "400=Very Unhealthy, 500=Hazardous). Leave blank to skip."
            input "devInAQI",   "capability.airQuality", title: "Indoor AQI device",   required: false
            input "devOutAQI",  "capability.airQuality", title: "Outdoor AQI device",  required: false
            input "devInTVOC",  "capability.airQuality", title: "Indoor TVOC device",  required: false
            input "devOutTVOC", "capability.airQuality", title: "Outdoor TVOC device", required: false
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
    def existing = (getAllGlobalVars() ?: [:]).keySet()
    def wanted   = allVarNames()
    def missing  = wanted.findAll { !existing.contains(it) }
    def present  = wanted.findAll { existing.contains(it) }

    if (missing) log.warn "Hub Variable(s) not found, will be skipped — create them under Settings > Hub Variables: ${missing}"
    if (present) addInUseGlobalVar(present)

    ["pm25", "airQualityPlain", "airQualityIndex", "vocIndex", "temperature", "humidity"].each { attr ->
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
    pushDevice(devInAQI,  indoorSensor, "airQualityIndex", false, "Indoor AQI")
    pushDevice(devInTVOC, indoorSensor, "vocIndex",        true,  "Indoor TVOC")
    state.lastIndoor = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
}

def syncOutdoor() {
    if (!outdoorSensor) return
    publish(outdoorSensor, settings.vOutPm25, settings.vOutAQ, settings.vOutTrans, settings.vOutTemp, settings.vOutHum, "Outdoor")
    pushDevice(devOutAQI,  outdoorSensor, "airQualityIndex", false, "Outdoor AQI")
    pushDevice(devOutTVOC, outdoorSensor, "vocIndex",        true,  "Outdoor TVOC")
    state.lastOutdoor = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
}

/* Push a sensor value into a target device via its airQualityIndex() command, only on change.
   When remapVoc is true the raw VOC Index is converted onto the EPA AQI 0-500 scale first. */
private pushDevice(targetDev, sensor, srcAttr, remapVoc, label) {
    if (!targetDev) return
    def raw = num(sensor.currentValue(srcAttr))?.toInteger()
    if (raw == null) return
    def val = remapVoc ? vocToAqi(raw) : raw
    if (!targetDev.hasCommand("airQualityIndex")) {
        log.warn "${targetDev} has no airQualityIndex() command — skipping ${label}"
        return
    }
    if (targetDev.currentValue("airQualityIndex")?.toString() == val.toString()) return
    targetDev.airQualityIndex(val)
    if (logEnable) log.debug "${label}: set ${targetDev} airQualityIndex=${val}" + (remapVoc ? " (VOC Index ${raw} remapped)" : " (from ${srcAttr})")
}

/* Sensirion VOC Index (0-500, baseline 100) -> EPA AQI 0-500 scale, "strict" bands,
   linear within each band so dashboard colors map to Good/Moderate/USG/etc. */
private int vocToAqi(int v) {
    def pts = [[0,0], [100,50], [150,100], [200,150], [250,200], [400,300], [500,500]]
    if (v <= 0)   return 0
    if (v >= 500) return 500
    for (int i = 0; i < pts.size() - 1; i++) {
        def (inLo, outLo) = pts[i]
        def (inHi, outHi) = pts[i + 1]
        if (v <= inHi) {
            return Math.round(outLo + (v - inLo) * (outHi - outLo) / (double)(inHi - inLo)) as int
        }
    }
    return 500
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
    def gv = getGlobalVar(name)
    if (gv == null) return 0                       // variable does not exist; skip
    def newVal = asString ? val.toString() : val
    if (gv.value?.toString() == newVal?.toString()) return 0
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

private allVarNames() {
    return [settings.vInPm25, settings.vInAQ, settings.vInTrans, settings.vInTemp, settings.vInHum,
            settings.vOutPm25, settings.vOutAQ, settings.vOutTrans, settings.vOutTemp, settings.vOutHum].findAll { it }
}

/* Existing Hub Variable names, for the dropdown options. */
private varOptions() {
    return (getAllGlobalVars() ?: [:]).keySet().sort()
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
