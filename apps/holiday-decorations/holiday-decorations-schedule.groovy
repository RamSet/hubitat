/**
 *  Holiday Decorations Schedule (child of "Holiday Decorations")
 *
 *  One instance per decoration group — Halloween, Turkey, Christmas, the Cauldron.
 *  Each says: which devices, which calendar range, when in the evening, and what
 *  conditions veto it (weather, security state).
 *
 *  The parent evaluates every child once a minute and on every sensor change, so this
 *  app holds no schedules of its own. Everything is derived from the current time and
 *  the parent's sensors, which means there is no state to get stuck in.
 */

definition(
    name:        "Holiday Decorations Schedule",
    namespace:   "ramset",
    author:      "RamSet",
    description: "One decoration schedule: devices, date range, evening window, weather and security vetoes",
    category:    "Convenience",
    iconUrl:     "",
    iconX2Url:   "",
    importUrl:   "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/apps/holiday-decorations/holiday-decorations-schedule.groovy",
    parent:      "ramset:Holiday Decorations"
)

preferences {
    page(name: "mainPage")
}

def MONTHS() {
    ["1":"Jan","2":"Feb","3":"Mar","4":"Apr","5":"May","6":"Jun",
     "7":"Jul","8":"Aug","9":"Sep","10":"Oct","11":"Nov","12":"Dec"]
}

def DAYS() { (1..31).collectEntries { [(it.toString()): it.toString()] } }

def mainPage() {
    dynamicPage(name: "mainPage", title: "Decoration Schedule", install: true, uninstall: true) {
        section("Status") {
            paragraph configured()
                ? statusHtml()
                : "<i>Fill in the sections below and hit Done — live status appears here once this schedule is complete.</i>"
        }
        section("What") {
            label title: "Name this schedule (e.g. Halloween)", required: true
            input "devices", "capability.switch", title: "Decorations to switch", multiple: true, required: true, submitOnChange: true
        }
        section("Season") {
            paragraph "The days of the year this schedule is live. A range may wrap the new year " +
                      "(e.g. Dec 1 to Jan 8). Outside it, these devices are switched off and left alone."
            input "startMonth", "enum", title: "From month", options: MONTHS(), required: true, width: 3
            input "startDay",   "enum", title: "day",        options: DAYS(),   required: true, width: 3
            input "endMonth",   "enum", title: "Until month", options: MONTHS(), required: true, width: 3
            input "endDay",     "enum", title: "day",         options: DAYS(),   required: true, width: 3
        }
        section("When, each day") {
            input "onWhen", "enum", title: "Turn on",
                  options: ["dark": "When it gets dark", "sunset": "At sunset", "time": "At a fixed time"],
                  defaultValue: "dark", required: true, submitOnChange: true

            // A defaultValue does not reach settings until the page is submitted, so read
            // through the same fallback the logic uses or the dependent inputs never draw.
            def mode = onWhen ?: "dark"

            if (mode == "sunset") {
                // Without an explicit range, Hubitat's number input refuses negatives.
                input "sunsetOffset", "number", title: "Minutes relative to sunset (negative = before sunset)",
                      range: "-240..240", defaultValue: -30, required: true
                input "notBefore",    "time",   title: "But never before (optional)", required: false
            }
            if (mode == "time") {
                input "onTime", "time", title: "On at", required: true
            }
            if (mode == "dark") {
                paragraph parent ? parent.darkExplainer() : ""
                input "notBefore", "time", title: "But never before (optional)", required: false
                paragraph "<small>Leave blank and it will not come on before <b>noon</b> — without some floor, " +
                          "&ldquo;when it gets dark&rdquo; is also true at 4am.</small>"
            }
            input "offTime", "time", title: "Off at", required: true
        }
        section("Vetoes") {
            input "weatherProtect", "bool",
                  title: "Force off in wind or rain",
                  description: "On for anything outdoors (inflatables). Off for anything indoors — the Cauldron does not care about the weather.",
                  defaultValue: true
            input "hsmOff", "enum", title: "Force off when the security system is in any of these states",
                  options: ["armedAway": "Armed Away", "armedNight": "Armed Night", "armedHome": "Armed Home"],
                  multiple: true, required: false
        }
        section(hideable: true, hidden: true, "Advanced") {
            input "reassert", "bool",
                  title: "Keep re-asserting the desired state every minute",
                  description: "OFF: switch only when the decision changes (dusk, weather, end of season), then leave " +
                               "the devices alone — a manual tap or another app can override until the next change. " +
                               "ON: drive them to the wanted state every minute, which heals a dropped Z-Wave command " +
                               "or a hand-flipped switch, but will fight any other app driving the same devices.",
                  defaultValue: false
            input "announceChanges", "bool", title: "Announce when this schedule switches", defaultValue: true
        }
    }
}

def installed() { initialize() }

def updated() {
    unsubscribe()
    initialize()
}

def initialize() {
    state.lastWanted = null
    evaluate()
}

// ---------------------------------------------------------------- the decision
// Called by the parent every minute and on every sensor change. Everything is derived
// from now(), so there is no stored state that can drift out of sync with reality.

// A half-filled child must never touch a device, and must never blow up the parent's
// page by having the parent call into it.
def configured() {
    if (!devices || !startMonth || !startDay || !endMonth || !endDay || !offTime) return false
    if (mode() == "time" && !onTime) return false
    return true
}

def mode() { onWhen ?: "dark" }

def evaluate() {
    if (!configured()) return

    def want    = desired()
    def changed = (want != state.lastWanted)

    // Without re-assert we act only when the decision itself changes, so another app
    // (All Decorations, a manual tap) can override us until the next real transition.
    // With it, we drive the devices to the wanted state every minute — which heals a
    // dropped Z-Wave command, and will also fight anything else driving these devices.
    if (changed || reassert) {
        devices?.each { d ->
            def isOn = d.currentValue("switch") == "on"
            if (want && !isOn)      d.on()
            else if (!want && isOn) d.off()
            else if (reassert)      { want ? d.on() : d.off() }
        }
    }

    if (changed) {
        if (state.lastWanted != null && announceChanges != false) {
            parent.announce("${app.label}: turning ${want ? 'on' : 'off'}${want ? '' : ' — ' + offReason()}")
        }
        state.lastWanted = want
    }
}

def desired() {
    if (!inSeason())                                 return false
    if (weatherProtected() && parent.weatherUnsafe()) return false
    if (hsmBlocked())                                return false
    return inWindow()
}

def offReason() {
    if (!inSeason())                                 return "out of season"
    if (weatherProtected() && parent.weatherUnsafe()) return "wind or rain"
    if (hsmBlocked())                                return "security is ${location.hsmStatus}"
    return "end of the evening"
}

// defaultValue does not reach settings until the page is submitted, so a null here
// means "never saved", not "unticked". Weather protection must fail closed.
def weatherProtected() { weatherProtect != false }

def hsmBlocked() {
    hsmOff && hsmOff.contains(location.hsmStatus)
}

// mm-dd compare as a plain integer, so a range that wraps the new year is just the
// inverted test rather than a special case.
def inSeason() {
    def today = new Date().format("Mdd", location.timeZone) as Integer
    def s = mmdd(startMonth, startDay)
    def e = mmdd(endMonth, endDay)
    return (s <= e) ? (today >= s && today <= e) : (today >= s || today <= e)
}

def mmdd(m, d) { ((m as Integer) * 100) + (d as Integer) }

// The evening window, handling one that runs past midnight: if "off" is not after
// "on", it belongs to the next day, and we must also test yesterday's window because
// right now might be inside it.
def inWindow() {
    def now = new Date()
    def on  = onMoment()
    def off = timeToday(offTime, location.timeZone)
    if (on == null) return false

    if (off <= on) off = off + 1
    if (now >= on && now < off) return true

    return now >= (on - 1) && now < (off - 1)
}

def onMoment() {
    def tz = location.timeZone

    if (mode() == "time") return timeToday(onTime, tz)

    def earliest = notBefore ? timeToday(notBefore, tz) : null

    if (mode() == "sunset") {
        def s = new Date(location.sunset.time + ((sunsetOffset ?: 0) as Integer) * 60000L)
        return (earliest != null && earliest > s) ? earliest : s
    }

    // "dark": there is no fixed moment — it is on as soon as the parent says it is dark.
    // The floor is what stops this also being true before dawn, so default it rather
    // than letting a blank setting mean midnight.
    if (!parent.isDark()) return null
    return earliest ?: timeToday("12:00", tz)
}

// ---------------------------------------------------------------- status

def overlapsWith(other) {
    if (!configured() || !other.configured()) return false

    def a1 = mmdd(startMonth, startDay), a2 = mmdd(endMonth, endDay)
    def b1 = other.rangeStart(),         b2 = other.rangeEnd()
    return (101..1231).any { d ->
        def inA = (a1 <= a2) ? (d >= a1 && d <= a2) : (d >= a1 || d <= a2)
        def inB = (b1 <= b2) ? (d >= b1 && d <= b2) : (d >= b1 || d <= b2)
        inA && inB
    }
}

def rangeStart() { mmdd(startMonth, startDay) }
def rangeEnd()   { mmdd(endMonth, endDay) }

def rangeText() {
    "${MONTHS()[startMonth]} ${startDay} &ndash; ${MONTHS()[endMonth]} ${endDay}"
}

def summaryHtml() {
    if (!configured()) return "<span style='color:#e67e22;font-size:1.1em'>&#9679;</span> <b>${app.label}</b> &nbsp;·&nbsp; <i>not finished — open it and complete the setup</i>"

    def want = desired()
    def col  = want ? "#27ae60" : "#95a5a6"
    def bits = ["<b>${app.label}</b>", rangeText()]
    bits << (want ? "<b>on</b>" : "off — ${offReason()}")
    bits << (weatherProtected() ? "weather-protected" : "indoor")
    if (devices) bits << "${devices.size()} device${devices.size() == 1 ? '' : 's'}"
    return "<span style='color:${col};font-size:1.1em'>&#9679;</span> ${bits.join(' &nbsp;·&nbsp; ')}"
}

def statusHtml() {
    def rows = [
        ["Right now", desired() ? "<b>should be on</b>" : "should be off — ${offReason()}"],
        ["Season",    "${rangeText()} &nbsp;·&nbsp; <b>${inSeason() ? 'in season' : 'out of season'}</b>"],
        ["Weather",   weatherProtected()
            ? (parent.weatherUnsafe() ? "wind/rain protected — <b>currently unsafe</b>" : "wind/rain protected — clear")
            : "<i>ignores wind and rain (indoor)</i>"],
    ]
    devices?.each { d ->
        rows << [rows.any { it[0] == "Devices" } ? "" : "Devices", "${d.displayName}: <b>${d.currentValue('switch')}</b>"]
    }
    def s = new StringBuilder("<table style='border-collapse:collapse'>")
    rows.each {
        s << "<tr><td style='padding:3px 14px 3px 0;white-space:nowrap;vertical-align:top;opacity:.6'>${it[0]}</td>"
        s << "<td style='padding:3px 0'>${it[1]}</td></tr>"
    }
    return (s << "</table>").toString()
}
