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
 *  Version: 1.3.0 (2026-10-06)
 *  Version history:
 *    1.3.0 - Enforces the thermostat's mode instead of trusting its own "paused" flag. On 2026-10-06 the app
 *            turned the thermostat off at 07:36 with windows open; at 16:40 something outside the app set it back
 *            to cool and it ran three cooling cycles while the page still said PAUSED, because the 5-minute
 *            re-sync only re-ran doPause(), which returns early once paused. Now: while paused, any mode other
 *            than off is turned off again (immediately on the thermostat's mode event, and on every 5-minute
 *            re-sync), with a notification. After a resume, the restored mode is checked for 15 minutes and
 *            re-sent if the thermostat did not take it. Long pause durations now read "12 h 25 min".
 *    1.2.0 - Current-status block at the top of the page (HVAC paused/running, a pending pause or resume
 *            with its countdown, the thermostat, delays, one line per contact). A pending delay used to
 *            show as "open but NOT paused — press Done", which was wrong while the delay was still running.
 *    1.1.1 - No more silent failures: if a contact is open but the app can't pause because no thermostat
 *            is selected in the parent, it now says so on the Status page and logs a warning (was silent).
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
    importUrl:   "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/apps/ecobee-hap-helpers/ecobee-hap-contact-pause-child.groovy"
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Open-Contact Pause", install: true, uninstall: true) {
        section("<b>Current status</b>") {
            paragraph currentStatus()
        }
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
        }
    }
}

def installed() { initialize() }
def updated()   { unsubscribe(); unschedule(); initialize() }

def initialize() {
    subscribe(contacts, "contact", contactHandler)
    def t = parent?.getThermostat()
    if (t) subscribe(t, "thermostatMode", modeHandler)
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

// periodic safety net: re-read live values and re-evaluate, so a missed event still gets corrected,
// then check the thermostat itself — the app's own flag is not proof the thermostat obeyed.
def resync() { seedContactStates(); evaluatePause(); enforceMode() }

// The thermostat reported a mode change: correct it right away if it contradicts what we want.
def modeHandler(evt) { enforceMode() }

@groovy.transform.Field static final long RESUME_VERIFY_MS = 15 * 60000L
@groovy.transform.Field static final long RESEND_GAP_MS    = 60000L

// Make the thermostat's actual mode match the intended one.
//   paused            -> must be off; anything else is overridden back to off.
//   just resumed      -> for 15 min, must be the restored mode; re-sent if it did not take.
//   otherwise         -> hands off: a mode the user picks while all contacts are closed is theirs.
private void enforceMode() {
    def t = parent?.getThermostat()
    if (!t) return
    String mode = t.currentValue("thermostatMode")
    long nowMs = now()
    if (nowMs - ((state.lastEnforceMs ?: 0L) as long) < RESEND_GAP_MS) return
    if (state.paused) {
        if (!anyOpen() || mode == "off") return
        state.lastEnforceMs = nowMs
        log.warn "Open-Contact Pause '${app.label}': thermostat is '${mode}' while paused (${openContactNames()} open) — turning it off again"
        t.off()
        sendNote("Thermostat was switched back to ${mode} while ${openContactNames()} open. Turned it OFF again.")
        return
    }
    String want = state.resumeMode
    long since = (state.resumedAtMs ?: 0L) as long
    if (!want) return
    if (mode == want || nowMs - since > RESUME_VERIFY_MS) { state.resumeMode = null; return }
    state.lastEnforceMs = nowMs
    log.warn "Open-Contact Pause '${app.label}': resumed to '${want}' but thermostat reports '${mode}' — re-sending"
    t.setThermostatMode(want)
}

private boolean anyOpen() {
    def m = state.contactOpen ?: [:]
    if (m.values().any { it }) return true                        // tracked state says something is open
    return contacts?.any { it.currentValue("contact") == "open" } // safety net for any not-yet-tracked device
}

private void evaluatePause() {
    if (anyOpen()) {
        unschedule(doResume)
        state.resumeDueMs = null
        int os = toSeconds(openDelay)
        if (os > 0) { state.pauseDueMs = now() + os * 1000L; runIn(os, doPause) } else doPause()
    } else {
        unschedule(doPause)
        state.pauseDueMs = null
        int cs = toSeconds(closeDelay)
        if (cs > 0) { state.resumeDueMs = now() + cs * 1000L; runIn(cs, doResume) } else doResume()
    }
}

def doPause() {
    if (!anyOpen()) return
    if (state.paused) return
    def t = parent?.getThermostat()
    if (!t) { log.warn "Open-Contact Pause '${app.label}': ${openContactNames()} open but CANNOT pause — no thermostat is selected in the parent 'Local Ecobee Helpers' app"; return }
    state.priorMode = t.currentValue("thermostatMode")
    state.paused = true
    state.pausedAtMs = now()
    state.pauseDueMs = null
    state.resumeMode = null
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
    state.pausedAtMs = null
    state.resumeDueMs = null
    state.resumeMode = m
    state.resumedAtMs = now()
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
    if (secs >= 3600) {
        int total = (int) Math.round(secs / 60.0d)
        int rem = total % 60
        return "${total.intdiv(60)} h" + (rem ? " ${rem} min" : "")
    }
    double m = secs / 60.0d
    return (m == Math.floor(m)) ? "${m as int} min" : "${Math.round(m * 10.0d) / 10.0d} min"
}

private String openContactNames() {
    def names = contacts?.findAll { isOpen(it) }?.collect { it.displayName }
    return names ? names.join(", ") : "contact(s)"
}

// tracked event state first (authoritative), live value for a device not tracked yet
private boolean isOpen(c) {
    def m = state.contactOpen ?: [:]
    return m.containsKey(c.id as String) ? m[c.id as String] : (c.currentValue("contact") == "open")
}

// --- current status (page top) ---
private String currentStatus() {
    def rows = []
    rows << row("HVAC", hvacPill())
    def t = parent?.getThermostat()
    rows << row("Thermostat", t ? pill("${t.displayName} — ${t.currentValue('thermostatMode')}", "grey")
                                : pill("not selected in the parent Local Ecobee Helpers app", "red"))
    int os = toSeconds(openDelay)
    int cs = toSeconds(closeDelay)
    rows << row("Delays", "<small>pause ${os > 0 ? humanDelay(openDelay) + ' after' : 'as soon as'} a contact opens · " +
                          "resume ${cs > 0 ? humanDelay(closeDelay) + ' after' : 'as soon as'} all are closed</small>")
    if (contacts) {
        contacts.each { c ->
            boolean open = isOpen(c)
            rows << row("Contact — ${c.displayName}", pill(open ? "open" : "closed", open ? "red" : "green"))
        }
    } else {
        rows << row("Contacts", pill("none selected", "grey"))
    }
    return rows.join("<br>")
}

// The one pill that says what the app is doing right now; the parent shows it too.
private String hvacPill() {
    long nowMs = now()
    long pauseDue  = (state.pauseDueMs  ?: 0L) as long
    long resumeDue = (state.resumeDueMs ?: 0L) as long
    boolean open = anyOpen()
    if (state.paused) {
        if (!open && resumeDue > nowMs) return pill("PAUSED — resuming in ${leftIn(resumeDue)}", "amber")
        long since = (state.pausedAtMs ?: 0L) as long
        String dur = since > 0L ? " for ${humanDelay((nowMs - since) / 60000.0d)}" : ""
        String actual = parent?.getThermostat()?.currentValue("thermostatMode")
        if (open && actual && actual != "off") return pill("PAUSED but thermostat is ${actual} — turning it off", "red")
        return pill("PAUSED${dur} — was ${state.priorMode}", "amber")
    }
    if (open) {
        if (!parent?.getThermostat()) return pill("${openContactNames()} open but CANNOT pause — no thermostat selected in the parent", "red")
        if (pauseDue > nowMs) return pill("${openContactNames()} open — pausing in ${leftIn(pauseDue)}", "amber")
        return pill("${openContactNames()} open but NOT paused — press Done to re-arm", "red")
    }
    return pill("running — all contacts closed", "green")
}

// called by the parent app for its overview line
Map statusSummary() {
    int n = (contacts?.count { isOpen(it) } ?: 0) as int
    return [kind: "Open-Contact Pause", html: hvacPill() + (n ? " <small>${n} open</small>" : "")]
}

private String leftIn(long dueMs) { humanDelay(Math.max(0L, dueMs - now()) / 60000.0d) }

// --- status helpers (same look as the Blinds Dusk Automation status block) ---
private String row(String label, String value) {
    "<b>${label}:</b> ${value}"
}

private String pill(String text, String color) {
    def bg = [green:'#2e7d32', red:'#c62828', amber:'#ef6c00',
              blue:'#1565c0', indigo:'#4527a0', grey:'#616161'][color] ?: '#616161'
    "<span style='background:${bg};color:#fff;padding:2px 8px;border-radius:10px;font-size:0.85em;white-space:nowrap'>${text}</span>"
}

private void sendNote(String msg) {
    notifier?.each { it.deviceNotification(msg) }
    log.info "Open-Contact Pause '${app.label}': notify → ${msg}"
}
