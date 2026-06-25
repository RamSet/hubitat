/**
 *  Local Ecobee Open-Contact Pause  (child app)
 *
 *  Pauses the local Ecobee HAP Thermostat (sets mode to off) whenever ANY
 *  monitored contact sensor is open, and restores the previous mode once all
 *  are closed. Whole-system pause — any open contact anywhere stops the HVAC.
 *  Fully offline.
 *
 *  Child of: Local Ecobee Helpers (RamSet)
 *
 *  Author: RamSet
 *  Version: 1.0.0 (2026-06-24)
 *  Version history:
 *    1.0.0 - Initial release. Minute-based delays, humanized duration, optional pause/resume notifications.
 *
 *  DISCLAIMER: Provided as-is, without warranty of any kind. You are solely
 *  responsible for the safe operation of your HVAC system and connected devices.
 *  Use at your own risk.
 */
definition(
    name:        "Local Ecobee Open-Contact Pause",
    namespace:   "RamSet",
    author:      "RamSet",
    description: "Pauses the local Ecobee HAP Thermostat while any monitored contact is open.",
    category:    "Convenience",
    parent:      "RamSet:Local Ecobee Helpers",
    iconUrl:     "",
    iconX2Url:   "",
    importUrl:   "https://raw.githubusercontent.com/RamSet/hubitat/main/apps/ecobee-hap-helpers/ecobee-hap-contact-pause-child.groovy"
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Open-Contact Pause", install: true, uninstall: true) {
        section("Contacts") {
            label title: "Name for this Open-Contact Pause", required: true
            input "contacts", "capability.contactSensor", title: "Window/door contact sensor(s)", multiple: true, required: true
        }
        section("Behavior") {
            input "openDelay",  "decimal", title: "Delay before pausing after an open (minutes)",        defaultValue: 0, range: "0..240", required: true
            input "closeDelay", "decimal", title: "Delay before resuming after all are closed (minutes)", defaultValue: 0, range: "0..240", required: true
        }
        section("Notifications") {
            input "notifier", "capability.notification", title: "Send notifications to (optional)", multiple: true, required: false
        }
        section {
            paragraph "When any contact opens, the thermostat's current mode is remembered and it is set to <b>off</b>. " +
                      "When all contacts close, the previous mode is restored. Any open contact anywhere pauses the entire HVAC."
            if (state.paused) paragraph "<span style='color:#b36b00'>Currently PAUSED (was: ${state.priorMode}).</span>"
        }
    }
}

def installed() { initialize() }
def updated()   { unsubscribe(); unschedule(); initialize() }

def initialize() {
    subscribe(contacts, "contact", contactHandler)
    contactHandler(null)   // sync to current state on (re)install
}

private boolean anyOpen() {
    contacts?.any { it.currentValue("contact") == "open" }
}

def contactHandler(evt) {
    if (anyOpen()) {
        unschedule(doResume)
        int os = toSeconds(openDelay)
        if (os > 0) runIn(os, doPause) else doPause()
    } else {
        unschedule(doPause)
        int cs = toSeconds(closeDelay)
        if (cs > 0) runIn(cs, doResume) else doResume()
    }
}

def doPause() {
    if (!anyOpen()) return
    if (state.paused) return
    def t = parent?.getThermostat()
    if (!t) return
    state.priorMode = t.currentValue("thermostatMode")
    state.paused = true
    t.off()
    log.info "Open-Contact Pause '${app.label}': contact open → HVAC off (was ${state.priorMode})"
    String dur = toSeconds(openDelay) > 0 ? " for ${humanDelay(openDelay)}" : ""
    sendNote("HVAC paused: ${openContactNames()} open${dur}. Thermostat is now OFF (was ${state.priorMode}).")
}

def doResume() {
    if (anyOpen()) return
    if (!state.paused) return
    def t = parent?.getThermostat()
    if (!t) return
    def m = state.priorMode ?: "auto"
    state.paused = false
    t.setThermostatMode(m)
    log.info "Open-Contact Pause '${app.label}': all closed → restored ${m}"
    sendNote("All contacts closed. Thermostat is back ON (${m}).")
}

private int toSeconds(mins) {
    Math.round(((mins ?: 0) as double) * 60.0d) as int
}

// "45s" under a minute, otherwise "N min" (one decimal if needed)
private String humanDelay(mins) {
    int secs = toSeconds(mins)
    if (secs < 60) return "${secs}s"
    double m = secs / 60.0d
    return (m == Math.floor(m)) ? "${m as int} min" : "${Math.round(m * 10.0d) / 10.0d} min"
}

private String openContactNames() {
    def names = contacts?.findAll { it.currentValue("contact") == "open" }?.collect { it.displayName }
    return names ? names.join(", ") : "contact(s)"
}

private void sendNote(String msg) {
    notifier?.each { it.deviceNotification(msg) }
    log.info "Open-Contact Pause '${app.label}': notify → ${msg}"
}
