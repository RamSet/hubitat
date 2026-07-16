/*
 * Ecobee HAP Thermostat (Local)
 *
 * Description:
 *   Controls an ecobee thermostat 100% locally over the HomeKit Accessory
 *   Protocol (HAP) — no cloud account, no Apple hardware, no extra bridge or
 *   hub. The driver pairs directly with the thermostat using its 8-digit
 *   HomeKit setup code, then holds a persistent encrypted LAN session for
 *   mode, setpoints, temperature, humidity, operating state, fan, and resume.
 *   Remote room sensors are created automatically as child devices, and HAP
 *   event push keeps everything updated in real time. Pairing uses one of the
 *   thermostat's HomeKit slots; resetting HomeKit on the device frees a slot.
 *
 *   As of 0.15.0 the HAP protocol engine (pairing, encrypted session, mDNS,
 *   reconnect) lives in the shared "hapCore" library so it is maintained in one
 *   place across all of RamSet's HomeKit drivers — install the library alongside
 *   this driver (HPM does it automatically).
 *
 * Author: RamSet
 * Version: 0.19.3
 * Date: 2026-07-16
 *
 * REQUIRES library: RamSet.hapCore (installed automatically by Hubitat Package Manager).
 *
 * Changelog:
 *  v0.19.3 - Timed fan run no longer clobbers a PRE-EXISTING hold. 0.19.2's resume-after-run was unconditional,
 *           so if a hold was already active before the timed run started, ending the run wiped it. Now it records
 *           whether a hold was active at the start and only resumes the schedule if the run itself created the
 *           hold; a run started from an existing hold just returns the fan to Auto and leaves the hold intact.
 *           (A comfort override like Away reads onHold=false, so a run started from one still resumes to schedule
 *           — distinguishing scheduled vs overridden climate over HAP is ambiguous, so that edge is left as-is.)
 *  v0.19.2 - Timed fan run now returns to the schedule. Turning the ecobee's fan On (HAP) creates a HOLD, and
 *           returning it to Auto does NOT clear that hold — so setFanRunTime(N) used to leave the thermostat
 *           stuck on hold at whatever temps were active, indefinitely if the ecobee's Hold Action is "until I
 *           change it". fanRunTimeEnd() now calls resumeProgram() after returning the fan to Auto, so a timed
 *           blower run cleans up its own hold (verified on hardware). Plain fanOn/fanAuto still don't auto-resume
 *           by design — a manual fan-on is a hold; add resumeProgram() in your rule, or set the thermostat's
 *           Hold Action to "until next scheduled activity".
 *  v0.19.1 - Sensor children now also get timeSinceMotion / timeSinceOccupancy — a human-readable form of the
 *           secondsSince* activity timers (e.g. "3h 50m", "13d 4h", "1mo 2d"), for reading a sensor's activity
 *           age at a glance. The numeric secondsSince* attributes stay for rules; this is the companion display.
 *           Requires the Ecobee HAP Remote Sensor child driver 0.13.0+.
 *  v0.19.0 - New preference "Live-session safety-reconnect window (seconds)": how long the live HomeKit session
 *           may go with no pushed event before the driver reconnects to re-sync (re-subscribe + fresh read). An
 *           idle ecobee (HVAC off, temperature steady) pushes nothing for minutes, which tripped the old fixed
 *           120-second window and forced a reconnect every 2-3 minutes — flooding the log with "HAP: no update in
 *           Ns — reconnecting to reconcile". Default is now 360s, ABOVE the 5-minute background refresh, so the
 *           periodic refresh keeps the session warm and the reconnect only fires on a genuinely stalled session.
 *           Keep it above your background refresh interval. Requires one "Save Preferences" after updating
 *           (Hubitat doesn't re-run setup on a package update). Pairs with hapCore 0.10.3, which drops that
 *           message from warn to info so it no longer reads as a fault (or triggers phone notifications).
 *  v0.18.2 - Clears the now-removed battery/lowBattery readings off existing sensor children on the next
 *           discovery (Save Preferences), so the stale value from older versions doesn't linger.
 *  v0.18.1 - Removed battery reporting from the sensor children. The ecobee reports every SmartSensor's battery
 *           as 100% (and "not low") over HomeKit right up until it dies — confirmed across multiple ecobees — so
 *           it was misleading, not useful. Use the thermostat's alert, which reliably flags a low/lost sensor.
 *           (Commented out, not deleted — easy to restore if a future firmware reports battery accurately.)
 *  v0.18.0 - Support ecobee door/window SmartSensors (model EBDWC01): they expose contact + motion +
 *           occupancy + battery, and now get a child device reporting open/closed. Previously only sensors
 *           with a temperature reading got a child, so these were discovered but never created. (Requires the
 *           Ecobee HAP Remote Sensor child driver 0.11.0+, which adds the ContactSensor capability.)
 *  v0.17.4 - customParams (the raw undecoded HAP characteristics — a diagnostic dump) is now also hidden
 *           unless debug logging is on, alongside diag. Everything useful in it is already surfaced as
 *           proper attributes; this just de-clutters the device page. Save Preferences with debug off to
 *           drop the row.
 *  v0.17.3 - The diag state is now hidden when debug logging is off: it's removed from the device's Current
 *           States entirely (not just blanked), so it no longer clutters the device page. Turn on debug
 *           logging to see it again; it clears itself when debug turns off (or after the 30-min auto-off).
 *  v0.17.2 - Setpoint rounding is now conditional on the ecobee's own units. A °F-native ecobee uses whole-°F
 *           setpoints whose Celsius value picks up ~0.1° of round-trip noise, so those are rounded to the whole
 *           °F. A °C-native ecobee steps in 0.5°C, and 0.5°C = 0.9°F exactly — those tenths are real, so they're
 *           now shown as-is instead of being rounded away (thanks to a forum note pointing that out). Detected
 *           via HAP TemperatureDisplayUnits (iid21).
 *  v0.17.1 - Setpoints now show whole °F (for °F users) instead of a stray tenth. The ecobee is whole-°F-
 *           native, but HomeKit only exposes the value in Celsius, so the °F->°C->°F round-trip could add
 *           ~0.1° (e.g. 80°F read back as 80.1). Setpoints are rounded to recover the intended whole number;
 *           the live temperature keeps its decimal (it's a real reading). °C users keep 0.5° resolution.
 *  v0.17.0 - thermostatSetpoint is now dynamic and always meaningful: it reflects the desired temperature for
 *           the current mode — the heat target in heat, the cool target in cool, and in auto the threshold
 *           actually being regulated (cooling->cool setpoint, heating->heat setpoint, idle->the threshold
 *           nearest the current temp). Previously in auto it showed HomeKit's single value, which is just the
 *           midpoint of the two thresholds (a number that appears nowhere on the thermostat). Added a
 *           setpointDetail attribute that labels what thermostatSetpoint currently represents.
 *  v0.16.4 - Fix holdEndsAt not clearing after a resume: it now reports "none" when there's no hold instead
 *           of an empty string (Hubitat drops empty-string events, which left the old hold-end date showing).
 *  v0.16.3 - Fix a compile error in 0.16.2: the helper I added (pollSecs) collided with a method of the same
 *           name in the shared library, so the driver wouldn't save. Renamed to refreshSecs. (0.16.2 also
 *           replaced the cron-based background refresh with a self-rescheduling timer — see below.)
 *  v0.16.2 - Reliability: the background refresh now uses a self-rescheduling timer instead of a cron, which
 *           is more dependable (and fixes the 30-second/2-minute options). NOTE: after updating via HPM you
 *           must open the device and click "Save Preferences" once — Hubitat doesn't re-run a driver's setup
 *           on a package update, so the background poll only arms after a Save.
 *  v0.16.1 - The background refresh interval is now configurable (preference): 30 seconds up to 30 minutes,
 *           default 5 minutes. This controls how quickly the values HomeKit can't push — comfort profile,
 *           on-hold, hold-end, per-profile setpoints, alert, sensor activity timers — catch up. Faster is
 *           fresher but adds local traffic; 5 minutes is recommended, 30 seconds is the floor.
 *  v0.16.0 - More detail surfaced from HAP: the thermostat now reports manufacturer, model, firmware and
 *           serial as attributes, and each sensor child gains secondsSinceMotion / secondsSinceOccupancy
 *           (an ecobee per-sensor activity timer; semantics inferred, polled on ~5-min cadence). Note the
 *           "Thermostat Sensor" child's temperature is the thermostat's own controlling temperature — HAP
 *           doesn't expose a separate individual built-in-sensor reading.
 *  v0.15.6 - Housekeeping only: the driver's displayed version now matches the package version after an
 *           update (previous packaging/engine fixes didn't touch the driver file, so its header lagged). No
 *           functional change from 0.15.5.
 *  v0.15.5 - Engine update (hapCore 0.9.1): the shared library now generates randomness without the
 *           java.security.KeyPairGenerator class, which some hub firmware blocks in the sandbox (that block
 *           stopped the driver from saving on those hubs). Now uses UUID-based entropy, so it installs on
 *           every hub. No driver-code or behavior change; existing pairings are unaffected.
 *  v0.15.4 - Packaging: rebuilt the shared hapCore library bundle with a distinct namespace so Hubitat
 *           Package Manager installs it reliably (the earlier bundle failed to install). No code change.
 *  v0.15.3 - Comfort profile reporting corrected. HomeKit numbers only the three built-in comfort settings
 *           (Home/Sleep/Away); anything else (a hold, vacation, or a custom comfort setting like "Night")
 *           all report the same value, so the driver now shows those as "Hold" when a hold is actually
 *           active and "Custom" when you're on a non-standard scheduled comfort setting, and onHold now
 *           reflects a genuine hold instead of assuming every non-standard state is a hold. HomeKit doesn't
 *           expose custom comfort-setting names, so those show as "Custom". Also picks up the shared engine's
 *           faster recovery when the thermostat reboots/re-keys the session (via hapCore 0.9.0).
 *  v0.15.2 - Fix stale readings for comfort profile, hold-end, per-profile setpoints and alert text. Those
 *           characteristics are read-only with NO HomeKit event push, so they can't be subscribed to — they
 *           must be polled. The shared engine is "pure listen" (no polling), so after 0.15.0 they only updated
 *           on a manual Refresh. Restored a 5-minute background refresh so they self-heal again, like pre-0.15.
 *
 *  v0.15.1 - Packaging: the shared hapCore engine library now ships as a Hubitat bundle so HPM installs it
 *           automatically (HPM has no 'libraries' manifest support). Fixes "library not found" on install/
 *           update; no manual library import needed. No functional change to the driver.
 *
 *  v0.15.0 - The HomeKit/HAP engine (pairing, encrypted session, port discovery, reconnect) moved into a
 *           shared library (hapCore) used by all of RamSet's HomeKit drivers, so the protocol is fixed and
 *           improved in one place. No setup change and no re-pairing — existing pairings are preserved. You
 *           gain the library's sturdier reconnect logic. If installing manually, add the hapCore library too
 *           (HPM installs it for you).
 *
 *  v0.14.2 - Debug logging now auto-disables after 30 minutes (it's off by default already). The diag trace
 *           writes state on every socket frame/event, so leaving it on inflated both the device's busy% and
 *           state size — auto-off keeps both down. logsOff() also clears the diag buffer.
 *
 *  v0.14.1 - State hygiene: shed stale pair-setup temporaries (srpA/srpK/srpM1/psSeed/psEncKey/psPid/psstage,
 *           ~1.2KB) on Save, not just after a fresh pair — already-paired hubs that never re-pair now reclaim
 *           that dead state on the next re-import. (No functional change; pairing recreates them as needed.)
 *
 *  v0.14.0 - More "macgyvered" capabilities (synthesized from HAP + driver timers/derivation):
 *           Hold Until (set a comfort profile or temp for N minutes, then auto-resume), Boost (nudge the
 *           setpoint +/- for N minutes, then resume), and two derived booleans for easy rule-gating:
 *           onHold (true when on any override) and alertActive (true when the ecobee has a pending alert).
 *
 *  v0.13.0 - Added Set Fan Run Time (minutes): the ecobee's per-hour fan minimum isn't exposed over HomeKit,
 *           so this emulates it — runs the blower for N minutes then returns to Auto (driver-timed). Pair it
 *           with a rule/webCoRE to set per-hour run time from temps. (Ceiling-fans-when-blower-runs is already
 *           covered by the fanState attribute reading "blowing".)
 *
 *  v0.12.0 - Thermostat's own motion + occupancy are now exposed as their OWN child sensor device
 *           ("<thermostat> Sensor"), instead of capabilities on the thermostat. This keeps the parent a
 *           pure Thermostat (so it still exports to Apple HomeKit) while motion AND presence export
 *           separately via the child — works whether or not you have remote sensors.
 *
 *  v0.11.0 - Reliability: keepalive watchdog reconnects a stalled/zombie live session; connect retries on
 *           "connection refused"; mDNS port discovery retries before falling back to the last-known port.
 *           Adds comfortProfile + holdEndsAt attributes and a debug 'diag' flow trace.
 *
 *  v0.10.0 - Comfort profiles over local HAP (Home/Away/Sleep), humidifier target, generic Set Characteristic.
 *
 *  v0.9.0 - HomeKit event push is the default: persistent encrypted session, instant updates, self-recovery.
 *           In-driver pairing and automatic port discovery. Remote room sensors exposed as child devices.
 *
 *  v0.3.0 - Initial release: fully-local control of the ecobee — pair-verify, ChaCha20-Poly1305 encrypted
 *           session, and thermostat read/write, all on the hub with no cloud and no additional hardware.
 *
 * HPM Metadata:
 * {
 *   "package": "Ecobee HAP Thermostat (Local)",
 *   "namespace": "RamSet",
 *   "author": "RamSet",
 *   "location": "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/drivers/ecobee-hap-thermostat/ecobee-hap-thermostat.groovy",
 *   "description": "Local HAP controller for an ecobee thermostat: mode, setpoints, temperature, humidity, operating state, fan, and remote sensors.",
 *   "required": true,
 *   "version": "0.15.0"
 * }
 *
 * Copyright 2026 RamSet
 * Licensed under the Apache License, Version 2.0. Provided as-is, without warranty
 * of any kind; you assume all risk of controlling real HVAC hardware with it.
 */

metadata {
    definition(name: "Ecobee HAP Thermostat", namespace: "RamSet", author: "RamSet", importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/drivers/ecobee-hap-thermostat/ecobee-hap-thermostat.groovy") {
        capability "Thermostat"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        // NOTE: the thermostat's own motion/occupancy are intentionally NOT capabilities on this device.
        // A Thermostat that also has MotionSensor/PresenceSensor can't be classified by Hubitat's HomeKit
        // Integration and silently drops out of the HomeKit export. Instead, the thermostat's built-in
        // sensor is exposed as its own child device (a motion/occupancy sensor) — see onAccessories().
        capability "Refresh"
        command "setDesiredTemperature", [[name:"Desired temperature*",type:"NUMBER",description:"Target temperature to set on the thermostat"]]
        command "raiseSetpoint"
        command "lowerSetpoint"
        command "resumeProgram"
        command "setComfortProfile", [[name:"profile*",type:"ENUM",constraints:["Home","Away","Sleep"]]]
        command "setFanRunTime", [[name:"minutes*",type:"NUMBER",description:"run the blower this many minutes, then back to Auto (emulates fan min-runtime; drive per-hour from a rule)"]]
        command "holdUntil", [[name:"target*",type:"STRING",description:"comfort profile (Home/Away/Sleep) or a temperature like 72"],[name:"minutes*",type:"NUMBER",description:"auto-resume the schedule after this many minutes"]]
        command "boost", [[name:"degrees*",type:"NUMBER",description:"raise (+) or lower (-) the setpoint by this much"],[name:"minutes*",type:"NUMBER",description:"then resume the schedule"]]
        command "setHumiditySetpoint", [[name:"humidity %*",type:"NUMBER",description:"target humidity, 20-50"]]
        command "setCharacteristic", [[name:"aid.iid*",type:"STRING",description:"HAP characteristic, e.g. 1.40"],[name:"value*",type:"STRING",description:"value to write (number or string)"]]
        command "dumpAccessories"   // debug: logs this thermostat's full HAP accessory/service/characteristic map
        attribute "comfortProfile", "string"
        attribute "onHold", "string"          // true when on an override/hold (derived from comfortProfile) — easy rule-gating
        attribute "alertActive", "string"     // true when the ecobee has a pending alert/reminder (derived from thermostatAlert)
        attribute "holdEndsAt", "string"
        attribute "humiditySetpoint", "number"
        attribute "fanState", "string"          // actual fan running state: inactive / idle / blowing (HAP iid76)
        attribute "thermostatAlert", "string"   // ecobee alerts/reminders text (HAP iid54)
        attribute "setpointDetail", "string"     // what thermostatSetpoint currently reflects (heating/cooling target + mode)
        attribute "homeHeatSetpoint", "number"  // per-comfort-profile targets (HAP iid34-39, Home/Away/Sleep)
        attribute "homeCoolSetpoint", "number"
        attribute "awayHeatSetpoint", "number"
        attribute "awayCoolSetpoint", "number"
        attribute "sleepHeatSetpoint", "number"
        attribute "sleepCoolSetpoint", "number"
        attribute "customParams", "string"
        attribute "hapStatus", "string"
        attribute "diag", "string"
        attribute "manufacturer", "string"   // from the thermostat's HAP AccessoryInformation service
        attribute "model", "string"          // e.g. ecobee4 / EB-STATE5
        attribute "firmware", "string"
        attribute "serial", "string"
    }
    preferences {
        input "ip", "string", title: "Thermostat IP address", required: true
        if (!(state.paired==true || settings?.iosLtsk)) {   // settings is null at code-save time -> MUST use safe-nav (settings?.) or it NPEs and the save fails
            input "setupCode", "string", title: "HomeKit setup code — 8 digits, no dashes (e.g. 12345678). Enter and Save to pair.", required: false
        }
        input "infoLog", "bool", title: "Enable info logging", defaultValue: true
        input "debugLog", "bool", title: "Enable debug logging", defaultValue: false
        input "refreshInterval", "enum", title: "Background refresh interval",
            description: "How often to re-read the values HomeKit can't push (comfort profile, on-hold, hold-end, per-profile setpoints, alert, sensor activity timers). Faster = fresher but more local traffic; 5 minutes is recommended. If the session ever gets flaky, back it off.",
            options: ["30 seconds","1 minute","2 minutes","5 minutes","10 minutes","15 minutes","30 minutes"], defaultValue: "5 minutes"
        input "safetyRefreshSecs", "number", title: "Live-session safety-reconnect window (seconds)",
            description: "If the thermostat pushes no HomeKit event for this long, the live session is reconnected to re-sync. An idle thermostat (HVAC off, temperature steady) sends no events, so keep this ABOVE the background refresh interval — otherwise a healthy but quiet thermostat reconnects every couple of minutes and logs 'HAP: no update in Ns'. Recommended: refresh interval + 60s (default 360). 0 = off (falls back to a 30-minute watchdog).",
            defaultValue: 360, range: "0..3600"
    }
}

#include RamSet.hapCore
import groovy.transform.Field

// ===== thermostat topology (device-specific; the HAP engine lives in the hapCore library) =====
@Field static int TAID = 1
// thermostat readable characteristic iids -> label
@Field static Map TCHARS = [
    17:"opStateRaw", 18:"modeRaw", 19:"temperatureC", 20:"setpointC", 21:"unitsRaw",
    22:"coolingSetpointC", 23:"heatingSetpointC", 24:"humidity", 25:"targetHumidity",
    66:"thermMotion", 65:"thermOccupancy",
    33:"c_iid33", 34:"c_iid34", 35:"c_iid35", 36:"c_iid36", 37:"c_iid37", 38:"c_iid38",
    39:"c_iid39", 41:"c_iid41", 49:"c_iid49", 50:"c_iid50", 51:"c_iid51", 52:"c_iid52",
    53:"c_iid53", 54:"c_iid54", 75:"c_iid75", 76:"c_iid76"
]
// remote/own sensors are discovered dynamically from /accessories into state.sensors (see onAccessories)

// ===== lifecycle =====
def installed(){ updated() }
def updated(){
    unschedule(); try{ interfaces.rawSocket.close() }catch(e){}   // drop any prior socket cleanly so the thermostat frees its slot before we reconnect
    state.live=false; state.diag=[]; state.connTry=0; state.mdnsTries=0; state.connInFlight=null; state.vtry=0; state.wretry=0
    if(settings.debugLog) sendEvent(name:"diag", value:"") else { device.deleteCurrentState("diag"); device.deleteCurrentState("customParams") }   // hide diagnostic-only states when debug logging is off
    state.remove("sensors"); state.remove("services")   // force a fresh /accessories discovery on Save so sensor topology (incl. the thermostat's own sensor) rebuilds
    ["srpK","srpA","srpM1","psSeed","psEncKey","psPid","psstage"].each{ state.remove(it) }   // shed stale pair-setup temporaries (re-created if pairing; ~1.2KB reclaimed on already-paired hubs)
    if(settings.debugLog) runIn(1800,"logsOff")   // debug is off by default and auto-disables after 30 min (it writes state on every frame — keeps the device's busy% + state size down)
    if(settings.setupCode && !isPaired()){ logInfo "HAP: setup code entered — pairing"; runIn(1,"pair") }
    else if(isPaired()){ runIn(2,"startSession"); runEvery10Minutes("ensureUp"); scheduleRefresh() }   // live event mode is the default once paired; ensureUp is a reconnect backstop; scheduleRefresh polls the no-event chars (comfort profile/hold-end/per-profile setpoints/alert/sensor timers) the pure-listen engine won't push
}
// schedule the background re-read of the no-event characteristics; interval is user-configurable (default 5 min, floor 30 s)
def scheduleRefresh(){
    unschedule("refresh"); unschedule("pollRefresh")
    runIn(refreshSecs(), "pollRefresh")
    logInfo "HAP: background refresh every ${settings?.refreshInterval ?: '5 minutes'} (${refreshSecs()}s)"
}
Integer refreshSecs(){
    switch(settings?.refreshInterval ?: "5 minutes"){
        case "30 seconds": return 30
        case "1 minute":   return 60
        case "2 minutes":  return 120
        case "10 minutes": return 600
        case "15 minutes": return 900
        case "30 minutes": return 1800
        default:           return 300   // "5 minutes"
    }
}
// self-rescheduling poll — runIn is more reliable than a custom cron and handles 30s/2m intervals runEveryX can't.
// Re-arm FIRST so a refresh hiccup can't break the chain.
def pollRefresh(){ runIn(refreshSecs(), "pollRefresh"); refresh() }
def logsOff(){ device.updateSetting("debugLog",[value:"false",type:"bool"]); state.diag=[]; device.deleteCurrentState("diag"); device.deleteCurrentState("customParams"); log.info "HAP: debug logging auto-disabled" }

// ===== thermostat commands (write over the library's HAP session via writeChar/writeChars) =====
def setThermostatMode(String m){ String lm=m?.toLowerCase(); def v=[off:0,heat:1,cool:2,auto:3][lm]; if(v!=null){ writeChar(TAID,18,v); sendEvent(name:"thermostatMode", value:lm) } else log.warn "bad mode $m" }
def off(){ setThermostatMode("off") }
def heat(){ setThermostatMode("heat") }
def cool(){ setThermostatMode("cool") }
def auto(){ setThermostatMode("auto") }
def emergencyHeat(){ setThermostatMode("heat") }
// HAP: in heat/cool the active setpoint is TargetTemperature (iid20); thresholds (22/23) apply only in auto
def setHeatingSetpoint(t){ String m=device.currentValue("thermostatMode"); writeChar(TAID, (m=="auto")?23:20, round1(hubToC(t as BigDecimal))); sendEvent(name:"heatingSetpoint", value:t); if(m!="auto") sendEvent(name:"thermostatSetpoint", value:t) }
def setCoolingSetpoint(t){ String m=device.currentValue("thermostatMode"); writeChar(TAID, (m=="auto")?22:20, round1(hubToC(t as BigDecimal))); sendEvent(name:"coolingSetpoint", value:t); if(m!="auto") sendEvent(name:"thermostatSetpoint", value:t) }
def setThermostatSetpoint(t){ String m=device.currentValue("thermostatMode"); writeChar(TAID,20, round1(hubToC(t as BigDecimal))); sendEvent(name:"thermostatSetpoint", value:t); if(m=="cool") sendEvent(name:"coolingSetpoint", value:t); else if(m=="heat") sendEvent(name:"heatingSetpoint", value:t) }
def setDesiredTemperature(t){
    String m=device.currentValue("thermostatMode"); BigDecimal c=round1(hubToC(t as BigDecimal))
    if(m=="auto"){ writeChars([[TAID,22,c],[TAID,23,c]]); sendEvent(name:"coolingSetpoint", value:t); sendEvent(name:"heatingSetpoint", value:t) }
    else { writeChar(TAID,20,c); sendEvent(name:"thermostatSetpoint", value:t); if(m=="cool") sendEvent(name:"coolingSetpoint", value:t); else if(m=="heat") sendEvent(name:"heatingSetpoint", value:t) }
}
def raiseSetpoint(){ adjustSetpoint(1) }
def lowerSetpoint(){ adjustSetpoint(-1) }
void adjustSetpoint(BigDecimal d){
    String mode = device.currentValue("thermostatMode")
    if(mode=="cool" || mode=="heat"){ def sp=device.currentValue("thermostatSetpoint"); if(sp!=null){ BigDecimal nv=(sp as BigDecimal)+d; writeChar(TAID,20, round1(hubToC(nv))); sendEvent(name:"thermostatSetpoint", value:nv); sendEvent(name:(mode=="cool"?"coolingSetpoint":"heatingSetpoint"), value:nv) } }
    else if(mode=="auto"){
        def c=device.currentValue("coolingSetpoint"); def h=device.currentValue("heatingSetpoint")
        if(c!=null && h!=null){ BigDecimal nc=(c as BigDecimal)+d, nh=(h as BigDecimal)+d; writeChars([[TAID,22,round1(hubToC(nc))],[TAID,23,round1(hubToC(nh))]]); sendEvent(name:"coolingSetpoint", value:nc); sendEvent(name:"heatingSetpoint", value:nh) }
    } else { logInfo "HAP: mode is off — nothing to adjust" }
}
def resumeProgram(){ writeChar(TAID,48, true) }
// ecobee comfort profiles over HAP iid40 (write) — confirmed mapping: Home=0, Sleep=1, Away=2 (3=manual hold, read-only)
def setComfortProfile(String p){ def v=[Home:0,Sleep:1,Away:2][p]; if(v!=null){ writeChar(TAID,40, v as int); sendEvent(name:"comfortProfile", value:p) } else log.warn "HAP: unknown comfort profile $p" }
def setHumiditySetpoint(h){ writeChar(TAID,25, (h as BigDecimal)); sendEvent(name:"humiditySetpoint", value:(h as int), unit:"%") }
def setCharacteristic(String aidIid, String value){ def p=aidIid.split("\\."); def v = value.isNumber()? (value.contains(".")? (value as BigDecimal):(value as Integer)) : value; writeChar(p[0] as long, p[1] as int, v) }
// HAP iid75 = TargetFanState: 0=Manual(fan ON/continuous), 1=Auto
def setThermostatFanMode(String m){ boolean on=(m?.toLowerCase()=="on"); writeChar(TAID,75, on?0:1); sendEvent(name:"thermostatFanMode", value: on?"on":"auto") }
def fanOn(){ setThermostatFanMode("on") }
def fanAuto(){ setThermostatFanMode("auto") }
def fanCirculate(){ setThermostatFanMode("on") }
// macgyver: the ecobee's per-hour fan minimum isn't exposed over HAP, so emulate a timed blower run —
// turn the fan On, then back to Auto after N minutes (driver-timed). Drive it from a rule/webCoRE per hour.
def setFanRunTime(minutes){ int n=(minutes as int); if(n<=0){ setThermostatFanMode("auto"); return }; state.fanPriorHold = (device.currentValue("onHold")=="true"); setThermostatFanMode("on"); runIn(n*60, "fanRunTimeEnd") }
// Return the fan to Auto, and resume the schedule ONLY if this run created the hold. Turning the ecobee's fan On
// creates a HOLD that fan->Auto doesn't clear, so a run started from the schedule must resume to clean up after
// itself. But if a hold was ALREADY active when the run started (state.fanPriorHold), fan-on just folded into
// that hold — so we leave it alone (a blanket resume would wipe the user's pre-existing hold). Verified on hardware.
def fanRunTimeEnd(){ setThermostatFanMode("auto"); if(state.fanPriorHold != true) resumeProgram(); state.remove("fanPriorHold") }
// macgyver: temporary override -> set a comfort profile or a temp now, then auto-resume the schedule after N minutes
def holdUntil(String target, minutes){
    String t=target?.trim()
    if(t?.isNumber()) setDesiredTemperature(t as BigDecimal)
    else setComfortProfile(t?.toLowerCase()?.capitalize())   // Home / Away / Sleep
    int n=(minutes as int); if(n>0) runIn(n*60, "resumeProgram")
}
// macgyver: nudge the setpoint by +/- degrees for N minutes, then resume the schedule
def boost(degrees, minutes){
    adjustSetpoint(degrees as BigDecimal)
    int n=(minutes as int); if(n>0) runIn(n*60, "resumeProgram")
}
def setSchedule(s){}

// ===== unit conversion (device-specific) =====
BigDecimal round1(BigDecimal v){ return (v*10).setScale(0, java.math.RoundingMode.HALF_UP)/10 }
// humanize an elapsed-seconds value into a compact top-2-unit string (e.g. "3h 50m", "13d 4h", "1mo 2d").
// null/negative -> "unknown" (-1 is the ecobee's "unknown" sentinel). Approximate: month=30d, year=365d.
String humanizeDuration(v){
    if(v==null) return "unknown"
    long s = v as long
    if(s < 0) return "unknown"
    if(s == 0) return "0s"
    def units = [[31536000L,"y"],[2592000L,"mo"],[86400L,"d"],[3600L,"h"],[60L,"m"],[1L,"s"]]
    def parts = []
    for(u in units){ long q=(long)(s/(u[0] as long)); if(q>0){ parts << "${q}${u[1]}"; s-=q*(u[0] as long) }; if(parts.size()==2) break }
    return parts.join(" ")
}
boolean isF(){ return (location?.temperatureScale ?: "F") == "F" }
def hubToC(BigDecimal t){ isF()? ((t-32)*5/9) : t }
def cToHub(v){ if(v==null) return null; def c=(v as BigDecimal); return isF()? round1(c*9/5+32) : round1(c) }
// Setpoints: round to a WHOLE °F for °F users. The ecobee is whole-°F-native, but HomeKit only exposes the
// value in Celsius, so the °F->°C->°F round-trip adds up to ~0.1° of noise (e.g. 80°F reads back as 80.1).
// Rounding recovers the intended whole number; the arrows stay clean. (°C users keep 0.5° resolution.)
def cToHubSet(v){ if(v==null) return null; def c=(v as BigDecimal)
    // Round to a WHOLE °F only when the hub shows °F AND the ecobee itself is °F-native. A °F-native ecobee uses
    // whole-°F setpoints whose °C value gets quantized to a tenth, so the °C->°F round-trip adds ~0.1 noise
    // (80°F -> 26.7°C -> 80.1) — rounding recovers the intended whole number. A °C-native ecobee steps in 0.5°C,
    // and 0.5°C = 0.9°F EXACTLY, so those tenths (79.7, 72.5) are real, not noise — show them as-is, don't round.
    if(isF() && state.ecobeeF != false) return (c*9/5+32).setScale(0, java.math.RoundingMode.HALF_UP)
    return isF()? round1(c*9/5+32) : round1(c) }

// debug: fetch /accessories over the live session and log a compact structural map (for diagnosing unknown models)
def dumpAccessories(){
    if(state.live && state.sess){ state.dumpReq=true; logInfo "HAP: requesting /accessories dump…"; sendEncrypted("GET /accessories HTTP/1.1\r\nHost: ${settings.ip}\r\n\r\n") }
    else { log.warn "HAP: not connected — open the session first (device must be paired and live)" }
}

// ===== library callbacks (the hapCore engine invokes these) =====
// CSV of "aid.iid" to GET on connect / refresh / keepalive
String readIds(){
    def ids=[]; TCHARS.keySet().each{ ids << "${TAID}.${it}" }
    (state.sensors ?: []).each{ s-> [s.temp,s.occ,s.motion,s.contact,s.batt,s.lowbatt,s.serial,s.name,s.motionSince,s.occSince].each{ if(it!=null) ids << "${s.aid}.${it}" } }
    return ids.join(",")
}
// build the sensor topology from /accessories and create one child per sensor (the thermostat's own sensor + remotes)
void onAccessories(j){
    if(state.dumpReq){ state.dumpReq=false; dumpAcc(j) }   // the library delegates the Dump Accessories request to this callback
    def code={ x-> x.replace("-","").toUpperCase().replaceAll(/^0+/,"") }
    def sensors=[]
    j.accessories.each{ acc->
        if(acc.aid==TAID){
            // the thermostat's OWN built-in motion/occupancy -> its own child sensor device, so the parent
            // stays a pure Thermostat (a Thermostat + MotionSensor/PresenceSensor can't be exported to HomeKit)
            // surface the thermostat's HAP AccessoryInformation as attributes (values arrive with /accessories)
            acc.services.each{ sv-> if(code(sv.type)=="3E") sv.characteristics.each{ c-> def cc=code(c.type)
                if(cc=="20") sendEvent(name:"manufacturer", value: c.value)
                else if(cc=="21") sendEvent(name:"model", value: c.value)
                else if(cc=="30") sendEvent(name:"serial", value: c.value)
                else if(cc=="52") sendEvent(name:"firmware", value: c.value)
            } }
            def ts=[aid:TAID, isMain:true, temp:19]   // temp 19 = the thermostat's reading, gives the child a valid temp
            acc.services.each{ sv-> def sc=code(sv.type)
                sv.characteristics.each{ c-> def cc=code(c.type)
                    if(sc=="85" && cc=="22") ts.motion=c.iid
                    else if(sc=="86" && cc=="71") ts.occ=c.iid
                    else if(sc=="85" && cc=="BFE61C704A4011E6BDF40800200C9A66") ts.motionSince=c.iid   // ecobee vendor: seconds since last motion (inferred)
                    else if(sc=="86" && cc=="A8F798E04A4011E6BDF40800200C9A66") ts.occSince=c.iid       // ecobee vendor: seconds since last occupancy (inferred)
                }
            }
            if(ts.motion || ts.occ) sensors << ts
            return
        }
        if(!acc.services.any{ code(it.type) in ["8A","80","85","86"] }) return   // a sensor = has a temperature, contact, motion, or occupancy service
        def s=[aid:acc.aid]
        acc.services.each{ sv-> def sc=code(sv.type)
            sv.characteristics.each{ c-> def cc=code(c.type)
                if(sc=="8A" && cc=="11") s.temp=c.iid
                else if(sc=="86" && cc=="71") s.occ=c.iid
                else if(sc=="85" && cc=="22") s.motion=c.iid
                else if(sc=="80" && cc=="6A") s.contact=c.iid   // ContactSensor (door/window SmartSensor EBDWC01): 0=closed, 1=open
                else if(sc=="96" && cc=="68") s.batt=c.iid
                else if(sc=="96" && cc=="79") s.lowbatt=c.iid
                else if(sc=="3E" && cc=="30") s.serial=c.iid
                else if(sc=="3E" && cc=="23") s.name=c.iid
                else if(sc=="85" && cc=="BFE61C704A4011E6BDF40800200C9A66") s.motionSince=c.iid   // ecobee vendor: seconds since last motion (inferred)
                else if(sc=="86" && cc=="A8F798E04A4011E6BDF40800200C9A66") s.occSince=c.iid       // ecobee vendor: seconds since last occupancy (inferred)
            }
        }
        sensors << s
    }
    state.sensors=sensors
    state.services=true   // mark discovery complete so the library goes straight to the live session on reconnect (its gate is state.services==null ? discover : live)
    // battery reporting was removed (unreliable on ecobee SmartSensors) — clear any stale battery/lowBattery readings left on existing children by older versions
    getChildDevices()?.each{ cd-> cd.deleteCurrentState("battery"); cd.deleteCurrentState("lowBattery") }
    if(sensors.isEmpty())
        logInfo "HAP: this thermostat has no built-in occupancy/motion sensor and no remote sensors — no sensor child device is created (this is normal, e.g. ecobee3 lite)"
    else
        logInfo "HAP: discovered ${sensors.findAll{!it.isMain}.size()} remote sensor(s)${sensors.any{it.isMain}?' + thermostat sensor':''}"
}
// the event-subscription PUT body (which aid.iid pairs to subscribe to)
String subscribeBody(){
    def ev=[]; [17,18,19,20,22,23,24,25,65,66,75,76].each{ ev << "{\"aid\":${TAID},\"iid\":${it},\"ev\":true}" }
    (state.sensors ?: []).each{ s-> [s.temp,s.occ,s.motion,s.contact,s.batt,s.lowbatt].each{ if(it!=null) ev << "{\"aid\":${s.aid},\"iid\":${it},\"ev\":true}" } }
    String b="{\"characteristics\":[${ev.join(',')}]}"
    return "PUT /characteristics HTTP/1.1\r\nHost: ${settings.ip}\r\nContent-Type: application/hap+json\r\nContent-Length: ${b.getBytes('UTF-8').length}\r\nConnection: keep-alive\r\n\r\n"+b
}
// apply a /characteristics read or event push to thermostat attributes + child sensors
void onCharacteristics(j){
    def vmap=[:]   // "aid.iid" -> value
    j.characteristics.each{ vmap["${it.aid}.${it.iid}"]= it.value }
    // ---- thermostat ----
    def g={ iid-> vmap["${TAID}.${iid}"] }
    if(g(21)!=null) state.ecobeeF = ((g(21) as int)==1)   // ecobee's own display unit (iid21): 1=°F (whole-°F native), 0=°C (0.5°C native) — governs setpoint rounding
    if(g(19)!=null) sendEvent(name:"temperature", value: cToHub(g(19)), unit:"°${isF()?'F':'C'}")
    if(g(24)!=null) sendEvent(name:"humidity", value: g(24) as int, unit:"%")
    if(g(18)!=null) sendEvent(name:"thermostatMode", value: [0:"off",1:"heat",2:"cool",3:"auto"][g(18) as int])
    // Setpoint reporting is mode-aware (matches the writes): in heat/cool the real target is iid20
    // (TargetTemperature); iid22/iid23 (thresholds) only apply in Auto. Reporting iid22/23 in cool/heat
    // shows a stale Auto threshold instead of the actual target.
    String tmode = (g(18)!=null) ? [0:"off",1:"heat",2:"cool",3:"auto"][g(18) as int] : device.currentValue("thermostatMode")
    // thermostatSetpoint tracks the ACTIVE desired temperature for the current mode, so it's always meaningful:
    // heat -> heat target; cool -> cool target; auto -> the threshold actually being regulated (by operating
    // state; when idle, whichever threshold is nearer the current temp). In auto HAP's single iid20 is just the
    // midpoint of the two thresholds, so we do NOT use it there.
    if(tmode=="cool"){
        if(g(20)!=null){ sendEvent(name:"coolingSetpoint", value: cToHubSet(g(20))); sendEvent(name:"thermostatSetpoint", value: cToHubSet(g(20))) }
        if(g(23)!=null) sendEvent(name:"heatingSetpoint", value: cToHubSet(g(23)))
        sendEvent(name:"setpointDetail", value:"cooling setpoint")
    } else if(tmode=="heat"){
        if(g(20)!=null){ sendEvent(name:"heatingSetpoint", value: cToHubSet(g(20))); sendEvent(name:"thermostatSetpoint", value: cToHubSet(g(20))) }
        if(g(22)!=null) sendEvent(name:"coolingSetpoint", value: cToHubSet(g(22)))
        sendEvent(name:"setpointDetail", value:"heating setpoint")
    } else if(tmode=="auto"){
        def cThr=g(22), hThr=g(23)
        if(cThr!=null) sendEvent(name:"coolingSetpoint", value: cToHubSet(cThr))
        if(hThr!=null) sendEvent(name:"heatingSetpoint", value: cToHubSet(hThr))
        int op = (g(17)!=null) ? (g(17) as int) : -1
        def active = (op==2) ? cThr : (op==1) ? hThr : null   // cooling -> cool threshold, heating -> heat threshold
        String detail = (op==2) ? "cooling setpoint (auto)" : (op==1) ? "heating setpoint (auto)" : "auto — nearest target (idle)"
        if(active==null && cThr!=null && hThr!=null && g(19)!=null){   // idle -> threshold nearer the current temp
            BigDecimal t=g(19) as BigDecimal; active = ((cThr as BigDecimal)-t) <= (t-(hThr as BigDecimal)) ? cThr : hThr
        }
        if(active==null) active = (cThr!=null ? cThr : hThr)
        if(active!=null) sendEvent(name:"thermostatSetpoint", value: cToHubSet(active))
        sendEvent(name:"setpointDetail", value: detail)
    } else {   // off -> no active target; surface both thresholds, leave thermostatSetpoint at HAP's single value
        if(g(22)!=null) sendEvent(name:"coolingSetpoint", value: cToHubSet(g(22)))
        if(g(23)!=null) sendEvent(name:"heatingSetpoint", value: cToHubSet(g(23)))
        if(g(20)!=null) sendEvent(name:"thermostatSetpoint", value: cToHubSet(g(20)))
        sendEvent(name:"setpointDetail", value:"off — no active setpoint")
    }
    if(g(17)!=null) sendEvent(name:"thermostatOperatingState", value: [0:"idle",1:"heating",2:"cooling"][g(17) as int])
    if(g(75)!=null) sendEvent(name:"thermostatFanMode", value: (g(75) as int)==1?"auto":"on")
    // HAP comfort enum (iid33): 0=Home, 1=Sleep, 2=Away, 3=everything-else (a hold, vacation, OR a custom
    // climate — ecobee only numbers the 3 built-ins; the custom's NAME isn't on HAP). Disambiguate 3 using
    // the hold-end char (iid41): a real hold has a future end date; a scheduled non-standard/custom climate
    // uses the 2014-01-03 "no hold" sentinel. onHold keys off the actual hold, NOT off iid33==3.
    if(g(33)!=null){
        int ci = g(33) as int
        def he = g(41)?.toString()
        boolean held = (he != null) ? !he.startsWith("2014-01-03") : !((device.currentValue("holdEndsAt") ?: "none") in ["none",""])
        String cp = [0:"Home",1:"Sleep",2:"Away"][ci] ?: (held ? "Hold" : "Custom")
        sendEvent(name:"comfortProfile", value: cp)
        sendEvent(name:"onHold", value: held)
    }
    // Report "none" (not "") when there's no hold — Hubitat drops empty-string events, so "" left a stale date lingering after a resume.
    if(g(41)!=null){ String h=g(41).toString().replaceAll(/S$/,""); sendEvent(name:"holdEndsAt", value: h.startsWith("2014-01-03")?"none":h) }
    if(g(25)!=null) sendEvent(name:"humiditySetpoint", value: g(25) as int, unit:"%")
    if(g(76)!=null) sendEvent(name:"fanState", value: [0:"inactive",1:"idle",2:"blowing"][g(76) as int] ?: "unknown")
    if(g(54)!=null){ String a=g(54).toString(); sendEvent(name:"thermostatAlert", value: a); sendEvent(name:"alertActive", value: !(a.toLowerCase().contains("no pending alert"))) }
    // per-profile setpoints (HAP iid34-39 follow ecobee's fixed Home/Away/Sleep climate order)
    if(g(34)!=null) sendEvent(name:"homeHeatSetpoint",  value: cToHubSet(g(34)))
    if(g(35)!=null) sendEvent(name:"homeCoolSetpoint",  value: cToHubSet(g(35)))
    if(g(36)!=null) sendEvent(name:"awayHeatSetpoint",  value: cToHubSet(g(36)))
    if(g(37)!=null) sendEvent(name:"awayCoolSetpoint",  value: cToHubSet(g(37)))
    if(g(38)!=null) sendEvent(name:"sleepHeatSetpoint", value: cToHubSet(g(38)))
    if(g(39)!=null) sendEvent(name:"sleepCoolSetpoint", value: cToHubSet(g(39)))
    // thermostat's own motion (iid66) / occupancy (iid65) are routed to a child sensor device (see the
    // sensor loop below + onAccessories), NOT to parent capabilities — keeps the parent exportable to HomeKit.
    sendEvent(name:"supportedThermostatModes", value: '["off","heat","cool","auto"]')
    sendEvent(name:"supportedThermostatFanModes", value: '["on","auto"]')
    // ---- custom params -> attribute (only when present; events are partial) ----
    def params=[:]; TCHARS.each{ iid,label-> if(label.startsWith("c_") && g(iid)!=null) params[label]= g(iid) }
    if(params && settings.debugLog) sendEvent(name:"customParams", value: groovy.json.JsonOutput.toJson(params))   // diagnostic-only: shown only while debug logging is on
    rep("READ temp=${cToHub(g(19))} hum=${g(24)} mode=${g(18)!=null?[0:'off',1:'heat',2:'cool',3:'auto'][g(18) as int]:'-'} op=${g(17)!=null?[0:'idle',1:'heating',2:'cooling'][g(17) as int]:'-'} params=${params}")
    // ---- discovered sensors -> child devices (update only present attrs; events are partial) ----
    (state.sensors ?: []).each{ s->
        def val={ iid-> (iid!=null)? vmap["${s.aid}.${iid}"] : null }
        // DNI namespaced with the parent device id so multiple thermostats don't collide (esp. the
        // thermostat's own sensor, always aid 1). Adopt a pre-v0.12.1 child ("hap-<aid>") if present so
        // existing single-thermostat installs keep their child instead of getting a duplicate.
        String dni="hap-${device.id}-${s.aid}"
        def cd=getChildDevice(dni) ?: getChildDevice("hap-${s.aid}")
        if(!cd){
            if(val(s.temp)==null && val(s.occ)==null && val(s.motion)==null && val(s.contact)==null) return   // need some initial data to create
            String nm = s.isMain ? "${device.displayName} Sensor" : (val(s.name) ?: "Ecobee Sensor ${s.aid}")
            try{ cd=addChildDevice("RamSet","Ecobee HAP Remote Sensor",dni,[name:nm,label:nm]) }catch(e){ log.warn "child ${s.aid}: ${e}"; return }
        }
        if(val(s.serial)!=null) cd.sendEvent(name:"ecobeeId", value: val(s.serial))
        if(val(s.temp)!=null) cd.sendEvent(name:"temperature", value: cToHub(val(s.temp)), unit:"°${isF()?'F':'C'}")
        if(val(s.occ)!=null) cd.sendEvent(name:"presence", value: ((val(s.occ) as int)>0?"present":"not present"))
        if(val(s.motion)!=null) cd.sendEvent(name:"motion", value: (val(s.motion)?"active":"inactive"))
        if(val(s.contact)!=null) cd.sendEvent(name:"contact", value: (val(s.contact) as int)==0 ? "closed" : "open")   // HAP ContactSensorState: 0=contact detected (closed), 1=not detected (open)
        // Battery reporting REMOVED (0.18.1): ecobee SmartSensors report BatteryLevel=100 / StatusLowBattery=not-low
        // over HAP right up until the sensor dies — confirmed on multiple ecobees — so the value is misleading, not
        // informative. The thermostat's alert (thermostatAlert/alertActive) flags a low/lost sensor reliably instead.
        // Re-enable these three emits together with the child's Battery capability if a future firmware reports it accurately.
        // if(val(s.batt)!=null) cd.sendEvent(name:"battery", value: val(s.batt) as int, unit:"%")
        // else if(s.isMain) cd.sendEvent(name:"battery", value: 100, unit:"%")   // thermostat is wired — report full
        // if(val(s.lowbatt)!=null) cd.sendEvent(name:"lowBattery", value: ((val(s.lowbatt) as int)==1?"true":"false"))
        if(val(s.motionSince)!=null){ int ms=val(s.motionSince) as int; cd.sendEvent(name:"secondsSinceMotion", value: ms, unit:"s"); cd.sendEvent(name:"timeSinceMotion", value: humanizeDuration(ms)) }   // ecobee vendor timer (inferred); polled, ~5-min granularity. timeSince* = human-readable companion.
        if(val(s.occSince)!=null){ int os=val(s.occSince) as int; cd.sendEvent(name:"secondsSinceOccupancy", value: os, unit:"s"); cd.sendEvent(name:"timeSinceOccupancy", value: humanizeDuration(os)) }
    }
}
