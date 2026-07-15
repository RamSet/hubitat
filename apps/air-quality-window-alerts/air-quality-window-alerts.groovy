/**
 *  Air Quality Window Alerts
 *
 *  Tells you when a window or door is open and the air outside is bad. Two independent
 *  alerts:
 *    - a window is opened while the air is already bad
 *    - the air turns bad, or gets worse, while a window is already open
 *
 *  Alerts are held while an extractor fan is drawing power, since it is already pulling
 *  cooking fumes out and a spike is expected. Once the fan stops, the app re-checks and
 *  delivers the alert it withheld if the air is still bad.
 *
 *  Air quality is read straight off the selected devices, so no Hub Variables are
 *  required. The decision is made on airQualityIndex (EPA 0-500 scale). A device's index
 *  commonly reflects a single pollutant, so several devices may be selected per location
 *  and the worst reading governs — which is how an overall AQI is derived. pm25 /
 *  airQualityPlain / airQuality are used for message detail where a device exposes them.
 */

definition(
    name:        "Air Quality Window Alerts",
    namespace:   "ramset",
    author:      "RamSet",
    description: "Notifies when windows/doors are open and outdoor air quality is bad, with exhaust-fan suppression",
    category:    "Convenience",
    iconUrl:     "",
    iconX2Url:   "",
    importUrl:   "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/apps/air-quality-window-alerts/air-quality-window-alerts.groovy"
)

preferences {
    page(name: "mainPage")
}

// EPA AQI bands, worst last: [lower bound, label]
def bands() {
    [[0, "Good"], [51, "Moderate"], [101, "Unhealthy for Sensitive Groups"],
     [151, "Unhealthy"], [201, "Very Unhealthy"], [301, "Hazardous"]]
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Air Quality Window Alerts", install: true, uninstall: true) {
        section("Status") {
            paragraph statusHtml()
        }
        section("Sensors") {
            input "contacts",  "capability.contactSensor", title: "Windows / doors to watch", multiple: true, required: true, submitOnChange: true
            input "outdoorAQ", "capability.airQuality",    title: "Outdoor air quality sensors", multiple: true, required: true,  submitOnChange: true
            input "indoorAQ",  "capability.airQuality",    title: "Indoor air quality sensors (for message context, optional)", multiple: true, required: false, submitOnChange: true
            paragraph "Pick <b>one device per pollutant</b> — a PM2.5 device and a TVOC device are different measurements, " +
                      "and a sensor's own <b>airQualityIndex</b> usually reflects only one of them. The app takes the " +
                      "<b>worst</b> reading across everything you pick, which is how an overall AQI is meant to be " +
                      "computed, and the messages name the pollutant that is driving it."
        }
        section("When is the air 'bad'?") {
            input "threshold", "enum", title: "Alert when the worst outdoor reading reaches",
                  options: bands().collectEntries { [(it[0].toString()): it[1]] },
                  defaultValue: "51", required: true, submitOnChange: true
            paragraph "Judged on <b>airQualityIndex</b> (EPA 0-500 scale). " +
                      "Picking <i>Moderate</i> alerts on anything that is not Good."
        }
        section("Alerts") {
            input "alertOnOpen",   "bool", title: "Alert when a window/door is opened while the air is already bad", defaultValue: true
            input "alertOnWorsen", "bool", title: "Alert when the air turns bad (or gets worse) while a window/door is already open", defaultValue: true
        }
        section("Extractor fan suppression (cooking)") {
            paragraph "While an extractor fan is drawing power it is pulling cooking fumes out, so a spike is " +
                      "expected and alerts are held. When it stops, the app re-checks and will alert if the air " +
                      "is still bad and something is still open."
            input "exhaustMeter", "capability.powerMeter", title: "Extractor fan power meter", required: false, submitOnChange: true
            if (exhaustMeter) {
                input "exhaustWatts", "number", title: "Consider the fan running above (watts)", defaultValue: 50, required: true
                input "exhaustLag",   "number", title: "Keep holding alerts for this many minutes after it stops", defaultValue: 5, required: true
            }
        }
        section("Notify") {
            input "notifiers", "capability.notification",     title: "Notification devices", multiple: true, required: false, submitOnChange: true
            input "speakers",  "capability.speechSynthesis",  title: "Speakers to announce on", multiple: true, required: false, submitOnChange: true
            if (speakers) {
                input "speakVolume", "number", title: "Speak at this volume (leave blank to not change it)", required: false
            }
            if (!notifiers && !speakers) paragraph "<b>Pick at least one</b> notification device or speaker, or nothing will be delivered."
            input "cooldown",  "number", title: "After an alert, stay quiet for at least this many minutes", defaultValue: 5, required: true
        }
        section(hideable: true, hidden: true, "Quiet hours") {
            paragraph "Speakers stay silent between these times. Push notifications still go out, " +
                      "unless you tick the box below. Leave the times blank for no quiet hours."
            input "quietStart",     "time", title: "Quiet from", required: false, submitOnChange: true
            input "quietEnd",       "time", title: "Quiet until", required: false, submitOnChange: true
            input "quietMutePush",  "bool", title: "Hold push notifications during quiet hours too", defaultValue: false
            paragraph "The cooldown applies to windows being <i>opened</i>. If the air itself gets worse " +
                      "while something is open, you are told straight away — that is the alert you actually " +
                      "want to hear, and it only repeats when the air moves into a worse band."
        }
        section(hideable: true, hidden: true, "Messages") {
            paragraph "Leave these alone for sensible defaults, or rewrite them. Tokens:<br>" + tokenHelp()
            input "openMsg",   "textarea", title: "Opened into bad air", defaultValue: defaultOpenMsg(),   required: true
            input "worsenMsg", "textarea", title: "Air turned bad while open", defaultValue: defaultWorsenMsg(), required: true
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
    state.alerted    = false
    state.alertedAqi = null
    state.lastAqi    = outdoorWorst()?.aqi
    state.fanStopped = null

    subscribe(contacts,  "contact",         contactHandler)
    subscribe(outdoorAQ, "airQualityIndex", airQualityHandler)
    if (exhaustMeter) subscribe(exhaustMeter, "power", powerHandler)

    logDebug "initialized — ${contacts?.size()} contacts, outdoor AQI ${state.lastAqi}, threshold ${thresholdAqi()}"
}

// ---------------------------------------------------------------- handlers

def contactHandler(evt) {
    if (evt.value == "closed") {
        if (!openContacts()) {
            logDebug "all contacts closed — alert latch reset"
            state.alerted    = false
            state.alertedAqi = null
        }
        return
    }

    if (!alertOnOpen) return

    def aqi = outdoorWorst()?.aqi
    if (aqi == null) {
        log.warn "no airQualityIndex on any outdoor sensor — cannot evaluate ${evt.displayName} opening"
        return
    }
    if (aqi < thresholdAqi()) {
        logDebug "${evt.displayName} opened but outdoor AQI ${aqi} is below threshold ${thresholdAqi()} — no alert"
        return
    }
    if (state.alerted) {
        logDebug "${evt.displayName} opened, air is bad, but an alert was already sent — staying quiet"
        return
    }
    if (suppressed()) {
        logDebug "${evt.displayName} opened and air is bad, but exhaust fan is running — holding alert"
        return
    }

    sendAlert(openMessage(evt.displayName, aqi), aqi)
}

def airQualityHandler(evt) {
    // Recompute across every sensor rather than trusting evt.value: a clean PM2.5
    // reading arriving after a bad TVOC one must not overwrite the verdict.
    def aqi  = outdoorWorst()?.aqi
    def prev = state.lastAqi
    state.lastAqi = aqi
    if (aqi == null) return

    // Reset the latch on recovery regardless of which alerts are enabled, so a later
    // spike is reported again.
    if (aqi < thresholdAqi()) {
        if (state.alerted) {
            logDebug "outdoor AQI dropped to ${aqi}, below threshold — alert latch reset"
            state.alerted    = false
            state.alertedAqi = null
        }
        return
    }

    if (!alertOnWorsen) return

    def open = openContacts()
    if (!open) {
        logDebug "outdoor AQI ${aqi} but nothing is open — no alert"
        return
    }
    // Bad air, something is open. Alert on the first crossing, and again each time
    // it degrades into a worse band than the one we last reported.
    if (state.alerted && bandOf(aqi) <= bandOf(state.alertedAqi)) {
        logDebug "outdoor AQI ${aqi} still in band '${bandName(aqi)}' already reported — staying quiet"
        return
    }
    if (suppressed()) {
        logDebug "outdoor AQI ${aqi} with ${open.size()} open, but exhaust fan is running — holding alert"
        return
    }

    sendAlert(worsenMessage(aqi, prev, open), aqi, false)
}

def powerHandler(evt) {
    def watts   = toBigDecimal(evt.value)
    def running = watts != null && watts > (exhaustWatts ?: 50)

    if (running) {
        state.fanStopped = null
        unschedule("fanSettled")
        return
    }
    if (state.fanStopped != null) return   // already counting down

    state.fanStopped = now()
    def lag = (exhaustLag ?: 0) as Integer
    logDebug "exhaust fan stopped (${watts}W) — re-checking in ${lag} minute(s)"
    if (lag > 0) runIn(lag * 60, "fanSettled") else fanSettled()
}

// After cooking, re-evaluate: if the air is still bad and something is still open,
// say so now — the alert we swallowed during cooking was never delivered.
def fanSettled() {
    state.fanStopped = null

    def aqi = outdoorWorst()?.aqi
    if (aqi == null || aqi < thresholdAqi()) return
    if (state.alerted) return

    def open = openContacts()
    if (!open) return

    logDebug "exhaust fan settled, air is still bad (AQI ${aqi}) with ${open.size()} open — sending held alert"
    sendAlert(worsenMessage(aqi, null, open), aqi)
}

// ---------------------------------------------------------------- messages

def defaultOpenMsg() {
    '%device% is open and the air outside is %outdoorLevel% — %outdoorAdvice% (%outdoorDetail%). %indoorSummary%'
}

def defaultWorsenMsg() {
    'The air outside is now %outdoorLevel% — %outdoorAdvice%. Please close: %openSensors% (%outdoorDetail%). %indoorSummary%'
}

// Plain-English "so what should I do", one per air-quality band, for whoever reads the
// alert rather than the numbers. Keyed by band index (see bands()).
def adviceFor(aqi) {
    [ "the air is clean",                        // Good
      "fine for most people",                    // Moderate
      "not ideal if anyone is sensitive",        // Unhealthy for Sensitive Groups
      "keep the windows shut",                   // Unhealthy
      "keep everything closed",                  // Very Unhealthy
      "keep it closed up and stay inside",       // Hazardous
    ][bandOf(aqi)]
}

def tokenHelp() {
    [ "<b>%device%</b> the sensor that just opened",
      "<b>%openSensors%</b> every sensor currently open, comma separated",
      "<b>%openCount%</b> how many are open",
      "<b>%outdoorLevel%</b> e.g. Unhealthy &nbsp; <b>%outdoorAdvice%</b> plain English, e.g. keep the windows shut",
      "<b>%outdoorDetail%</b> e.g. AQI 158, PM2.5 68 µg/m³ &nbsp; <b>%outdoorSource%</b> which sensor is driving it",
      "<b>%outdoorAQI%</b> &nbsp; <b>%outdoorPM25%</b> &nbsp; <b>%previousLevel%</b> the band before this change",
      "<b>%indoorSummary%</b> a whole sentence about indoors, empty if no indoor sensor",
      "<b>%indoorLevel%</b> &nbsp; <b>%indoorDetail%</b> &nbsp; <b>%indoorSource%</b> &nbsp; <b>%indoorAQI%</b> &nbsp; <b>%indoorPM25%</b>"
    ].join("<br>")
}

def openMessage(String device, Integer aqi) {
    render(openMsg ?: defaultOpenMsg(), tokens(device, aqi, null, openContacts()))
}

def worsenMessage(Integer aqi, Integer prev, List open) {
    render(worsenMsg ?: defaultWorsenMsg(), tokens(null, aqi, prev, open))
}

def tokens(String device, Integer aqi, Integer prev, List open) {
    def out = outdoorWorst()
    def inn = indoorWorst()
    [
        '%device%'       : device ?: '',
        '%openSensors%'  : open ? open*.displayName.sort().join(', ') : '',
        '%openCount%'    : (open?.size() ?: 0).toString(),
        '%outdoorLevel%' : out == null ? '' : bandLabel(out.dev, aqi ?: out.aqi),
        '%outdoorAdvice%': out == null ? '' : adviceFor(aqi ?: out.aqi),
        '%outdoorDetail%': out == null ? '' : aqiDetail(out.dev, aqi ?: out.aqi),
        '%outdoorSource%': out == null ? '' : out.dev.displayName,
        '%outdoorAQI%'   : aqi == null ? '' : aqi.toString(),
        '%outdoorPM25%'  : out == null ? '' : str(out.dev.currentValue("pm25")),
        // prev is a past reading, so it gets the band name, never a device's current wording
        '%previousLevel%': prev == null ? '' : bandName(prev),
        '%indoorSummary%': indoorSummary() ?: '',
        '%indoorLevel%'  : inn == null ? '' : bandLabel(inn.dev, inn.aqi),
        '%indoorDetail%' : inn == null ? '' : aqiDetail(inn.dev, inn.aqi),
        '%indoorSource%' : inn == null ? '' : inn.dev.displayName,
        '%indoorAQI%'    : inn == null ? '' : inn.aqi.toString(),
        '%indoorPM25%'   : inn == null ? '' : str(inn.dev.currentValue("pm25")),
    ]
}

def indoorSummary() {
    def inn = indoorWorst()
    if (inn == null) return null
    return "Indoors it is ${bandLabel(inn.dev, inn.aqi)} (${aqiDetail(inn.dev, inn.aqi)})."
}

// Substitute tokens, then tidy up the gaps left by ones that resolved to nothing —
// without collapsing newlines, so multi-line templates survive.
def render(String tmpl, Map tok) {
    def s = tmpl ?: ''
    tok.each { k, v -> s = s.replace(k, v.toString()) }
    return s.replaceAll(/[ \t]{2,}/, ' ').replaceAll(/[ \t]+([.,;:])/, '$1').trim()
}

def str(v) { v == null ? '' : v.toString() }

// "AQI 158, PM2.5 68 µg/m³" — drops the PM2.5 half if the device does not report it.
def aqiDetail(dev, Integer aqi) {
    def bits = ["AQI ${aqi}"]
    def pm25 = dev?.currentValue("pm25")
    if (pm25 != null) bits << "PM2.5 ${pm25} µg/m³"
    return bits.join(", ")
}

// gated=false for air-quality changes: the air genuinely getting worse is news, and
// the band latch already stops it repeating within the same band, so it is not spam.
def sendAlert(String msg, Integer aqi, boolean gated = true) {
    if (gated && cooling()) {
        logDebug "would alert (${msg}) but only ${sinceLastAlert()}s since the last one — cooling down"
        return
    }
    log.info "alert: ${msg}"
    deliver(msg)
    state.alerted     = true
    state.alertedAqi  = aqi
    state.lastAlertAt = now()
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

// A hard floor between notifications, however many sensors trip in the meantime.
def cooling() {
    if (state.lastAlertAt == null) return false
    return sinceLastAlert() < ((cooldown ?: 0) as Integer) * 60
}

def sinceLastAlert() {
    state.lastAlertAt == null ? null : ((now() - state.lastAlertAt) / 1000L) as Long
}

// ---------------------------------------------------------------- helpers

def openContacts() {
    contacts?.findAll { it.currentValue("contact") == "open" } ?: []
}

def suppressed() {
    if (!exhaustMeter) return false
    def watts = toBigDecimal(exhaustMeter.currentValue("power"))
    if (watts != null && watts > (exhaustWatts ?: 50)) return true
    if (state.fanStopped == null) return false
    return (now() - state.fanStopped) < ((exhaustLag ?: 0) as Integer) * 60000L
}

def thresholdAqi() { toInt(threshold) ?: 51 }

// An overall AQI is the WORST of its sub-indices, never an average: a pristine PM2.5
// reading must not dilute a bad TVOC one. Returns [aqi: n, dev: <the driving device>].
def worst(devs) {
    def w = null
    devs?.each { d ->
        def a = toInt(d.currentValue("airQualityIndex"))
        if (a != null && (w == null || a > w.aqi)) w = [aqi: a, dev: d]
    }
    return w
}

def outdoorWorst() { worst(outdoorAQ) }
def indoorWorst()  { worst(indoorAQ) }

def currentAqi(dev) { toInt(dev?.currentValue("airQualityIndex")) }

// Index into bands(), so severity can be compared with < and >.
def bandOf(aqi) {
    def n = toInt(aqi)
    if (n == null) return -1
    def b = bands()
    def i = 0
    b.eachWithIndex { band, ndx -> if (n >= band[0]) i = ndx }
    return i
}

def bandName(aqi) {
    def n = toInt(aqi)
    return n == null ? "unknown" : bands()[bandOf(n)][1]
}

// Prefer the device's own wording, where it publishes airQualityPlain, over our band
// name — but only for that device's current reading, never for a historical value.
// The label MUST come from the same scale the threshold is defined on (bands()), never
// from the device's own airQualityPlain — a sensor whose "good" runs higher than EPA's
// would otherwise print "good" on an alert that only fired because the number crossed the
// threshold. Same-scale word and trigger, no contradiction.
def bandLabel(dev, aqi) {
    def n = toInt(aqi)
    return n == null ? "unknown" : bandName(n)
}

// ---------------------------------------------------------------- status panel

def statusHtml() {
    if (!outdoorAQ && !contacts) return "<i>Pick your sensors below — live readings appear here.</i>"

    def rows = []
    def out = outdoorWorst()
    def inn = indoorWorst()

    outdoorAQ?.each { rows << [rows.any { it[0] == "Outdoor" } ? "" : "Outdoor", airHtml(it, out?.dev)] }
    indoorAQ?.each  { rows << [rows.any { it[0] == "Indoor"  } ? "" : "Indoor",  airHtml(it, inn?.dev)] }

    if (outdoorAQ) {
        rows << ["Verdict", out == null
            ? "<i>no outdoor reading — nothing can fire</i>"
            : (out.aqi >= thresholdAqi()
                ? "${dot('#e74c3c')} outdoor air is <b>bad</b> (at or past ${bandName(thresholdAqi())}), driven by <b>${out.dev.displayName}</b>"
                : "${dot('#27ae60')} outdoor air is <b>OK</b> (below ${bandName(thresholdAqi())}), worst is <b>${out.dev.displayName}</b>")]
    }

    if (exhaustMeter) {
        def w = exhaustMeter.currentValue("power")
        rows << ["Exhaust fan", suppressed()
            ? "${dot('#e67e22')} <b>running</b> — alerts held (${w} W)"
            : "${dot('#95a5a6')} off (${w} W)"]
    }

    rows << ["Alerts", alertStateHtml()]

    return tableHtml(rows) + contactsHtml()
}

// governing = the device whose reading is currently deciding things, flagged so a split
// between a clean PM2.5 sensor and a bad TVOC one is impossible to miss.
def airHtml(dev, governing = null) {
    def aqi = currentAqi(dev)
    def name = "${dev.displayName}:"
    if (aqi == null) return "<span style='opacity:.6'>${name}</span> <i>no reading</i>"

    def bits = ["<b>${bandLabel(dev, aqi)}</b>", "AQI ${aqi}"]
    def pm   = dev.currentValue("pm25")
    def voc  = dev.currentValue("vocIndex")
    def t    = dev.currentValue("temperature")
    def h    = dev.currentValue("humidity")
    if (pm  != null) bits << "PM2.5 ${pm} µg/m³"
    if (voc != null) bits << "VOC ${voc}"
    if (t   != null) bits << "${t}°"
    if (h   != null) bits << "${h}% RH"

    def line = "${dot(bandColor(aqi))} <span style='opacity:.6'>${name}</span> ${bits.join(' &nbsp;·&nbsp; ')}"
    if (governing != null && dev.id == governing.id) line += " &nbsp;<b>&larr; governing</b>"
    return line
}

def alertStateHtml() {
    def bits = [state.alerted
        ? "${dot('#e67e22')} already alerted for this spell"
        : "${dot('#27ae60')} nothing sent"]
    if (state.lastAlertAt) bits << "last ${new Date(state.lastAlertAt as Long).format('MMM d, h:mm a', location.timeZone)}"
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

def bandColor(aqi) {
    ["#27ae60", "#f1c40f", "#e67e22", "#e74c3c", "#8e44ad", "#7d3c1e"][bandOf(aqi)]
}

def dot(String color) { "<span style='color:${color};font-size:1.1em'>&#9679;</span>" }

def toInt(v) {
    if (v == null) return null
    try { return (v as BigDecimal).intValue() } catch (e) { return null }
}

def toBigDecimal(v) {
    if (v == null) return null
    try { return v as BigDecimal } catch (e) { return null }
}

def logDebug(msg) { if (logEnable) log.debug msg }
