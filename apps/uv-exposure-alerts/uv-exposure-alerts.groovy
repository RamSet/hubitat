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
            input "outdoorAQ", "capability.airQuality",             title: "Outdoor air quality sensors (for message context, optional)", multiple: true, required: false, submitOnChange: true
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
            input "notifiers", "capability.notification",    title: "Notification devices", multiple: true, required: false, submitOnChange: true
            input "speakers",  "capability.speechSynthesis", title: "Speakers to announce on", multiple: true, required: false, submitOnChange: true
            if (speakers) {
                input "speakVolume", "number", title: "Speak at this volume (leave blank to not change it)", required: false
            }
            if (!notifiers && !speakers) paragraph "<b>Pick at least one</b> notification device or speaker, or nothing will be delivered."
            input "cooldown",  "number", title: "After an alert, stay quiet for at least this many minutes", defaultValue: 5, required: true
        }
        section(hideable: true, hidden: true, "Quiet hours") {
            paragraph "Speakers stay silent between these times. Push notifications still go out, " +
                      "unless you tick the box below. Leave the times blank for no quiet hours."
            input "quietStart",    "time", title: "Quiet from", required: false, submitOnChange: true
            input "quietEnd",      "time", title: "Quiet until", required: false, submitOnChange: true
            input "quietMutePush", "bool", title: "Hold push notifications during quiet hours too", defaultValue: false
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
    if (cooling()) {
        logDebug "${data.device} opened, UV ${uv} (${bandName(uv)}) but only ${sinceLastAlert()}s since the last alert — cooling down"
        return
    }

    log.info "alert: ${msg}"
    deliver(msg)
    state.lastAlertAt = now()
    maybeStrobe(band, uv)
}

def deliver(String msg) {
    def quiet = inQuietHours()

    if (quiet && quietMutePush) logDebug "quiet hours — holding the push notification"
    else notifiers*.deviceNotification(msg)

    if (quiet) {
        logDebug "quiet hours — not speaking"
        return
    }
    // setVolume is not part of the speechSynthesis capability, so only call it where
    // the driver actually offers it.
    speakers?.each { s ->
        if (speakVolume != null && s.hasCommand("setVolume")) s.setVolume(speakVolume)
        s.speak(msg)
    }
}

// timeOfDayIsBetween cannot express a window that crosses midnight, so split it.
def inQuietHours() {
    if (!quietStart || !quietEnd) return false
    def tz  = location.timeZone
    def now = new Date()
    def s   = timeToday(quietStart, tz)
    def e   = timeToday(quietEnd, tz)

    if (s <= e) return timeOfDayIsBetween(s, e, now, tz)
    return timeOfDayIsBetween(s, timeToday("23:59", tz), now, tz) ||
           timeOfDayIsBetween(timeToday("00:00", tz), e, now, tz)
}

// A hard floor between notifications, however many doors open in the meantime.
def cooling() {
    if (state.lastAlertAt == null) return false
    return sinceLastAlert() < ((cooldown ?: 0) as Integer) * 60
}

def sinceLastAlert() {
    state.lastAlertAt == null ? null : ((now() - state.lastAlertAt) / 1000L) as Long
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
    def aq = aqWorst()
    [
        '%device%'       : device ?: '',
        '%uv%'           : uv == null ? '' : uv.toString(),
        '%uvLevel%'      : bandName(uv),
        '%aqSuffix%'     : aqSuffix() ?: '',
        '%outdoorLevel%' : aq == null ? '' : aqLabel(aq),
        '%outdoorDetail%': aq == null ? '' : aqDetail(aq),
        '%outdoorSource%': aq == null ? '' : aq.dev.displayName,
        '%outdoorAQI%'   : aq == null ? '' : aq.aqi.toString(),
        '%outdoorPM25%'  : aq == null ? '' : str(aq.dev.currentValue("pm25")),
    ]
}

// An overall AQI is the WORST of its sub-indices, never an average: a pristine PM2.5
// reading must not dilute a bad TVOC one. Returns [aqi: n, dev: <the driving device>].
def aqWorst() {
    def w = null
    outdoorAQ?.each { d ->
        def a = toInt(d.currentValue("airQualityIndex"))
        if (a != null && (w == null || a > w.aqi)) w = [aqi: a, dev: d]
    }
    return w
}

def aqSuffix() {
    def aq = aqWorst()
    if (aq == null) return null
    return " Outdoor air quality is ${aqLabel(aq)} (${aqDetail(aq)})."
}

// EPA AQI wording, but prefer whatever the driving sensor itself calls it.
def aqLabel(Map aq) {
    def plain = aq.dev.currentValue("airQualityPlain")
    if (plain) return plain
    def epa = [[0, "Good"], [51, "Moderate"], [101, "Unhealthy for Sensitive Groups"],
               [151, "Unhealthy"], [201, "Very Unhealthy"], [301, "Hazardous"]]
    def i = 0
    epa.eachWithIndex { b, ndx -> if (aq.aqi >= b[0]) i = ndx }
    return epa[i][1]
}

def aqDetail(Map aq) {
    def bits = ["AQI ${aq.aqi}"]
    def pm25 = aq.dev.currentValue("pm25")
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

// ---------------------------------------------------------------- status panel

def statusHtml() {
    if (!uvSensor && !contacts) return "<i>Pick your sensors below — live readings appear here.</i>"

    def rows = []
    if (uvSensor) rows << ["UV", uvHtml()]

    def gov = aqWorst()
    outdoorAQ?.eachWithIndex { d, i ->
        def a = toInt(d.currentValue("airQualityIndex"))
        def line = a == null
            ? "<span style='opacity:.6'>${d.displayName}:</span> <i>no reading</i>"
            : "${dot(aqColor(a))} <span style='opacity:.6'>${d.displayName}:</span> <b>${aqLabel([aqi: a, dev: d])}</b> &nbsp;·&nbsp; ${aqDetail([aqi: a, dev: d])}"
        if (gov != null && d.id == gov.dev.id) line += " &nbsp;<b>&larr; governing</b>"
        rows << [i == 0 ? "Outdoor air" : "", line]
    }

    def uv = currentUv()
    if (uv != null) {
        def msg = messageFor(bandOf(uv))
        rows << ["If a door opens", msg?.trim()
            ? "${dot('#e67e22')} you would be told (${bandName(uv)})"
            : "${dot('#95a5a6')} silent — no message set for ${bandName(uv)}"]
    }

    rows << ["Notifies at", bandsWithMessagesHtml()]
    rows << ["Alerts", alertStateHtml()]

    return tableHtml(rows) + contactsHtml()
}

def uvHtml() {
    def uv = currentUv()
    if (uv == null) return "<i>no reading</i>"
    return "${dot(uvColor(uv))} <b>${bandName(uv)}</b> &nbsp;·&nbsp; index ${uv}"
}

def bandsWithMessagesHtml() {
    def on = []
    bands().eachWithIndex { b, i -> if (messageFor(i)?.trim()) on << b[1] }
    return on ? on.join(", ") : "<i>nothing — every band's message is blank</i>"
}

def alertStateHtml() {
    def bits = state.lastAlertAt
        ? ["last ${new Date(state.lastAlertAt as Long).format('MMM d, h:mm a', location.timeZone)}"]
        : ["${dot('#27ae60')} nothing sent yet"]
    if (cooling()) bits << "<b>cooling down</b>, ${(((cooldown ?: 0) as Integer) * 60) - sinceLastAlert()}s left"
    if (inQuietHours()) bits << "${dot('#8e44ad')} <b>quiet hours</b>${quietMutePush ? ' — push held too' : ' — speakers silent'}"
    return bits.join(" &nbsp;·&nbsp; ")
}

def contactsHtml() {
    if (!contacts) return ""
    def open = [], shut = []
    contacts.toSorted { it.displayName }.each {
        (it.currentValue("contact") == "open" ? open : shut) << it.displayName
    }

    def s = new StringBuilder()
    s << "<div style='margin-top:10px'><b>${open.size()} open</b>, ${shut.size()} closed</div>"
    open.each { s << "<div>${dot('#e74c3c')} ${it} — <b>open</b></div>" }
    shut.each { s << "<div style='opacity:.6'>${dot('#27ae60')} ${it} — closed</div>" }
    return s.toString()
}

def tableHtml(List rows) {
    def s = new StringBuilder("<table style='border-collapse:collapse'>")
    rows.each {
        s << "<tr><td style='padding:3px 14px 3px 0;white-space:nowrap;vertical-align:top;opacity:.6'>${it[0]}</td>"
        s << "<td style='padding:3px 0'>${it[1]}</td></tr>"
    }
    s << "</table>"
    return s.toString()
}

def uvColor(uv) { ["#27ae60", "#f1c40f", "#e67e22", "#e74c3c", "#8e44ad"][bandOf(uv)] }

def aqColor(Integer aqi) {
    def cuts = [0, 51, 101, 151, 201, 301]
    def cols = ["#27ae60", "#f1c40f", "#e67e22", "#e74c3c", "#8e44ad", "#7d3c1e"]
    def i = 0
    cuts.eachWithIndex { c, ndx -> if (aqi >= c) i = ndx }
    return cols[i]
}

def dot(String color) { "<span style='color:${color};font-size:1.1em'>&#9679;</span>" }

def toInt(v) {
    if (v == null) return null
    try { return (v as BigDecimal).intValue() } catch (e) { return null }
}

def str(v) { v == null ? '' : v.toString() }

def logDebug(msg) { if (logEnable) log.debug msg }
