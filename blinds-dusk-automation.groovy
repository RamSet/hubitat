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
    importUrl:   "https://raw.githubusercontent.com/RamSet/hubitat/main/blinds-dusk-automation.groovy"
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "<b>Blinds Dusk Automation</b>", install: true, uninstall: true) {
        section("<b>Name</b>") {
            label title: "Name this instance (one per room)", required: true,
                  defaultValue: "Room Blinds Dusk"
        }
        section("<b>Trigger</b>") {
            input "lightSensors", "capability.illuminanceMeasurement",
                  title: "Illuminance sensor(s) that trigger evaluation", multiple: true, required: true
            input "luxThreshold", "number",
                  title: "Act when illuminance is at or below (lux)", defaultValue: 200, required: true
            input "sunsetOffset", "number",
                  title: "Start this many minutes BEFORE sunset", defaultValue: 15, required: true
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

void lowerBlinds() {
    // Issue close twice, matching the original rule (reliability for the shades).
    blinds?.close()
    blinds?.close()
    if (logEnable) log.debug "lowering blind(s): ${blinds*.displayName}"
}

private void notify(String msg) {
    if (msg) notifiers?.deviceNotification(msg)
}

// Dark = NOT between sunrise and (sunset - offset). Handles the overnight wrap.
private boolean isDark() {
    // Negative offset moves sunset earlier, e.g. -15 => "15 minutes before sunset".
    def sun = getSunriseAndSunset(sunsetOffset: "-${(sunsetOffset ?: 0)}")
    def now = new Date()
    boolean daytime = now.after(sun.sunrise) && now.before(sun.sunset)
    return !daytime
}
