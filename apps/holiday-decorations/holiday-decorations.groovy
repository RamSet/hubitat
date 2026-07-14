/**
 *  Holiday Decorations (parent)
 *
 *  Runs seasonal decorations — one child schedule per season or group. The parent owns
 *  the sensing that every schedule shares: wind and rain, darkness, the security system
 *  state, and where to send notifications. Each child decides what to do with it.
 *
 *  Weather protection is a condition a schedule reads, not something that disables a
 *  schedule. So a decoration held off by wind resumes on its own once the wind settles —
 *  there is no paused state anything has to remember to undo.
 *
 *  The parent re-evaluates every child once a minute, and immediately on any weather,
 *  light or security change. Children therefore hold no schedules of their own, and
 *  every decision is re-derived from the current time and the current sensor readings.
 *  There is no stored state to drift, and a missed event cannot strand a decoration in
 *  the wrong state — the next tick corrects it.
 */

definition(
    name:        "Holiday Decorations",
    namespace:   "ramset",
    author:      "RamSet",
    description: "Seasonal decoration schedules with shared wind/rain protection and darkness sensing",
    category:    "Convenience",
    iconUrl:     "",
    iconX2Url:   "",
    importUrl:   "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/apps/holiday-decorations/holiday-decorations.groovy",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Holiday Decorations", install: true, uninstall: true) {
        section("Status") {
            paragraph statusHtml()
        }
        section("Weather protection") {
            paragraph "Anything a schedule marks as weather-protected is forced <b>off</b> while it is " +
                      "wet or windy, and released again once it has been clear for the settle time."
            input "rainSensors",  "capability.waterSensor",             title: "Rain sensors (wet = unsafe)", multiple: true, required: false
            input "windContacts", "capability.contactSensor",           title: "High-wind flags (open = unsafe)", multiple: true, required: false
            input "windSpeed",    "capability.illuminanceMeasurement",  title: "Wind speed sensor (optional, numeric)", required: false, submitOnChange: true
            if (windSpeed) {
                input "windMax", "number", title: "Unsafe at or above this wind speed", defaultValue: 20, required: true
            }
            input "allClear", "number", title: "Stay off for this many minutes after it clears", defaultValue: 10, required: true
        }
        section("Darkness") {
            paragraph "Used by any schedule set to come on <i>when it gets dark</i>. " +
                      "For scale: full daylight is thousands of lux, an overcast day still hundreds, " +
                      "civil twilight is around 3&ndash;40, and full night is under 1. " +
                      "<b>40 is a sensible threshold for decorations</b> &mdash; it trips as dusk finishes."
            input "luxSensors", "capability.illuminanceMeasurement", title: "Light sensors", multiple: true, required: false, submitOnChange: true
            input "darkBelow",  "number", title: "Dark when the brightest sensor is at or below (lux)",
                  range: "0..100000", defaultValue: 40, required: true
            if (luxSensors) paragraph liveLuxHtml()
        }
        section("Notify (optional)") {
            input "notifiers", "capability.notification",    title: "Notification devices", multiple: true, required: false
            input "speakers",  "capability.speechSynthesis", title: "Speakers to announce on", multiple: true, required: false
        }
        section("Schedules") {
            app name: "childSchedules", appName: "Holiday Decorations Schedule", namespace: "ramset",
                title: "Add a decoration schedule", multiple: true
        }
        section("Options") {
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: true
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
    if (rainSensors)  subscribe(rainSensors,  "water",       sensorHandler)
    if (windContacts) subscribe(windContacts, "contact",     sensorHandler)
    if (windSpeed)    subscribe(windSpeed,    "illuminance", sensorHandler)
    if (luxSensors)   subscribe(luxSensors,   "illuminance", sensorHandler)
    subscribe(location, "hsmStatus", sensorHandler)

    // One heartbeat drives every schedule: no per-child crons, no sunset jobs, and a
    // decoration cannot be stranded by a missed event — the next tick corrects it.
    runEvery1Minute("tick")
    tick()
}

def tick() {
    if (rawWeatherUnsafe()) state.lastUnsafeAt = now()
    childApps.each { it.evaluate() }
}

def sensorHandler(evt) {
    logDebug "${evt.name} = ${evt.value} — re-evaluating schedules"
    tick()
}

// ---------------------------------------------------------------- shared conditions
// Called by the children.

def weatherUnsafe() {
    if (rawWeatherUnsafe()) return true
    if (state.lastUnsafeAt == null) return false
    return (now() - state.lastUnsafeAt) < ((allClear ?: 0) as Integer) * 60000L
}

def rawWeatherUnsafe() {
    if (rainSensors?.any  { it.currentValue("water")   == "wet" })  return true
    if (windContacts?.any { it.currentValue("contact") == "open" }) return true
    if (windSpeed && windMax != null) {
        def w = toBigDecimal(windSpeed.currentValue("illuminance"))
        if (w != null && w >= windMax) return true
    }
    return false
}

// No light sensor means darkness is unknown; treat it as dark rather than silently
// never turning anything on.
def isDark() {
    def readings = luxSensors?.collect { toInt(it.currentValue("illuminance")) }?.findAll { it != null }
    if (!readings) return true
    return readings.max() <= (darkBelow ?: 40)
}

def liveLuxHtml() {
    def bits = luxSensors.collect { d ->
        def v = toInt(d.currentValue("illuminance"))
        "${d.displayName}: <b>${v == null ? 'no reading' : v + ' lux'}</b>"
    }
    return "${bits.join(' &nbsp;·&nbsp; ')} &nbsp;&rarr;&nbsp; currently <b>${isDark() ? 'dark' : 'daylight'}</b>"
}

// Shown on the child pages: "dark" is defined here, in one place, so every schedule
// agrees on it — but the child is where you feel the need to know what it means.
def darkExplainer() {
    if (!luxSensors) {
        return "<b>No light sensors are set in the Holiday Decorations parent app</b>, so &ldquo;dark&rdquo; " +
               "is always true and this will come on at the &ldquo;never before&rdquo; time. Add a light " +
               "sensor in the parent to make this meaningful."
    }
    def readings = luxSensors.collect { toInt(it.currentValue("illuminance")) }.findAll { it != null }
    def now = readings ? readings.max() : null
    def txt = "&ldquo;Dark&rdquo; means <b>${darkBelow ?: 40} lux or less</b>, measured on " +
              "${luxSensors*.displayName.join(', ')}. Change it in the Holiday Decorations parent app."
    if (now != null) {
        txt += " Right now it reads <b>${now} lux</b> &mdash; ${isDark() ? 'dark' : 'daylight'}."
    }
    return txt
}

def announce(String msg) {
    log.info msg
    notifiers*.deviceNotification(msg)
    speakers*.speak(msg)
}

// ---------------------------------------------------------------- status

def statusHtml() {
    def rows = []

    def unsafe = weatherUnsafe()
    def why = []
    if (rainSensors?.any  { it.currentValue("water")   == "wet" })  why << "rain"
    if (windContacts?.any { it.currentValue("contact") == "open" }) why << "high wind"
    if (windSpeed && windMax != null) {
        def w = toBigDecimal(windSpeed.currentValue("illuminance"))
        if (w != null && w >= windMax) why << "wind ${w}"
    }
    if (!why && unsafe) why << "settling"

    rows << ["Weather", unsafe
        ? "${dot('#e74c3c')} <b>unsafe</b> — protected decorations held off (${why.join(', ')})"
        : "${dot('#27ae60')} clear"]

    if (windSpeed) rows << ["Wind speed", "${windSpeed.currentValue('illuminance')} (unsafe at ${windMax})"]
    if (luxSensors) {
        def readings = luxSensors.collect { toInt(it.currentValue("illuminance")) }.findAll { it != null }
        rows << ["Light", readings
            ? "${dot(isDark() ? '#8e44ad' : '#f1c40f')} ${readings.max()} lux — <b>${isDark() ? 'dark' : 'daylight'}</b> (dark at or below ${darkBelow})"
            : "<i>no reading</i>"]
    }
    rows << ["Security", "${location.hsmStatus ?: 'unknown'}"]

    def kids = childApps
    if (!kids) {
        rows << ["Schedules", "<i>none yet — add one below</i>"]
    } else {
        kids.eachWithIndex { kid, i -> rows << [i == 0 ? "Schedules" : "", kid.summaryHtml()] }
        def clash = overlaps(kids)
        if (clash) rows << ["<b>Warning</b>", "${dot('#e67e22')} overlapping date ranges: <b>${clash.join('; ')}</b> — both run on the shared days"]
    }

    return tableHtml(rows)
}

// Two schedules claiming the same calendar day will fight over any device they share.
// Easy to do by accident when one season is set to end on the day the next begins.
def overlaps(kids) {
    def clashes = []
    kids.each { a ->
        kids.each { b ->
            if (a.id < b.id && a.overlapsWith(b)) clashes << "${a.label} & ${b.label}"
        }
    }
    return clashes
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
