/**
 *  Fan Timer
 *
 *  Runs a fan for a fixed time, started by virtual switches you can expose to a voice
 *  assistant or a dashboard. Press "30 min", the fan runs for thirty minutes and stops.
 *
 *  Install one instance per fan.
 *
 *  The button you pressed stays on for as long as the fan is running, so whatever is
 *  showing that switch tells you both that the fan is on and which duration is counting
 *  down. Pressing a different duration re-times it; pressing Off, or turning the fan off
 *  by hand, cancels everything.
 *
 *  A single timer is rescheduled rather than several timers cancelling each other, so
 *  there is nothing to leave dangling. A maximum runtime backstops the lot: however the
 *  fan was started, it cannot run past it.
 */

definition(
    name:        "Fan Timer",
    namespace:   "ramset",
    author:      "RamSet",
    description: "Run a fan for a set time from virtual switches, with an optional power-triggered run and a maximum runtime",
    category:    "Convenience",
    iconUrl:     "",
    iconX2Url:   "",
    importUrl:   "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/apps/fan-timer/fan-timer.groovy"
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Fan Timer", install: true, uninstall: true) {
        section("Status") {
            paragraph fan ? statusHtml() : "<i>Pick a fan below to see live status here.</i>"
        }
        section("Fan") {
            input "fan", "capability.switch", title: "The fan to run", required: true, submitOnChange: true
            label title: "Name this app", required: false
        }
        section("Duration buttons") {
            paragraph "Each button is a virtual switch. Turning it on runs the fan for that many " +
                      "minutes. Expose these to HomeKit or a dashboard and they behave like presets."
            input "buttonCount", "enum", title: "How many durations?",
                  options: ["1","2","3","4","5"], defaultValue: "3", required: true, submitOnChange: true

            (1..(buttonCount ? buttonCount as Integer : 3)).each { i ->
                input "btn${i}",  "capability.switch", title: "Button ${i}", required: false, width: 6
                input "mins${i}", "number", title: "runs the fan for (minutes)", required: false, width: 6
            }
        }
        section("Off button") {
            input "offSwitch", "capability.switch", title: "Virtual switch that stops the fan", required: false
        }
        section("Run while something else draws power (optional)") {
            paragraph "For a fan that should run whenever an appliance is working — a water heater, " +
                      "say. The fan follows the power draw and is not on a timer while it does."
            input "powerMeter", "capability.powerMeter", title: "Power meter to watch", required: false, submitOnChange: true
            if (powerMeter) {
                input "powerOn",  "number", title: "Run the fan while this is at or above (watts)", defaultValue: 5, required: true
                input "powerFor", "number", title: "...and has been for this many minutes", defaultValue: 3, required: true
            }
        }
        section("Safety") {
            input "maxRuntime", "number",
                  title: "Never run the fan longer than (minutes)",
                  description: "A backstop, however the fan was started. Set higher than your longest button.",
                  defaultValue: 90, required: true
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
    buttons().each { b -> subscribe(b.sw, "switch", buttonHandler) }
    if (offSwitch)  subscribe(offSwitch, "switch.on", offHandler)
    if (fan)        subscribe(fan, "switch", fanHandler)
    if (powerMeter) subscribe(powerMeter, "power", powerHandler)

    logDebug "initialized — ${buttons().size()} duration button(s), max runtime ${maxRuntime ?: 90} min"
}

// The configured duration buttons, as [sw: <device>, mins: n, key: "btn2"]. A button with
// no device or no minutes is simply not a button.
def buttons() {
    def out = []
    (1..5).each { i ->
        // settings is keyed by String; a GString key never matches, so these must be
        // forced to String or every lookup silently returns null.
        def sw = settings["btn${i}".toString()]
        def m  = settings["mins${i}".toString()]
        if (sw && m) out << [sw: sw, mins: m as Integer, key: "btn${i}".toString()]
    }
    return out
}

// ---------------------------------------------------------------- handlers

def buttonHandler(evt) {
    def b = buttons().find { it.sw.id?.toString() == evt.deviceId?.toString() }
    if (!b) return

    if (evt.value == "on") {
        start(b)
        return
    }
    // Switched off by hand while it was the one running: treat as a cancel.
    if (state.activeKey == b.key) {
        logDebug "${evt.displayName} switched off — cancelling"
        stopAll()
    }
}

def offHandler(evt) {
    logDebug "off button pressed"
    stopAll()
}

// If the fan is turned off anywhere else — by hand, by another app — do not keep a timer
// running against a fan that is no longer on.
def fanHandler(evt) {
    if (evt.value == "off" && (state.activeKey || state.powerRun)) {
        logDebug "fan switched off elsewhere — clearing the timer"
        clear()
    }
}

def powerHandler(evt) {
    def w = toBigDecimal(evt.value)
    if (w == null || powerOn == null) return

    if (w >= powerOn) {
        if (!state.powerRun) runIn(((powerFor ?: 0) as Integer) * 60, "powerSustained")
        return
    }

    unschedule("powerSustained")
    if (state.powerRun) {
        state.powerRun = false
        // A duration button beats the power trigger: do not cut a timed run short.
        if (!state.activeKey) {
            logDebug "${powerMeter.displayName} dropped below ${powerOn}W — fan off"
            fan.off()
            unschedule("hardOff")
        }
    }
}

def powerSustained() {
    state.powerRun = true
    logDebug "${powerMeter.displayName} above ${powerOn}W for ${powerFor} min — fan on"
    fan.on()
    armMaxRuntime()
}

// ---------------------------------------------------------------- the timer

def start(Map b) {
    logDebug "running the fan for ${b.mins} minutes"

    state.activeKey = b.key
    state.until     = now() + (b.mins * 60000L)

    fan.on()
    // Only the pressed button stays on, so whatever is showing these switches reflects
    // which duration is actually counting down.
    buttons().each { if (it.key != b.key) it.sw.off() }
    offSwitch?.off()

    runIn(b.mins * 60, "expire")   // overwrites any timer already running
    armMaxRuntime()
}

def expire() {
    logDebug "timer finished — fan off"
    fan.off()
    clear()
}

def stopAll() {
    fan.off()
    clear()
}

// Put everything back to rest: no timer, no lit buttons.
def clear() {
    unschedule("expire")
    unschedule("hardOff")
    unschedule("powerSustained")
    state.activeKey = null
    state.until     = null
    state.powerRun  = false
    buttons().each { it.sw.off() }
    offSwitch?.off()
}

def armMaxRuntime() {
    runIn(((maxRuntime ?: 90) as Integer) * 60, "hardOff")
}

// However the fan was started, it cannot outlive this.
def hardOff() {
    log.warn "${fan.displayName} hit the ${maxRuntime ?: 90} minute maximum runtime — forcing it off"
    fan.off()
    clear()
}

// ---------------------------------------------------------------- status

def statusHtml() {
    def rows = []
    def on   = fan.currentValue("switch") == "on"

    rows << ["Fan", on
        ? "${dot('#27ae60')} <b>${fan.displayName} is on</b>"
        : "${dot('#95a5a6')} ${fan.displayName} is off"]

    if (state.until) {
        def left = ((state.until - now()) / 60000L) as Integer
        def b    = buttons().find { it.key == state.activeKey }
        rows << ["Timer", "<b>${left < 1 ? 'less than a minute' : left + ' min'}</b> left of the " +
                          "${b ? b.mins : '?'} minute run"]
    } else if (state.powerRun) {
        rows << ["Timer", "no timer — following ${powerMeter?.displayName} (${powerMeter?.currentValue('power')} W)"]
    } else {
        rows << ["Timer", "<i>not running</i>"]
    }

    buttons().eachWithIndex { b, i ->
        rows << [i == 0 ? "Buttons" : "", "${b.sw.displayName}: <b>${b.sw.currentValue('switch')}</b> &nbsp;·&nbsp; ${b.mins} min"]
    }
    if (powerMeter) {
        rows << ["Power", "${powerMeter.displayName}: <b>${powerMeter.currentValue('power')} W</b> (runs the fan at or above ${powerOn} W)"]
    }

    def s = new StringBuilder("<table style='border-collapse:collapse'>")
    rows.each {
        s << "<tr><td style='padding:3px 14px 3px 0;white-space:nowrap;vertical-align:top;opacity:.6'>${it[0]}</td>"
        s << "<td style='padding:3px 0'>${it[1]}</td></tr>"
    }
    s << "</table>"
    return s.toString()
}

def dot(String color) { "<span style='color:${color};font-size:1.1em'>&#9679;</span>" }

def toBigDecimal(v) {
    if (v == null) return null
    try { return v as BigDecimal } catch (e) { return null }
}

def logDebug(msg) { if (logEnable) log.debug msg }
