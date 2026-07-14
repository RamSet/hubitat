/**
 *  UV Exposure Alerts
 *
 *  Replaces Rule Machine rule "10.8 UV Measurement and warnings".
 *
 *  When a door you actually walk out of stays open for a moment, look up the current
 *  UV index and — if it is high enough to matter — say so, optionally strobing a siren
 *  on the worst bands. Messages carry the outdoor air quality too, like the rule did.
 *
 *  The UV index is read from a sensor's illuminance attribute (rule 10.8 used the
 *  "UV" Virtual Illuminance Sensor, whose illuminance value IS the UV index).
 */

definition(
    name:        "UV Exposure Alerts",
    namespace:   "ramset",
    author:      "RamSet",
    description: "Warns about UV exposure when an exterior door is opened, with per-band messages",
    category:    "Convenience",
    iconUrl:     "",
    iconX2Url:   "",
    importUrl:   "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/apps/uv-exposure-alerts/uv-exposure-alerts.groovy"
)

preferences {
    page(name: "mainPage")
}

// WHO UV index bands, worst last: [lower bound, label]
def bands() {
    [[0, "Low"], [3, "Moderate"], [6, "High"], [8, "Very High"], [11, "Extreme"]]
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "UV Exposure Alerts", install: true, uninstall: true) {
        section("Status") {
            paragraph statusHtml()
        }
        section("Sensors") {
            input "contacts",  "capability.contactSensor",          title: "Doors that mean someone is going outside", multiple: true, required: true, submitOnChange: true
            input "uvSensor",  "capability.illuminanceMeasurement", title: "UV sensor (its illuminance value is the UV index)", required: true, submitOnChange: true
            input "outdoorAQ", "capability.airQuality",             title: "Outdoor air quality sensor (for message context, optional)", required: false, submitOnChange: true
            input "openFor",   "number",                            title: "Only alert if the door stays open this many seconds", defaultValue: 2, required: true
        }
        section("Messages") {
            paragraph "One message per UV band. <b>Leave a band blank to stay quiet at that level</b> — Low is silent by default, " +
                      "as in the original rule. Tokens:<br>" + tokenHelp()
            input "msgLow",      "textarea", title: "Low (UV 0-2)",       defaultValue: "",                required: false
            input "msgModerate", "textarea", title: "Moderate (UV 3-5)",  defaultValue: defaultMsg(1),     required: false
            input "msgHigh",     "textarea", title: "High (UV 6-7)",      defaultValue: defaultMsg(2),     required: false
            input "msgVeryHigh", "textarea", title: "Very High (UV 8-10)", defaultValue: defaultMsg(3),    required: false
            input "msgExtreme",  "textarea", title: "Extreme (UV 11+)",   defaultValue: defaultMsg(4),     required: false
        }
        section("Notify") {
            input "notifiers", "capability.notification", title: "Notification devices", multiple: true, required: true
        }
        section(hideable: true, hidden: true, "Strobe (optional)") {
            paragraph "The original rule strobed a siren on the worst bands and turned it off after 2 minutes."
            input "strobes", "capability.alarm", title: "Devices to strobe", multiple: true, required: false, submitOnChange: true
            if (strobes) {
                input "strobeFrom", "enum", title: "Strobe at this band or worse",
                      options: bands().collectEntries { [(it[0].toString()): it[1]] },
                      defaultValue: "8", required: true
                input "strobeFor", "number", title: "Then turn it off after (minutes)", defaultValue: 2, required: true
            }
        }
        section("Options") {
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: true
            label title: "Name this app", required: false
        }
    }
}

def installed() { initialize() }

def updated() {
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    subscribe(contacts, "contact.open", contactHandler)
    logDebug "initialized — ${contacts?.size()} doors, UV ${currentUv()}"
}

// ---------------------------------------------------------------- handlers

def contactHandler(evt) {
    def wait = (openFor ?: 0) as Integer
    if (wait > 0) runIn(wait, "stillOpen", [data: [device: evt.displayName], overwrite: false])
    else stillOpen([device: evt.displayName])
}

// The rule required the door to stay open, so a quick in-and-out does not nag.
def stillOpen(data) {
    def dev = contacts?.find { it.displayName == data.device }
    if (dev && dev.currentValue("contact") != "open") {
        logDebug "${data.device} was closed again before ${openFor}s — no alert"
        return
    }

    def uv = currentUv()
    if (uv == null) {
        log.warn "${uvSensor?.displayName} has no illuminance value — cannot evaluate UV"
        return
    }

    def band = bandOf(uv)
    def msg  = render(messageFor(band), tokens(data.device, uv))
    if (!msg) {
        logDebug "${data.device} opened, UV ${uv} (${bandName(uv)}) — no message configured for this band, staying quiet"
        return
    }

    log.info "alert: ${msg}"
    notifiers*.deviceNotification(msg)
    maybeStrobe(band, uv)
}

def maybeStrobe(Integer band, Integer uv) {
    if (!strobes) return
    if (band < bandOf(toInt(strobeFrom) ?: 8)) return

    logDebug "UV ${uv} (${bandName(uv)}) — strobing for ${strobeFor} minute(s)"
    strobes*.strobe()
    runIn(((strobeFor ?: 2) as Integer) * 60, "strobeOff")
}

def strobeOff() { strobes*.off() }

// ---------------------------------------------------------------- messages

// One default per band index; Low (0) is deliberately silent.
def defaultMsg(Integer band) {
    def advice = [
        1: "It is a good idea to cover up.",
        2: "Cover up, and use sunscreen.",
        3: "Avoid the sun if you can, and use sunscreen.",
        4: "I would not go out unless you have to.",
    ]
    return "UV is %uvLevel% (%uv%). ${advice[band]}%aqSuffix%"
}

def tokenHelp() {
    [ "<b>%device%</b> the door that was opened",
      "<b>%uv%</b> the UV index &nbsp; <b>%uvLevel%</b> e.g. Very High",
      "<b>%aqSuffix%</b> a whole sentence about outdoor air quality, empty if no sensor selected",
      "<b>%outdoorLevel%</b> &nbsp; <b>%outdoorDetail%</b> e.g. AQI 158, PM2.5 68 µg/m³",
      "<b>%outdoorAQI%</b> &nbsp; <b>%outdoorPM25%</b>"
    ].join("<br>")
}

def messageFor(Integer band) {
    [msgLow, msgModerate, msgHigh, msgVeryHigh, msgExtreme][band]
}

def tokens(String device, Integer uv) {
    def aqi = outdoorAQ ? toInt(outdoorAQ.currentValue("airQualityIndex")) : null
    [
        '%device%'       : device ?: '',
        '%uv%'           : uv == null ? '' : uv.toString(),
        '%uvLevel%'      : bandName(uv),
        '%aqSuffix%'     : aqSuffix() ?: '',
        '%outdoorLevel%' : aqi == null ? '' : aqLabel(aqi),
        '%outdoorDetail%': aqi == null ? '' : aqDetail(aqi),
        '%outdoorAQI%'   : aqi == null ? '' : aqi.toString(),
        '%outdoorPM25%'  : str(outdoorAQ?.currentValue("pm25")),
    ]
}

def aqSuffix() {
    if (!outdoorAQ) return null
    def aqi = toInt(outdoorAQ.currentValue("airQualityIndex"))
    if (aqi == null) return null
    return " Outdoor air quality is ${aqLabel(aqi)} (${aqDetail(aqi)})."
}

// EPA AQI wording, but prefer whatever the sensor itself calls it.
def aqLabel(Integer aqi) {
    def plain = outdoorAQ?.currentValue("airQualityPlain")
    if (plain) return plain
    def epa = [[0, "Good"], [51, "Moderate"], [101, "Unhealthy for Sensitive Groups"],
               [151, "Unhealthy"], [201, "Very Unhealthy"], [301, "Hazardous"]]
    def i = 0
    epa.eachWithIndex { b, ndx -> if (aqi >= b[0]) i = ndx }
    return epa[i][1]
}

def aqDetail(Integer aqi) {
    def bits = ["AQI ${aqi}"]
    def pm25 = outdoorAQ?.currentValue("pm25")
    if (pm25 != null) bits << "PM2.5 ${pm25} µg/m³"
    return bits.join(", ")
}

// Substitute tokens, then tidy up the gaps left by ones that resolved to nothing —
// without collapsing newlines, so multi-line templates survive.
def render(String tmpl, Map tok) {
    if (!tmpl?.trim()) return null
    def s = tmpl
    tok.each { k, v -> s = s.replace(k, v.toString()) }
    return s.replaceAll(/[ \t]{2,}/, ' ').replaceAll(/[ \t]+([.,;:])/, '$1').trim()
}

// ---------------------------------------------------------------- helpers

def currentUv() { toInt(uvSensor?.currentValue("illuminance")) }

// Index into bands(), so severity can be compared with < and >.
def bandOf(uv) {
    def n = toInt(uv)
    if (n == null) return 0
    def b = bands()
    def i = 0
    b.eachWithIndex { band, ndx -> if (n >= band[0]) i = ndx }
    return i
}

def bandName(uv) {
    def n = toInt(uv)
    return n == null ? "unknown" : bands()[bandOf(n)][1]
}

def statusHtml() {
    if (!uvSensor) return "Pick a UV sensor below to see live readings here."
    def uv = currentUv()
    def rows = ["UV right now: <b>${uv == null ? 'no reading' : bandName(uv) + ' (' + uv + ')'}</b>"]
    if (outdoorAQ) {
        def aqi = toInt(outdoorAQ.currentValue("airQualityIndex"))
        rows << "Outdoor air: <b>${aqi == null ? 'no reading' : aqLabel(aqi) + ' (' + aqDetail(aqi) + ')'}</b>"
    }
    def open = contacts?.findAll { it.currentValue("contact") == "open" } ?: []
    rows << "Open right now: <b>${open ? open*.displayName.sort().join(', ') : 'nothing'}</b>"
    return rows.join("<br>")
}

def toInt(v) {
    if (v == null) return null
    try { return (v as BigDecimal).intValue() } catch (e) { return null }
}

def str(v) { v == null ? '' : v.toString() }

def logDebug(msg) { if (logEnable) log.debug msg }
