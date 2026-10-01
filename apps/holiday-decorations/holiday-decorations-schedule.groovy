/**
 *  Holiday Decorations Schedule (child of "Holiday Decorations")
 *
 *  One instance per decoration group. Each says: which devices, which calendar range it
 *  is live for, when in the evening it should be on, and what conditions veto it.
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
                  description: "On for anything outdoors, such as inflatables. Off for anything indoors — indoor decorations do not care about the weather.",
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

// id -> name, for the parent's shared-device overlap check
def deviceMap() {
    (devices ?: []).collectEntries { [(it.id as String): it.displayName] }
}

def summaryHtml() {
    if (!configured()) return pill("not finished — open it and complete the setup", "amber")
    def want = desired()
    String main = want ? pill("on", "green") : pill("off — ${idleReason()}", offColor())
    def bits = [rangeText(), weatherProtected() ? "weather-protected" : "indoor"]
    if (devices) bits << "${devices.size()} device${devices.size() == 1 ? '' : 's'}"
    return main + " <small>${bits.join(' · ')}</small>"
}

def statusHtml() {
    def rows = []
    def want = desired()
    rows << row("Right now", want ? pill("should be on", "green") : pill("should be off — ${idleReason()}", offColor()))
    rows << row("Season", pill(inSeason() ? "in season" : "out of season", inSeason() ? "green" : "grey") + " <small>${rangeText()}</small>")
    rows << row("Evening", "<small>${onText()} · off at ${fmtTime(offTime)}</small>")
    rows << row("Weather", weatherProtected()
        ? (parent.weatherUnsafe() ? pill("unsafe — held off", "amber") : pill("clear", "green"))
        : pill("ignored — indoor", "grey"))
    if (hsmOff) {
        rows << row("Security", hsmBlocked() ? pill("held off — ${location.hsmStatus}", "amber")
                                             : pill("${location.hsmStatus ?: 'unknown'} — ok", "green"))
    }
    devices?.each { d ->
        boolean isOn = d.currentValue("switch") == "on"
        String txt = isOn ? "on" : "off"
        String col = isOn ? "green" : "grey"
        if (want != isOn) { txt += " — expected ${want ? 'on' : 'off'}"; col = "amber" }
        rows << row("Device — ${d.displayName}", pill(txt, col))
    }
    return rows.join("<br>")
}

// grey = the normal off states, amber = held off by a veto
def offColor() {
    if (!inSeason()) return "grey"
    if (weatherProtected() && parent.weatherUnsafe()) return "amber"
    if (hsmBlocked()) return "amber"
    return "grey"
}

// For the page only: "end of the evening" is also what offReason() says all day before the
// decorations come on, so say when they will instead. Announcements keep offReason().
def idleReason() {
    String r = offReason()
    if (r != "end of the evening") return r
    def on = onMoment()
    if (on == null) return "not dark yet"
    if (new Date() < on) return "comes on at ${on.format('h:mm a', location.timeZone)}"
    return r
}

def onText() {
    switch (mode()) {
        case "time":
            return "on at ${fmtTime(onTime)}"
        case "sunset":
            int off = (sunsetOffset ?: 0) as Integer
            String rel = off == 0 ? "at sunset" : (off < 0 ? "${-off} min before sunset" : "${off} min after sunset")
            return "on ${rel}" + (notBefore ? ", not before ${fmtTime(notBefore)}" : "")
        default:
            return "on when it gets dark, not before ${notBefore ? fmtTime(notBefore) : 'noon'}"
    }
}

def fmtTime(t) {
    if (!t) return "?"
    try { return timeToday(t as String, location.timeZone).format("h:mm a", location.timeZone) } catch (ignored) { return t as String }
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
