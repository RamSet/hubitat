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
 *  Version: 1.1.0 (2026-07-01)
 *  Version history:
 *    1.1.0 - Reliability + visibility. Now tracks each contact's state from its EVENT value (authoritative)
 *            instead of re-reading currentValue inside the handler — that read can lag the just-fired event,
 *            so the app could miss an open and skip pausing (worst with a 0-minute delay). Added a 5-minute
 *            re-sync that recovers any missed event, and a live per-contact status list on the app page.
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
        section("Status") {
            def m = state.contactOpen ?: [:]
            def lines = contacts?.collect { c ->
                boolean open = m.containsKey(c.id as String) ? m[c.id as String] : (c.currentValue("contact") == "open")
                (open ? "🔴 <b>OPEN</b>" : "🟢 closed") + " — ${c.displayName}"
            }
            paragraph (lines ? lines.join("<br>") : "No contacts selected yet.")
            paragraph state.paused ? "<span style='color:#b36b00'><b>HVAC PAUSED</b> (was: ${state.priorMode}).</span>" : "HVAC running (not paused)."
        }
        section {
            paragraph "When any contact opens, the thermostat's current mode is remembered and it is set to <b>off</b>. " +
                      "When all contacts close, the previous mode is restored. Any open contact anywhere pauses the entire HVAC."
        }
    }
}

def installed() { initialize() }
def updated()   { unsubscribe(); unschedule(); initialize() }

def initialize() {
    subscribe(contacts, "contact", contactHandler)
    seedContactStates()          // seed from live values on (re)install
    runEvery5Minutes("resync")   // self-heal: recover if a contact event is ever missed
    evaluatePause()              // sync to current state now
}

// Track each contact's open/closed from its EVENT value (authoritative). Re-reading currentValue inside
// the handler can lag the just-fired event, which made the app miss an open and skip pausing.
private void seedContactStates() {
    def m = [:]
    contacts?.each { m[it.id as String] = (it.currentValue("contact") == "open") }
    state.contactOpen = m
}

def contactHandler(evt) {
    if (evt?.deviceId != null) {
        def m = state.contactOpen ?: [:]
        m[evt.deviceId as String] = (evt.value == "open")
        state.contactOpen = m
    }
    evaluatePause()
}

// periodic safety net: re-read live values and re-evaluate, so a missed event still gets corrected
def resync() { seedContactStates(); evaluatePause() }

private boolean anyOpen() {
    def m = state.contactOpen ?: [:]
    if (m.values().any { it }) return true                        // tracked state says something is open
    return contacts?.any { it.currentValue("contact") == "open" } // safety net for any not-yet-tracked device
}

private void evaluatePause() {
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
    def m = state.contactOpen ?: [:]
    def names = contacts?.findAll { m.containsKey(it.id as String) ? m[it.id as String] : (it.currentValue("contact") == "open") }?.collect { it.displayName }
    return names ? names.join(", ") : "contact(s)"
}

private void sendNote(String msg) {
    notifier?.each { it.deviceNotification(msg) }
    log.info "Open-Contact Pause '${app.label}': notify → ${msg}"
}
