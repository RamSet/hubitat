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

    def why = []
    if (rainSensors?.any  { it.currentValue("water")   == "wet" })  why << "rain"
    if (windContacts?.any { it.currentValue("contact") == "open" }) why << "high wind"
    if (windSpeed && windMax != null) {
        def w = toBigDecimal(windSpeed.currentValue("illuminance"))
        if (w != null && w >= windMax) why << "wind ${w}"
    }
    if (why) {
        rows << row("Weather", pill("unsafe — ${why.join(', ')}", "red") + " <small>protected decorations held off</small>")
    } else if (weatherUnsafe()) {
        long left = (state.lastUnsafeAt as long) + ((allClear ?: 0) as Integer) * 60000L - now()
        int mins = (int) Math.ceil(left / 60000.0d)
        rows << row("Weather", pill("settling — clear again in ${mins} min", "amber"))
    } else {
        rows << row("Weather", pill("clear", "green"))
    }
    if (windSpeed) {
        rows << row("Wind speed — ${windSpeed.displayName}",
                    pill("${windSpeed.currentValue('illuminance')}", "blue") + " <small>unsafe at ${windMax}</small>")
    }
    if (luxSensors) {
        def readings = luxSensors.collect { toInt(it.currentValue("illuminance")) }.findAll { it != null }
        rows << row("Light", readings
            ? pill("${readings.max()} lux — ${isDark() ? 'dark' : 'daylight'}", isDark() ? "indigo" : "amber") +
              " <small>dark at or below ${darkBelow ?: 40}</small>"
            : pill("no reading", "grey"))
    } else {
        rows << row("Light", pill("no light sensors — always counts as dark", "amber"))
    }
    rows << row("Security", pill(location.hsmStatus ?: "unknown", "grey"))

    def kids = childApps
    if (!kids) {
        rows << row("Schedules", pill("none yet — add one below", "grey"))
    } else {
        kids.each { kid -> rows << row("Schedule — ${kid.label}", kid.summaryHtml()) }
        overlaps(kids).each { rows << row("Warning", pill(it, "amber")) }
    }
    return rows.join("<br>")
}

// Two schedules claiming the same calendar day fight over any device they share. Easy to
// do by accident when one season ends on the day the next begins. Schedules that overlap
// in dates but drive different devices are fine, so only a shared device is flagged.
def overlaps(kids) {
    def clashes = []
    kids.each { a ->
        kids.each { b ->
            if (a.id < b.id && a.overlapsWith(b)) {
                def am = a.deviceMap(), bm = b.deviceMap()
                def shared = am.keySet().findAll { bm.containsKey(it) }
                if (shared) clashes << "${a.label} and ${b.label} overlap in dates and both drive ${shared.collect { am[it] }.join(', ')}"
            }
        }
    }
    return clashes
}

// --- status helpers (same look as the Blinds Dusk Automation status block) ---
def row(String label, String value) {
    "<b>${label}:</b> ${value}"
}

def pill(String text, String color) {
    def bg = [green:'#2e7d32', red:'#c62828', amber:'#ef6c00',
              blue:'#1565c0', indigo:'#4527a0', grey:'#616161'][color] ?: '#616161'
    "<span style='background:${bg};color:#fff;padding:2px 8px;border-radius:10px;font-size:0.85em;white-space:nowrap'>${text}</span>"
}

def toInt(v) {
    if (v == null) return null
    try { return (v as BigDecimal).intValue() } catch (e) { return null }
}

def toBigDecimal(v) {
    if (v == null) return null
    try { return v as BigDecimal } catch (e) { return null }
}

def logDebug(msg) { if (logEnable) log.debug msg }
