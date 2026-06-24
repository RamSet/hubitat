/**
 *  Local Ecobee Open-Contact Pause  (child app)
 *
 *  Pauses the local Ecobee HAP Thermostat (sets mode to off) whenever ANY
 *  monitored contact sensor is open, and restores the previous mode once all
 *  are closed. Whole-system pause — any open contact anywhere stops the HVAC.
 *  Fully offline.
 *
 *  Child of: Local Ecobee Helpers (RamSet)
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
    importUrl:   "http://10.33.47.84/RamSet/hubitat/raw/branch/main/ecobee-hap-helpers/ecobee-hap-contact-pause-child.groovy"
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
            input "openDelay",  "number", title: "Delay before pausing after an open (seconds)",        defaultValue: 0, range: "0..3600", required: true
            input "closeDelay", "number", title: "Delay before resuming after all are closed (seconds)", defaultValue: 0, range: "0..3600", required: true
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
        if ((openDelay ?: 0) > 0) runIn(openDelay as int, doPause)
        else doPause()
    } else {
        unschedule(doPause)
        if ((closeDelay ?: 0) > 0) runIn(closeDelay as int, doResume)
        else doResume()
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
    String dur = (openDelay ?: 0) > 0 ? " for ${openDelay}s" : ""
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

private String openContactNames() {
    def names = contacts?.findAll { it.currentValue("contact") == "open" }?.collect { it.displayName }
    return names ? names.join(", ") : "contact(s)"
}

private void sendNote(String msg) {
    notifier?.each { it.deviceNotification(msg) }
    log.info "Open-Contact Pause '${app.label}': notify → ${msg}"
}
