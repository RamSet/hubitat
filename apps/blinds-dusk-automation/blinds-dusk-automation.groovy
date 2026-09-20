/**
 *  Blinds Dusk Automation
 *
 *  Standalone replacement for the Rule Machine "Room Blinds Dawn/Dusk"
 *  rule-set (originally one set of rules per room: Matt's and Maya's).
 *
 *  What it does, once per evening, per room:
 *    Trigger    - an illuminance sensor reports at/below a lux threshold
 *                 (the original used the Hub Variable "LightValue" = 200).
 *    Time gate  - only acts when it is dark out, i.e. between
 *                 (sunset - offset) and sunrise.
 *    If the window is OPEN  -> notify, wait for the window to close, then
 *                              notify again and lower (close) the blind.
 *    If the window is CLOSED -> close the blind immediately, and turn on the
 *                              room light only if motion is currently active.
 *    Reset      - at a configured morning time the app re-arms for the next
 *                 evening (replaces the original "Restore blinds logic" rule
 *                 that resumed the paused rules at 07:00).
 *
 *  Install this app once per room (Matt's Room, Maya's Room, ...). It keeps
 *  its own state, so no Hub Variables are required.
 */

definition(
    name:        "Blinds Dusk Automation",
    namespace:   "ramset",
    author:      "RamSet",
    description: "Lowers a room's blind at dusk, waiting for an open window to close first, and turns on the room light when occupied",
    category:    "Convenience",
    iconUrl:     "",
    iconX2Url:   "",
    importUrl:   "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/apps/blinds-dusk-automation/blinds-dusk-automation.groovy"
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "<b>Blinds Dusk Automation</b>", install: true, uninstall: true) {
        section("<b>Current status</b>") {
            paragraph currentStatus()
        }
        section("<b>Name</b>") {
            label title: "Name this instance (one per room)", required: true,
                  defaultValue: "Room Blinds Dusk"
        }
        section("<b>Trigger</b>") {
            input "lightSensors", "capability.illuminanceMeasurement",
                  title: "Illuminance sensor(s) that trigger evaluation", multiple: true, required: true
            input "luxThreshold", "number",
                  title: "Act when illuminance is at or below (lux)", defaultValue: 200, required: true
            input "useSunsetGate", "bool",
                  title: "Wait for the before-sunset gate (off = lower on lux alone, any time of day)",
                  defaultValue: true, submitOnChange: true
            if (settings.useSunsetGate != false) {
                input "sunsetOffset", "number",
                      title: "Start this many minutes BEFORE sunset", defaultValue: 15, required: true
            }
        }
        section("<b>Room devices</b>") {
            input "blinds", "capability.windowShade",
                  title: "Blind / shade to lower", multiple: true, required: true
            input "windowSensor", "capability.contactSensor",
                  title: "Window contact sensor (open = window open)", required: false
            input "windowCloseDelay", "number",
                  title: "Delay between the window closing and lowering the blind (seconds)",
                  defaultValue: 0, required: false
            input "motionSensor", "capability.motionSensor",
                  title: "Motion sensor (room light turns on only if active)", required: false
            input "roomLight", "capability.switch",
                  title: "Room light to turn on when occupied", required: false
        }
        section("<b>Confirm the blind actually closed</b>") {
            paragraph "Closing a shade is fire-and-forget: if the motor is offline or ignores the command, the app would otherwise finish for the night with the shade still up. With this on, a while after commanding close the app reads the shade back, re-sends close if it isn't closed, and alerts you if it never confirms."
            input "blindVerifyEnable", "bool", title: "Verify the blind reaches 'closed'", defaultValue: true
            input "blindTravelSec", "number", title: "Seconds to allow for the blind to finish closing before checking", defaultValue: 45, required: true
            input "blindRetries", "number", title: "Re-send close this many times before alerting", defaultValue: 2, required: true
        }
        section("<b>Notifications</b>") {
            input "notifiers", "capability.notification",
                  title: "Notification device(s)", multiple: true, required: false
            input "windowOpenMsg", "text", title: "Message when window is open",
                  defaultValue: "Window is open. Waiting for it to be closed in order to lower the blind."
            input "windowClosedMsg", "text", title: "Message when window has closed",
                  defaultValue: "Window is now closed. Lowering the blind."
        }
        section("<b>Reset</b>") {
            input "resetTime", "time",
                  title: "Re-arm for the next evening at", defaultValue: "07:00", required: true
        }
        section("<b>Logging</b>") {
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: true
        }
    }
}

def installed() {
    initialize()
}

def updated() {
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    state.actedTonight = state.actedTonight ?: false
    state.waitingForWindow = false
    subscribe(lightSensors, "illuminance", illuminanceHandler)
    def reset = toDateTime(resetTime)
    schedule("0 ${reset.format('m', location.timeZone)} ${reset.format('H', location.timeZone)} * * ?", resetHandler)
    if (logEnable) log.debug "initialized: armed=${!state.actedTonight}, threshold=${luxThreshold} lux"
}

def illuminanceHandler(evt) {
    Integer lux = (evt.value as BigDecimal).intValue()
    if (logEnable) log.debug "illuminance ${lux} lux from ${evt.displayName}"

    if (lux > (luxThreshold as Integer)) return
    if (state.actedTonight) return
    if (!isDark()) {
        if (logEnable) log.debug "below threshold but not yet dusk (sunset-${sunsetOffset} to sunrise); ignoring"
        return
    }

    // From here we act exactly once for the evening.
    state.actedTonight = true

    if (windowSensor && windowSensor.currentValue("contact") == "open") {
        if (logEnable) log.debug "window open: notifying and waiting for it to close"
        notify(windowOpenMsg)
        state.waitingForWindow = true
        subscribe(windowSensor, "contact.closed", windowClosedHandler)
    } else {
        lowerBlinds()
        if (motionSensor && motionSensor.currentValue("motion") == "active") {
            if (logEnable) log.debug "motion active: turning on room light"
            roomLight?.on()
        }
    }
}

def windowClosedHandler(evt) {
    if (!state.waitingForWindow) return
    state.waitingForWindow = false
    unsubscribe(windowSensor)
    notify(windowClosedMsg)
    Integer delay = (windowCloseDelay ?: 0) as Integer
    if (delay > 0) {
        if (logEnable) log.debug "window closed: lowering blind in ${delay}s"
        runIn(delay, "lowerBlinds")
    } else {
        if (logEnable) log.debug "window closed: lowering blind now"
        lowerBlinds()
    }
}

def resetHandler() {
    if (logEnable) log.debug "re-arming for the next evening"
    state.actedTonight = false
    if (state.waitingForWindow) {
        state.waitingForWindow = false
        if (windowSensor) unsubscribe(windowSensor)
    }
}

// --- helpers ---

// Live device/state readout for the app's config page. Re-evaluated every time
// the page is opened, so it always reflects the current values.
private String currentStatus() {
    def rows = []

    boolean armed = !(state.actedTonight)
    rows << row("Armed for tonight",
                armed ? pill("yes", "green") : pill("no — already acted", "grey"))

    boolean gateOff = (settings.useSunsetGate == false)
    boolean dark = lightSensors ? isDark() : false
    rows << row(gateOff ? "Dusk gate" : "Dark now (dusk gate)",
                gateOff ? pill("off — lux only", "grey")
                        : (lightSensors ? (dark ? pill("yes", "indigo") : pill("no", "amber")) : pill("—", "grey")))

    rows << row("Waiting for window to close",
                state.waitingForWindow ? pill("yes", "amber") : pill("no", "grey"))

    if (lightSensors) {
        lightSensors.each { s ->
            rows << row("Light — ${s.displayName}",
                        pill("${s.currentValue('illuminance')} lux", "blue") +
                        " <small>acts at &le; ${luxThreshold}</small>")
        }
    } else {
        rows << row("Light sensor", pill("not selected", "grey"))
    }

    if (blinds) {
        blinds.each { b ->
            def st = b.currentValue('windowShade') ?: b.currentValue('switch') ?: 'unknown'
            rows << row("Blind — ${b.displayName}",
                        pill(st, st in ['closed', 'off'] ? 'green' : (st == 'unknown' ? 'grey' : 'amber')))
        }
    } else {
        rows << row("Blind", pill("not selected", "grey"))
    }

    if (windowSensor) {
        def contact = windowSensor.currentValue('contact')
        rows << row("Window contact — ${windowSensor.displayName}",
                    pill(contact, contact == 'open' ? 'red' : 'green'))
    } else {
        rows << row("Window contact", pill("not selected", "grey"))
    }

    if (motionSensor) {
        def motion = motionSensor.currentValue('motion')
        rows << row("Motion — ${motionSensor.displayName}",
                    pill(motion, motion == 'active' ? 'green' : 'grey'))
    }
    if (roomLight) {
        def sw = roomLight.currentValue('switch')
        rows << row("Room light — ${roomLight.displayName}",
                    pill(sw, sw == 'on' ? 'amber' : 'grey'))
    }

    return rows.join("<br>")
}

// One status line: label + a value (usually a colored pill).
private String row(String label, String value) {
    "<b>${label}:</b> ${value}"
}

// A rounded colored badge. Colors chosen for readability on Hubitat's light UI.
private String pill(String text, String color) {
    def bg = [green:'#2e7d32', red:'#c62828', amber:'#ef6c00',
              blue:'#1565c0', indigo:'#4527a0', grey:'#616161'][color] ?: '#616161'
    "<span style='background:${bg};color:#fff;padding:2px 8px;border-radius:10px;font-size:0.85em;white-space:nowrap'>${text}</span>"
}

void lowerBlinds() {
    // Issue close twice, matching the original rule (reliability for the shades).
    blinds?.close()
    blinds?.close()
    if (logEnable) log.debug "lowering blind(s): ${blinds*.displayName}"
    // Verify they actually reach 'closed'. close() is fire-and-forget — a shade that
    // ignores it (offline / dead motor) would otherwise leave the night 'done' with the
    // blind still up (exactly what happened 2026-09-19). Read back, re-send, then alert.
    if (settings.blindVerifyEnable != false) armBlindConfirm(0, 0)
}

private Integer blindTravelSec() { return Math.max(5, (settings.blindTravelSec ?: 45) as int) }

private void armBlindConfirm(Integer tries, Integer grace) {
    runIn(blindTravelSec(), "confirmBlindsClosed", [data: [tries: tries, grace: grace], overwrite: true])
}

// Read each shade back after it's had time to travel; re-send close to any that aren't
// closed and alert loudly if one never confirms. A shade still 'closing' just gets more
// time (up to a couple of grace cycles) rather than a wasted retry. Falls back to the
// position value when the driver reports no windowShade state.
def confirmBlindsClosed(data) {
    Integer tries = (data?.tries ?: 0) as int
    Integer grace = (data?.grace ?: 0) as int
    def stuck = []; def moving = []; def unverifiable = []
    blinds?.each { b ->
        try { if (b.hasCommand("refresh")) b.refresh() } catch (e) { }
        String st = (b.currentValue("windowShade") ?: "").toString().toLowerCase()
        Integer pos = null; try { pos = (b.currentValue("position") as Integer) } catch (e) { }
        if (st == "closed") return
        else if (st == "closing" || st == "opening") moving << b
        else if (st == "" || st == "unknown") {
            if (pos != null) { if (pos > ((settings.blindClosedPos ?: 2) as int)) stuck << b }
            else unverifiable << b
        } else stuck << b   // open / partially open / anything not closed
    }
    // Still physically moving and nothing outright stuck → give it another travel window,
    // but cap the grace so a shade that reports 'closing' forever can't loop endlessly.
    if (moving && !stuck) {
        if (grace < 2) { armBlindConfirm(tries, grace + 1); return }
        stuck = moving   // grace exhausted — treat a perpetually-'closing' shade as failed
    }
    if (unverifiable && !stuck) {
        log.warn "${app.label}: can't confirm ${unverifiable*.displayName.join(', ')} closed (shade reports no state) — verification skipped"
        return
    }
    if (!stuck) { if (logEnable) log.debug "blind(s) confirmed closed"; return }

    List names = stuck*.displayName
    Integer maxTries = Math.max(1, (settings.blindRetries ?: 2) as int)
    if (tries < maxTries) {
        log.warn "${app.label}: blind(s) not closed (${names.join(', ')}) — re-sending close (retry ${tries + 1}/${maxTries})"
        stuck.each { try { it.close() } catch (e) { } }
        armBlindConfirm(tries + 1, 0)
        return
    }
    log.error "${app.label}: blind(s) did NOT close after ${maxTries + 1} attempt(s): ${names.join(', ')}"
    notify("⚠ ${app.label}: blind did NOT close — ${names.join(', ')} still up after ${maxTries + 1} tries. Check the shade.")
}

private void notify(String msg) {
    if (msg) notifiers?.deviceNotification(msg)
}

// Dark = NOT between sunrise and (sunset - offset). Handles the overnight wrap.
private boolean isDark() {
    // Sunset/before-sunset gate is optional: when off, the lux threshold alone decides,
    // so the blind can lower whenever it's dark enough — at any time of day.
    if (settings.useSunsetGate == false) return true
    // Negative offset moves sunset earlier, e.g. -15 => "15 minutes before sunset".
    def sun = getSunriseAndSunset(sunsetOffset: "-${(sunsetOffset ?: 0)}")
    def now = new Date()
    boolean daytime = now.after(sun.sunrise) && now.before(sun.sunset)
    return !daytime
}
