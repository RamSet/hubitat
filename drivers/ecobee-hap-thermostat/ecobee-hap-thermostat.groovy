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
 * Version: 0.15.2
 * Date: 2026-07-01
 *
 * REQUIRES library: RamSet.hapCore (installed automatically by Hubitat Package Manager).
 *
 * Changelog:
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
 *   "location": "https://raw.githubusercontent.com/RamSet/hubitat/main/drivers/ecobee-hap-thermostat/ecobee-hap-thermostat.groovy",
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
    definition(name: "Ecobee HAP Thermostat", namespace: "RamSet", author: "RamSet", importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/main/drivers/ecobee-hap-thermostat/ecobee-hap-thermostat.groovy") {
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
        attribute "homeHeatSetpoint", "number"  // per-comfort-profile targets (HAP iid34-39, Home/Away/Sleep)
        attribute "homeCoolSetpoint", "number"
        attribute "awayHeatSetpoint", "number"
        attribute "awayCoolSetpoint", "number"
        attribute "sleepHeatSetpoint", "number"
        attribute "sleepCoolSetpoint", "number"
        attribute "customParams", "string"
        attribute "hapStatus", "string"
        attribute "diag", "string"
    }
    preferences {
        input "ip", "string", title: "Thermostat IP address", required: true
        if (!(state.paired==true || settings?.iosLtsk)) {   // settings is null at code-save time -> MUST use safe-nav (settings?.) or it NPEs and the save fails
            input "setupCode", "string", title: "HomeKit setup code — 8 digits, no dashes (e.g. 12345678). Enter and Save to pair.", required: false
        }
        input "infoLog", "bool", title: "Enable info logging", defaultValue: true
        input "debugLog", "bool", title: "Enable debug logging", defaultValue: false
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
    if(settings.debugLog) sendEvent(name:"diag", value:"")
    state.remove("sensors"); state.remove("services")   // force a fresh /accessories discovery on Save so sensor topology (incl. the thermostat's own sensor) rebuilds
    ["srpK","srpA","srpM1","psSeed","psEncKey","psPid","psstage"].each{ state.remove(it) }   // shed stale pair-setup temporaries (re-created if pairing; ~1.2KB reclaimed on already-paired hubs)
    if(settings.debugLog) runIn(1800,"logsOff")   // debug is off by default and auto-disables after 30 min (it writes state on every frame — keeps the device's busy% + state size down)
    if(settings.setupCode && !isPaired()){ logInfo "HAP: setup code entered — pairing"; runIn(1,"pair") }
    else if(isPaired()){ runIn(2,"startSession"); runEvery10Minutes("ensureUp"); runEvery5Minutes("refresh") }   // live event mode is the default once paired; ensureUp is a reconnect backstop; refresh re-reads the no-event chars (comfort profile/hold-end/per-profile setpoints/alert) the pure-listen engine won't poll
}
def logsOff(){ device.updateSetting("debugLog",[value:"false",type:"bool"]); state.diag=[]; sendEvent(name:"diag", value:""); log.info "HAP: debug logging auto-disabled" }

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
def setFanRunTime(minutes){ int n=(minutes as int); if(n<=0){ setThermostatFanMode("auto"); return }; setThermostatFanMode("on"); runIn(n*60, "fanRunTimeEnd") }
def fanRunTimeEnd(){ setThermostatFanMode("auto") }
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
boolean isF(){ return (location?.temperatureScale ?: "F") == "F" }
def hubToC(BigDecimal t){ isF()? ((t-32)*5/9) : t }
def cToHub(v){ if(v==null) return null; def c=(v as BigDecimal); return isF()? round1(c*9/5+32) : round1(c) }

// debug: fetch /accessories over the live session and log a compact structural map (for diagnosing unknown models)
def dumpAccessories(){
    if(state.live && state.sess){ state.dumpReq=true; logInfo "HAP: requesting /accessories dump…"; sendEncrypted("GET /accessories HTTP/1.1\r\nHost: ${settings.ip}\r\n\r\n") }
    else { log.warn "HAP: not connected — open the session first (device must be paired and live)" }
}

// ===== library callbacks (the hapCore engine invokes these) =====
// CSV of "aid.iid" to GET on connect / refresh / keepalive
String readIds(){
    def ids=[]; TCHARS.keySet().each{ ids << "${TAID}.${it}" }
    (state.sensors ?: []).each{ s-> [s.temp,s.occ,s.motion,s.batt,s.lowbatt,s.serial,s.name].each{ if(it!=null) ids << "${s.aid}.${it}" } }
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
            def ts=[aid:TAID, isMain:true, temp:19]   // temp 19 = the thermostat's reading, gives the child a valid temp
            acc.services.each{ sv-> def sc=code(sv.type)
                sv.characteristics.each{ c-> def cc=code(c.type)
                    if(sc=="85" && cc=="22") ts.motion=c.iid
                    else if(sc=="86" && cc=="71") ts.occ=c.iid
                }
            }
            if(ts.motion || ts.occ) sensors << ts
            return
        }
        if(!acc.services.any{ code(it.type)=="8A" }) return   // remote sensor = has TemperatureSensor service
        def s=[aid:acc.aid]
        acc.services.each{ sv-> def sc=code(sv.type)
            sv.characteristics.each{ c-> def cc=code(c.type)
                if(sc=="8A" && cc=="11") s.temp=c.iid
                else if(sc=="86" && cc=="71") s.occ=c.iid
                else if(sc=="85" && cc=="22") s.motion=c.iid
                else if(sc=="96" && cc=="68") s.batt=c.iid
                else if(sc=="96" && cc=="79") s.lowbatt=c.iid
                else if(sc=="3E" && cc=="30") s.serial=c.iid
                else if(sc=="3E" && cc=="23") s.name=c.iid
            }
        }
        sensors << s
    }
    state.sensors=sensors
    state.services=true   // mark discovery complete so the library goes straight to the live session on reconnect (its gate is state.services==null ? discover : live)
    if(sensors.isEmpty())
        logInfo "HAP: this thermostat has no built-in occupancy/motion sensor and no remote sensors — no sensor child device is created (this is normal, e.g. ecobee3 lite)"
    else
        logInfo "HAP: discovered ${sensors.findAll{!it.isMain}.size()} remote sensor(s)${sensors.any{it.isMain}?' + thermostat sensor':''}"
}
// the event-subscription PUT body (which aid.iid pairs to subscribe to)
String subscribeBody(){
    def ev=[]; [17,18,19,20,22,23,24,25,65,66,75,76].each{ ev << "{\"aid\":${TAID},\"iid\":${it},\"ev\":true}" }
    (state.sensors ?: []).each{ s-> [s.temp,s.occ,s.motion,s.batt,s.lowbatt].each{ if(it!=null) ev << "{\"aid\":${s.aid},\"iid\":${it},\"ev\":true}" } }
    String b="{\"characteristics\":[${ev.join(',')}]}"
    return "PUT /characteristics HTTP/1.1\r\nHost: ${settings.ip}\r\nContent-Type: application/hap+json\r\nContent-Length: ${b.getBytes('UTF-8').length}\r\nConnection: keep-alive\r\n\r\n"+b
}
// apply a /characteristics read or event push to thermostat attributes + child sensors
void onCharacteristics(j){
    def vmap=[:]   // "aid.iid" -> value
    j.characteristics.each{ vmap["${it.aid}.${it.iid}"]= it.value }
    // ---- thermostat ----
    def g={ iid-> vmap["${TAID}.${iid}"] }
    if(g(19)!=null) sendEvent(name:"temperature", value: cToHub(g(19)), unit:"°${isF()?'F':'C'}")
    if(g(24)!=null) sendEvent(name:"humidity", value: g(24) as int, unit:"%")
    if(g(18)!=null) sendEvent(name:"thermostatMode", value: [0:"off",1:"heat",2:"cool",3:"auto"][g(18) as int])
    // Setpoint reporting is mode-aware (matches the writes): in heat/cool the real target is iid20
    // (TargetTemperature); iid22/iid23 (thresholds) only apply in Auto. Reporting iid22/23 in cool/heat
    // shows a stale Auto threshold instead of the actual target.
    String tmode = (g(18)!=null) ? [0:"off",1:"heat",2:"cool",3:"auto"][g(18) as int] : device.currentValue("thermostatMode")
    if(g(20)!=null) sendEvent(name:"thermostatSetpoint", value: cToHub(g(20)))
    if(tmode=="cool"){
        if(g(20)!=null) sendEvent(name:"coolingSetpoint", value: cToHub(g(20)))
        if(g(23)!=null) sendEvent(name:"heatingSetpoint", value: cToHub(g(23)))
    } else if(tmode=="heat"){
        if(g(20)!=null) sendEvent(name:"heatingSetpoint", value: cToHub(g(20)))
        if(g(22)!=null) sendEvent(name:"coolingSetpoint", value: cToHub(g(22)))
    } else {   // auto / off -> the two thresholds are the active setpoints
        if(g(22)!=null) sendEvent(name:"coolingSetpoint", value: cToHub(g(22)))
        if(g(23)!=null) sendEvent(name:"heatingSetpoint", value: cToHub(g(23)))
    }
    if(g(17)!=null) sendEvent(name:"thermostatOperatingState", value: [0:"idle",1:"heating",2:"cooling"][g(17) as int])
    if(g(75)!=null) sendEvent(name:"thermostatFanMode", value: (g(75) as int)==1?"auto":"on")
    if(g(33)!=null){ String cp=[0:"Home",1:"Sleep",2:"Away",3:"Hold"][g(33) as int] ?: "Hold"; sendEvent(name:"comfortProfile", value: cp); sendEvent(name:"onHold", value: (cp=="Hold")) }
    if(g(41)!=null){ String h=g(41).toString().replaceAll(/S$/,""); sendEvent(name:"holdEndsAt", value: h.startsWith("2014-01-03")?"":h) }
    if(g(25)!=null) sendEvent(name:"humiditySetpoint", value: g(25) as int, unit:"%")
    if(g(76)!=null) sendEvent(name:"fanState", value: [0:"inactive",1:"idle",2:"blowing"][g(76) as int] ?: "unknown")
    if(g(54)!=null){ String a=g(54).toString(); sendEvent(name:"thermostatAlert", value: a); sendEvent(name:"alertActive", value: !(a.toLowerCase().contains("no pending alert"))) }
    // per-profile setpoints (HAP iid34-39 follow ecobee's fixed Home/Away/Sleep climate order)
    if(g(34)!=null) sendEvent(name:"homeHeatSetpoint",  value: cToHub(g(34)))
    if(g(35)!=null) sendEvent(name:"homeCoolSetpoint",  value: cToHub(g(35)))
    if(g(36)!=null) sendEvent(name:"awayHeatSetpoint",  value: cToHub(g(36)))
    if(g(37)!=null) sendEvent(name:"awayCoolSetpoint",  value: cToHub(g(37)))
    if(g(38)!=null) sendEvent(name:"sleepHeatSetpoint", value: cToHub(g(38)))
    if(g(39)!=null) sendEvent(name:"sleepCoolSetpoint", value: cToHub(g(39)))
    // thermostat's own motion (iid66) / occupancy (iid65) are routed to a child sensor device (see the
    // sensor loop below + onAccessories), NOT to parent capabilities — keeps the parent exportable to HomeKit.
    sendEvent(name:"supportedThermostatModes", value: '["off","heat","cool","auto"]')
    sendEvent(name:"supportedThermostatFanModes", value: '["on","auto"]')
    // ---- custom params -> attribute (only when present; events are partial) ----
    def params=[:]; TCHARS.each{ iid,label-> if(label.startsWith("c_") && g(iid)!=null) params[label]= g(iid) }
    if(params){ sendEvent(name:"customParams", value: groovy.json.JsonOutput.toJson(params)) }
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
            if(val(s.temp)==null && val(s.occ)==null && val(s.motion)==null) return   // need some initial data to create
            String nm = s.isMain ? "${device.displayName} Sensor" : (val(s.name) ?: "Ecobee Sensor ${s.aid}")
            try{ cd=addChildDevice("RamSet","Ecobee HAP Remote Sensor",dni,[name:nm,label:nm]) }catch(e){ log.warn "child ${s.aid}: ${e}"; return }
        }
        if(val(s.serial)!=null) cd.sendEvent(name:"ecobeeId", value: val(s.serial))
        if(val(s.temp)!=null) cd.sendEvent(name:"temperature", value: cToHub(val(s.temp)), unit:"°${isF()?'F':'C'}")
        if(val(s.occ)!=null) cd.sendEvent(name:"presence", value: ((val(s.occ) as int)>0?"present":"not present"))
        if(val(s.motion)!=null) cd.sendEvent(name:"motion", value: (val(s.motion)?"active":"inactive"))
        if(val(s.batt)!=null) cd.sendEvent(name:"battery", value: val(s.batt) as int, unit:"%")
        else if(s.isMain) cd.sendEvent(name:"battery", value: 100, unit:"%")   // thermostat is wired — report full
        if(val(s.lowbatt)!=null) cd.sendEvent(name:"lowBattery", value: ((val(s.lowbatt) as int)==1?"true":"false"))
    }
}
