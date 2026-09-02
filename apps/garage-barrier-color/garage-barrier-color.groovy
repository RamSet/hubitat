/**
 *  Garage Barrier Colour
 *
 *  Colours a light to show whether the garage door is open or closed: one colour while
 *  the contact reads open, another while it reads closed.
 *
 *  Replaces Rule Machine rule "5. Garage dooor barrier red green", which lived on the C8
 *  and drove a lightstrip that is native to the C-5 over Hub Mesh. Running it on the hub
 *  that owns the light removes that round trip.
 *
 *  The original rule set the colour three times, once with a one-second delay, which
 *  reads as compensation for a dropped command across the mesh. That is kept here as a
 *  configurable repeat rather than hard-coded, because on the hub that owns the light it
 *  may well be unnecessary — set repeats to 1 and see whether the colour still lands.
 *
 *  Author: RamSet — https://github.com/RamSet/hubitat
 */

import groovy.transform.Field

definition(
    name:        "Garage Barrier Colour",
    namespace:   "ramset",
    author:      "RamSet",
    description: "Sets a light to one colour while the garage door is open and another while it is closed",
    category:    "Convenience",
    iconUrl:     "",
    iconX2Url:   "",
    importUrl:   "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/apps/garage-barrier-color/garage-barrier-color.groovy"
)

preferences {
    page(name: "mainPage")
}

// Hubitat hue is 0-100, not 0-360. These are the values the built-in colour picker uses.
@Field static final Map COLOURS = [
    "Red":    [hue: 0,  saturation: 100],
    "Green":  [hue: 33, saturation: 100],
    "Blue":   [hue: 66, saturation: 100],
    "Amber":  [hue: 11, saturation: 100],
    "Yellow": [hue: 16, saturation: 100],
    "Purple": [hue: 82, saturation: 100],
    "White":  [hue: 0,  saturation: 0]
]

def mainPage() {
    dynamicPage(name: "mainPage", title: "Garage Barrier Colour", install: true, uninstall: true) {

        section("Status") {
            paragraph statusText()
        }

        section("Door") {
            input name: "barrier", type: "capability.contactSensor",
                  title: "Select sensor", required: true, submitOnChange: true
        }

        section("Light") {
            input name: "lights", type: "capability.colorControl",
                  title: "Select light(s)", multiple: true, required: true, submitOnChange: true
            input name: "openColour", type: "enum", title: "Colour when open",
                  options: COLOURS.keySet() as List, defaultValue: "Red", required: true
            input name: "closedColour", type: "enum", title: "Colour when closed",
                  options: COLOURS.keySet() as List, defaultValue: "Green", required: true
            input name: "setLevel", type: "number", title: "Brightness %",
                  description: "Optional", range: "1..100", required: false
        }

        section("Turn off") {
            paragraph "While the door is open the light stays on. The timer only runs once it closes."
            input name: "autoOffMins", type: "number",
                  title: "Turn light off (minutes) after closed",
                  description: "0 = leave on", defaultValue: 1, range: "0..1440", required: true
        }

        section("Options") {
            input name: "repeats", type: "number", title: "Times to send colour",
                  defaultValue: 2, range: "1..5", required: true
            input name: "repeatSecs", type: "decimal", title: "Seconds between repeats",
                  defaultValue: 1, range: "0.5..10", required: true
            input name: "applyOnSave", type: "bool", title: "Apply current state on save",
                  defaultValue: true
            input name: "txtEnable", type: "bool", title: "Log each colour change", defaultValue: true
            input name: "logEnable", type: "bool", title: "Debug logging", defaultValue: false
            label title: "App name", required: false
        }
    }
}

private String statusText() {
    if (!settings.barrier || !settings.lights) return "Pick a contact sensor and a light to get started."
    String c = settings.barrier.currentValue("contact") ?: "unknown"
    String want = (c == "open") ? (settings.openColour ?: "Red") : (settings.closedColour ?: "Green")
    Integer off = autoOffMins()
    String tail = (off > 0) ? ((c == "closed") ? " Off after ${off} min." : " Stays on while open.") : ""
    return "Door is <b>${c}</b> → light should be <b>${want}</b>.${tail}"
}

def installed() { initialize() }
def updated()   { unsubscribe(); unschedule(); initialize() }

def initialize() {
    subscribe(settings.barrier, "contact", contactHandler)
    logInfo "watching ${settings.barrier?.displayName} → ${settings.lights*.displayName?.join(', ')}"
    if (settings.applyOnSave != false) {
        // Sync now rather than leaving the light showing a stale colour until the door
        // next moves — which, for a garage door, can be a long time.
        apply(settings.barrier?.currentValue("contact"))
    }
}

def contactHandler(evt) {
    logDebug "contact event: ${evt.value}"
    apply(evt.value)
}

private void apply(String contactValue) {
    if (contactValue == null) { logDebug "no contact value yet — nothing to apply"; return }
    String name = (contactValue == "open") ? (settings.openColour ?: "Red") : (settings.closedColour ?: "Green")
    Map c = COLOURS[name]
    if (c == null) { log.warn "${app.label}: unknown colour '${name}'"; return }

    Map cmd = [hue: c.hue, saturation: c.saturation]
    if (settings.setLevel) cmd.level = (settings.setLevel as Integer)

    state.pending = cmd
    state.pendingName = name
    state.left = Math.max(1, (settings.repeats == null ? 2 : (settings.repeats as Integer)))

    // Auto-off means the light is usually OFF when the next door event lands, so the
    // colour would go to a dark bulb and never be seen. Switch on first. hasCommand,
    // not respondsTo — respondsTo is always false for driver commands inside an app.
    if (autoOffMins() > 0) settings.lights?.each { if (it.hasCommand("on")) it.on() }

    if (txtEnable != false) log.info "${app.label}: door ${contactValue} → ${name}"
    sendColour()
    armAutoOff(contactValue)
}

private Integer autoOffMins() {
    return (settings.autoOffMins == null ? 1 : (settings.autoOffMins as Integer))
}

// Only counts down once the door is CLOSED — an open garage is the state worth keeping
// visible, so the light stays lit for as long as it is open. Any door event clears the
// pending timer first, so re-opening cancels a countdown already in flight instead of
// switching the light off part-way through showing the open colour.
private void armAutoOff(String contactValue) {
    unschedule("autoOff")
    if (contactValue != "closed") { logDebug "door ${contactValue} — light stays on"; return }
    Integer m = autoOffMins()
    if (m > 0) { runIn(m * 60, "autoOff"); logDebug "auto-off armed for ${m} min" }
}

def autoOff(data = null) {
    settings.lights?.each { if (it.hasCommand("off")) it.off() }
    if (txtEnable != false) log.info "${app.label}: auto-off — ${autoOffMins()} min after close"
}

// Repeats are scheduled rather than looped so the app never sits blocking, and each
// pass re-reads state so a door that flips mid-sequence cancels the stale colour.
def sendColour() {
    Map cmd = state.pending
    if (cmd == null) return
    settings.lights?.each { it.setColor(cmd) }
    logDebug "sent ${state.pendingName} ${cmd} (${state.left} left)"
    state.left = (state.left as Integer) - 1
    if ((state.left as Integer) > 0) {
        BigDecimal gap = (settings.repeatSecs == null ? 1 : settings.repeatSecs) as BigDecimal
        runInMillis((long)(gap * 1000), "sendColour")
    } else {
        state.remove("pending"); state.remove("pendingName"); state.remove("left")
    }
}

private void logInfo(String m)  { if (txtEnable != false) log.info  "${app.label}: ${m}" }
private void logDebug(String m) { if (logEnable)          log.debug "${app.label}: ${m}" }
