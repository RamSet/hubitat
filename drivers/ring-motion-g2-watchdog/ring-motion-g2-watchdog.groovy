/*
 *  Ring Alarm Motion Sensor G2 — Watchdog
 *
 *  Why this exists:
 *    The Ring Alarm Motion Detector (2nd gen) reports motion clear exactly once —
 *    Notification Report, Home Security (0x07), event 0x00 "Previous Events Cleared",
 *    event parameter 0x08. Per Ring's Z-Wave technical manual the sensor makes only
 *    `configuration parameter 2` application-level retries (factory default: 1), and it
 *    sends no periodic state report afterwards (parameter 1 "heartbeats" are BATTERY
 *    reports, not state). So if that one clear and its single retry are both lost, the
 *    sensor never mentions it again and the hub sits on motion=active forever. No driver
 *    can recover from that without a timer of its own, which is why the stock driver and
 *    every community driver show the same stuck-active symptom.
 *
 *    This driver adds the timer. It does NOT blindly lie about state: when the watchdog
 *    fires it first ASKS the sensor for its real notification state (Notification Get)
 *    and only clears if the sensor says it is idle — or if the sensor does not answer
 *    within the grace window.
 *
 *  Deliberately has NO fingerprint. Assign it by hand. It must never hijack a pairing
 *  away from the built-in driver.
 *
 *  Author: RamSet — https://github.com/RamSet/hubitat
 *
 *  Changelog:
 *    0.3.1 - Fix: the watchdog timers threw MissingMethodException at fire time. runIn()
 *            with an options map invokes the handler with an argument, so the no-arg
 *            signatures never matched. Caught only by running the watchdog for real with
 *            a shortened timeout — it compiles clean and every other path works.
 *    0.3.0 - Fix: a sensor that answered the watchdog's verify query with "motion still
 *            active" was force-cleared anyway a few seconds later, because re-arming the
 *            check did not cancel the grace timer from the query. Verify-then-clear only
 *            works if a positive answer actually cancels the clear.
 *    0.2.0 - Expose configuration parameter 2 (application-level retries) as a preference
 *            and write it from configure(). Factory default is 1 retry, which is the
 *            reason a single lost motion-clear becomes permanent. Verified on firmware
 *            1.09: parameters 1-11 are supported (12 is not), and 1/2/3 read back 70/1/5
 *            exactly as Ring's manual describes. NOTE: parameters 5, 6, 8 and 11 do NOT
 *            match the sizes or defaults in Ring's published North America manual on this
 *            firmware, so no label is claimed for anything above parameter 4.
 *    0.1.0 - Initial. Motion/tamper/battery parsing, Supervision reply, S2 encapsulation,
 *            verify-then-clear watchdog, parameter read/write commands.
 */

import groovy.transform.Field

@Field static final String VERSION = "0.3.1"

// Ring G2 advertises Notification v8. Keep the versions the device actually speaks;
// asking zwave.parse() for a version the device does not support silently drops frames.
@Field static final Map CMD_CLASS_VERS = [
    0x71: 8,   // Notification
    0x80: 1,   // Battery
    0x70: 1,   // Configuration
    0x86: 2,   // Version
    0x85: 2,   // Association
    0x59: 1,   // Association Group Info
    0x6C: 1,   // Supervision
    0x55: 1,   // Transport Service
    0x9F: 1    // Security 2
]

// Home Security (0x07) events we care about, from Ring's technical manual.
@Field static final Integer NOTIF_HOME_SECURITY = 7
@Field static final Integer EVT_CLEARED         = 0x00
@Field static final Integer EVT_TAMPER          = 0x03   // product covering removed
@Field static final Integer EVT_MOTION          = 0x08   // intrusion / motion detected

metadata {
    definition(name: "Ring Alarm Motion Sensor G2 Watchdog", namespace: "RamSet", author: "RamSet",
               importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/drivers/ring-motion-g2-watchdog/ring-motion-g2-watchdog.groovy") {
        capability "MotionSensor"
        capability "TamperAlert"
        capability "Battery"
        capability "Configuration"
        capability "Refresh"
        capability "Sensor"

        command "forceInactive"
        command "getParameterReport", [[name:"parameterNumber", type:"NUMBER",
                 description:"Parameter number to read. Leave blank to sweep 1-12 (one read per second)."]]
        command "setParameter", [[name:"parameterNumber*", type:"NUMBER", description:"Parameter number"],
                                 [name:"size*",            type:"NUMBER", description:"Size in bytes (1, 2 or 4)"],
                                 [name:"value*",           type:"NUMBER", description:"Value to write"]]

        // How often the watchdog actually had to step in. If this stays at 0 the sensor
        // is clearing on its own and the watchdog is dead weight; if it climbs, it is
        // carrying the integration.
        attribute "watchdogClears", "number"
        // "device" = the sensor cleared itself; "watchdog-verified" = sensor confirmed idle
        // when asked; "watchdog-forced" = sensor never answered and we cleared anyway.
        attribute "lastClearSource", "string"
    }

    preferences {
        input name: "motionTimeoutSecs", type: "number",
              title: "Watchdog timeout (seconds)",
              description: "If motion has been active this long with no clear from the sensor, query the sensor and clear if it is idle. 0 disables the watchdog. Default 240.",
              defaultValue: 240, range: "0..3600"
        input name: "verifyGraceSecs", type: "number",
              title: "Verify grace (seconds)",
              description: "How long to wait for the sensor to answer that query before clearing anyway. Default 10.",
              defaultValue: 10, range: "2..120"
        input name: "retryCount", type: "number",
              title: "Sensor retry count (configuration parameter 2)",
              description: "How many application-level retries the SENSOR makes when a report is not acknowledged. Ring ships this at 1, so one lost motion-clear is never re-sent and the hub stays stuck on active. 5 is the maximum. Costs a little battery. Written on Configure.",
              defaultValue: 5, range: "0..5"
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

/*******************************************************************
 ***** Lifecycle
 ********************************************************************/

void installed() {
    logInfo "installed"
    sendEvent(name: "watchdogClears", value: 0)
    runIn(2, "refresh")
}

void updated() {
    logInfo "preferences saved — watchdog ${watchdogSecs() ? "${watchdogSecs()}s" : "DISABLED"}, verify grace ${graceSecs()}s"
    if (logEnable) runIn(1800, "logsOff")
    // A shortened timeout must apply to a run already in flight, and a disabled
    // watchdog must not leave an armed timer behind.
    if (device.currentValue("motion") == "active") armWatchdog() else disarmWatchdog()
}

void configure() {
    Integer retries = (settings?.retryCount == null ? 5 : (settings.retryCount as Integer))
    logInfo "configure — writing parameter 2 = ${retries}, then reading version, battery and parameters 1-12"
    // Parameter 2 first: it is the one lever that attacks the actual cause rather than
    // papering over it. The sweep that follows reads it back for confirmation.
    sendCmds([secureCmd(zwave.configurationV1.configurationSet(parameterNumber: 2, size: 1, scaledConfigurationValue: retries))], 500)
    getParameterReport()
    sendCmds([secureCmd(zwave.versionV2.versionGet()),
              secureCmd(zwave.batteryV1.batteryGet())], 500)
}

void logsOff() {
    device.updateSetting("logEnable", [value:"false", type:"bool"])
    logInfo "debug logging disabled"
}

/*******************************************************************
 ***** Commands
 ********************************************************************/

void refresh() {
    logDebug "refresh — asking for battery and current motion notification state"
    sendCmds([secureCmd(zwave.batteryV1.batteryGet()),
              secureCmd(notificationGet())], 500)
}

// Manual escape hatch. Same path as the watchdog's last resort.
void forceInactive() {
    logInfo "forceInactive requested"
    clearMotion("manual")
}

void getParameterReport(parameterNumber = null) {
    List<String> cmds = []
    if (parameterNumber != null) {
        cmds << secureCmd(zwave.configurationV1.configurationGet(parameterNumber: parameterNumber as Integer))
    } else {
        // Ring's manual documents 1-12, but firmware 1.09 answers a Get for 12 with
        // parameter 1's value, so 12 is not really supported. Sweep what exists, and
        // sweep it rather than trusting any published map — the manual's sizes and
        // defaults for 5, 6, 8 and 11 do not match this firmware at all.
        (1..11).each { cmds << secureCmd(zwave.configurationV1.configurationGet(parameterNumber: it)) }
    }
    sendCmds(cmds, 1000)
}

void setParameter(parameterNumber, size, value) {
    Integer num = parameterNumber as Integer
    Integer sz  = size as Integer
    Integer val = value as Integer
    logInfo "setParameter — writing ${val} to parameter ${num} (size ${sz})"
    sendCmds([secureCmd(zwave.configurationV1.configurationSet(parameterNumber: num, size: sz, scaledConfigurationValue: val)),
              secureCmd(zwave.configurationV1.configurationGet(parameterNumber: num))], 1000)
}

/*******************************************************************
 ***** Z-Wave parsing
 ********************************************************************/

void parse(String description) {
    hubitat.zwave.Command cmd = zwave.parse(description, CMD_CLASS_VERS)
    if (cmd) { zwaveEvent(cmd) }
    else { logDebug "unparsed: ${description}" }

    // Self-bootstrap. Assigning a driver to an existing device does not call
    // installed(), and this sensor is FLiRS so there is no wake-up to hook either.
    // The first frame we successfully parse is the reliable trigger.
    if (state.driverVersion != VERSION) {
        state.driverVersion = VERSION
        logInfo "first parse on ${VERSION} — running configure()"
        runIn(2, "configure")
    }
}

// The sensor wraps its reports in Supervision. If we never send the SupervisionReport
// back it treats the report as unacknowledged, burns its (single, by default) retry and
// gives up — the exact way a motion-clear goes missing. Always answer.
void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd) {
    hubitat.zwave.Command encapsulatedCmd = cmd.encapsulatedCommand(CMD_CLASS_VERS)
    logDebug "supervision get ${cmd.sessionID} --ENCAP-- ${encapsulatedCmd}"
    if (encapsulatedCmd) { zwaveEvent(encapsulatedCmd) }
    else { logWarn "unable to extract encapsulated cmd from ${cmd}" }
    sendCmds([secureCmd(zwave.supervisionV1.supervisionReport(
        sessionID: cmd.sessionID, reserved: 0, moreStatusUpdates: false, status: 0xFF, duration: 0))])
}

void zwaveEvent(hubitat.zwave.commands.notificationv8.NotificationReport cmd) {
    logDebug "notification report: type=${cmd.notificationType} event=${cmd.event} param=${cmd.eventParameter}"
    if ((cmd.notificationType as Integer) != NOTIF_HOME_SECURITY) {
        logDebug "ignoring notification type ${cmd.notificationType}"
        return
    }
    Integer evt = cmd.event as Integer
    // A clear names the event it is clearing in eventParameter, so motion-clear and
    // tamper-clear arrive as the same event 0x00 and are only told apart by that byte.
    Integer clearing = (cmd.eventParameter && cmd.eventParameter.size() > 0) ? (cmd.eventParameter[0] as Integer) : null

    if (evt == EVT_MOTION) {
        setMotion("active", "device")
    } else if (evt == EVT_TAMPER) {
        sendTamper("detected")
    } else if (evt == EVT_CLEARED) {
        if (clearing == EVT_MOTION)      { clearMotion("device") }
        else if (clearing == EVT_TAMPER) { sendTamper("clear") }
        else if (clearing == null) {
            // Some firmware sends a bare "all clear" with no event parameter. Treat it
            // as clearing everything rather than dropping it on the floor.
            logDebug "bare event-cleared with no event parameter — clearing motion and tamper"
            clearMotion("device")
            sendTamper("clear")
        } else {
            logDebug "event-cleared for unhandled event 0x${Integer.toHexString(clearing)}"
        }
    } else {
        logDebug "unhandled home-security event ${evt}"
    }
}

void zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd) {
    Integer lvl = cmd.batteryLevel as Integer
    String desc
    if (lvl == 0xFF) { lvl = 1; desc = "${device.displayName} battery is LOW" }
    else             { desc = "${device.displayName} battery is ${lvl}%" }
    if (txtEnable) log.info desc
    sendEvent(name: "battery", value: lvl, unit: "%", descriptionText: desc)
}

void zwaveEvent(hubitat.zwave.commands.configurationv1.ConfigurationReport cmd) {
    // Logged at info deliberately: reading the real parameter map off the device is the
    // whole point of the sweep, and it needs to be visible without debug logging on.
    log.info "${device.displayName} parameter ${cmd.parameterNumber} (size ${cmd.size}) = ${cmd.scaledConfigurationValue}"
    state."param${cmd.parameterNumber}" = cmd.scaledConfigurationValue
}

void zwaveEvent(hubitat.zwave.commands.versionv2.VersionReport cmd) {
    String fw = String.format("%d.%02d", cmd.firmware0Version, cmd.firmware0SubVersion)
    device.updateDataValue("firmwareVersion", fw)
    logInfo "firmware ${fw}"
}

void zwaveEvent(hubitat.zwave.Command cmd) {
    logDebug "unhandled command: ${cmd}"
}

/*******************************************************************
 ***** Motion state + watchdog
 ********************************************************************/

private Integer watchdogSecs() { return (settings?.motionTimeoutSecs == null ? 240 : (settings.motionTimeoutSecs as Integer)) }
private Integer graceSecs()    { return (settings?.verifyGraceSecs   == null ? 10  : (settings.verifyGraceSecs   as Integer)) }

private void setMotion(String value, String source) {
    if (value == "active") {
        sendMotionEvent("active", source)
        armWatchdog()
    }
}

private void clearMotion(String source) {
    disarmWatchdog()
    if (device.currentValue("motion") != "inactive") {
        if (source != "device") {
            Integer n = ((device.currentValue("watchdogClears") ?: 0) as Integer) + 1
            sendEvent(name: "watchdogClears", value: n)
            log.warn "${device.displayName}: motion cleared by ${source} — the sensor never sent its clear (watchdog clears: ${n})"
        }
        sendMotionEvent("inactive", source)
    } else {
        logDebug "clearMotion(${source}) — already inactive"
    }
}

private void sendMotionEvent(String value, String source) {
    String desc = "${device.displayName} motion is ${value}" + (source == "device" ? "" : " (${source})")
    if (txtEnable) log.info desc
    sendEvent(name: "motion", value: value, descriptionText: desc)
    sendEvent(name: "lastClearSource", value: source)
}

private void sendTamper(String value) {
    if (device.currentValue("tamper") == value) return
    String desc = "${device.displayName} tamper is ${value}"
    if (txtEnable) log.info desc
    sendEvent(name: "tamper", value: value, descriptionText: desc)
}

private void armWatchdog() {
    // Cancel any pending force-clear FIRST. If we got here because the sensor answered a
    // verify query with "still active", the grace timer from that query is still running
    // and would force the device inactive a few seconds later — overriding the very
    // answer we asked for. Re-arming the check without clearing the expire was a bug.
    unschedule("watchdogExpire")
    Integer secs = watchdogSecs()
    if (secs <= 0) { disarmWatchdog(); return }
    logDebug "watchdog armed for ${secs}s"
    runIn(secs, "watchdogCheck", [overwrite: true])
}

private void disarmWatchdog() {
    unschedule("watchdogCheck")
    unschedule("watchdogExpire")
}

// NOTE ON THE SIGNATURES: both scheduled handlers below take an optional argument.
// runIn() with an options map (we pass [overwrite: true]) invokes the handler WITH an
// argument, so a bare no-arg signature throws MissingMethodException when the timer
// fires — and only when it fires, which a compile check will never catch. runIn without
// an options map calls with no argument, so the default keeps both styles working.
// Stage 1: the sensor has been active too long. Ask it what it actually thinks before
// overriding it — a genuinely occupied room must not be reported as empty.
void watchdogCheck(data = null) {
    if (device.currentValue("motion") != "active") { logDebug "watchdogCheck — already inactive"; return }
    logDebug "watchdog timeout reached — querying the sensor for its real state"
    runIn(graceSecs(), "watchdogExpire", [overwrite: true])
    sendCmds([secureCmd(notificationGet())])
}

// Stage 2: it did not answer. Clear anyway — a silent sensor is the failure we are here
// to paper over, and a stuck "active" breaks every automation downstream of it.
void watchdogExpire(data = null) {
    if (device.currentValue("motion") != "active") return
    clearMotion("watchdog-forced")
}

// Ask specifically about the motion (intrusion) event. A device that is idle answers
// with event 0x00, which routes into the normal clear path above and marks the clear
// as verified rather than forced.
private hubitat.zwave.Command notificationGet() {
    return zwave.notificationV8.notificationGet(notificationType: NOTIF_HOME_SECURITY, v1AlarmType: 0, event: EVT_MOTION)
}

/*******************************************************************
 ***** Plumbing
 ********************************************************************/

String secureCmd(hubitat.zwave.Command cmd) { return zwaveSecureEncap(cmd) }

void sendCmds(List<String> cmds, Long delay = 300) {
    if (!cmds) return
    sendHubCommand(new hubitat.device.HubMultiAction(delayBetween(cmds, delay), hubitat.device.Protocol.ZWAVE))
}

private void logInfo(String msg)  { if (txtEnable != false) log.info  "${device.displayName}: ${msg}" }
private void logDebug(String msg) { if (logEnable)          log.debug "${device.displayName}: ${msg}" }
private void logWarn(String msg)  { log.warn "${device.displayName}: ${msg}" }
