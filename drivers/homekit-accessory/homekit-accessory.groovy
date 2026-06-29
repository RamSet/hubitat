/*
 * HomeKit Accessory (HAP Import)
 *
 * Description:
 *   Imports a LAN/Wi-Fi HomeKit (HAP) accessory into Hubitat. Enter the
 *   accessory's IP and 8-digit HomeKit setup code; this driver pairs to it,
 *   reads its accessory database (/accessories), auto-maps each supported
 *   HomeKit service to a Hubitat capability, and auto-creates a child device
 *   per mapped service. It holds one persistent encrypted session and pushes
 *   live updates to the children (and write-back for controllable services).
 *
 *   This is the "bridge" device for one accessory. The actual capabilities
 *   live on the child devices it creates, because Hubitat capabilities are
 *   static (declared at compile time) and one accessory can expose several
 *   services of different classes.
 *
 *   Service coverage: Switch/Outlet, Light, Lock, Garage Door, Window Shade, Fan,
 *   Contact/Motion/Occupancy/Temperature/Humidity/Light sensors, Battery. Unmapped -> Dump Accessories.
 *
 * Author: RamSet
 * Version: 0.10.1
 *
 * Changelog:
 *  v0.10.1 - Generic (unmapped) children are now self-identifying: their name carries the HAP service
 *           type, e.g. "<name> [HAP svc 8C]", so multiple unmapped services on one accessory are
 *           distinct and the type to map is visible at a glance. The tag is also back-filled onto
 *           already-created generic children on the next discovery.
 *  v0.10.0 - Renamed all device types to "HomeKit HAP ..." (e.g. HomeKit HAP Switch, HomeKit HAP
 *           Accessory) so they're clearly identifiable in the driver-type dropdown and don't blur into
 *           Hubitat's built-in HomeKit features. No functional change; existing devices keep working
 *           (Hubitat binds devices to drivers internally, not by display name).
 *  v0.9.5 - PURE LISTEN: never poll at all (like a real HomeKit controller). The ev:true subscription
 *           carries real-time updates; the only watchdog now RECONNECTS (never polls) after a long
 *           total silence, so a silently-dead link still recovers without the GET that kills cheap chips.
 *  v0.9.4 - Only probe when idle: the keepalive GET is skipped whenever the accessory has sent any
 *           frame within the interval (events prove liveness). The probe is what makes cheap chips
 *           drop the session, so while events flow we never poll — the session holds like HomeKit.
 *  v0.9.3 - Behave like a real HomeKit controller: SUBSCRIBE then LISTEN, do not poll. Frequent
 *           keepalive GETs were making cheap accessories (Meross) drop the session ~60s in while
 *           Apple holds it for days. Liveness probe relaxed to every 5 min; events carry real-time
 *           updates. Reverses the wrong-way 10s polling from 0.9.2.
 *  v0.9.2 - Reliability for accessories that recycle the HAP session (~60s, e.g. Meross): keepalive
 *           every 10s (was 30s), detect a dropped/zombie session in ~20s (2 misses, was 3), and
 *           reconnect fast by reusing the known port (skips the ~6s mDNS step).
 *  v0.9.1 - Reliability: reconnect now backs off to 5 min (was a fixed ~60s) and connects are
 *           de-duplicated, so a wedged accessory gets quiet time to recover instead of being
 *           hammered; the ensureUp safety net dropped from 1 min to 10 min.
 *  v0.9.0 - Generic raw fallback: unmapped HomeKit service types now create a HomeKit Generic
 *           child that surfaces every readable characteristic as a JSON attribute (with a
 *           setCharacteristic write), so unknown devices still work and dumps reveal what to map.
 *  v0.8.0 - Generic HomeKit Thermostat (type 4A): mode, operating state, target temperature,
 *           heating/cooling thresholds, and humidity, with mode-aware write-back. Standard
 *           characteristics only (vendor extras like ecobee comfort profiles are out of scope).
 *  v0.7.1 - Battery service (96) now folds into the same accessory's battery-powered sibling
 *           (sensor/lock/shade), so battery shows on the device itself; standalone Battery child
 *           only when nothing can host it.
 *  v0.7.0 - Full device-type set: Lightbulb, Lock, Window Covering, Fan (v1/v2), Motion,
 *           Occupancy, Humidity, Light sensor, Battery, Outlet — plus brand info (manufacturer/
 *           model/firmware) as attributes on every child.
 *  v0.6.1 - Auto-recovery heartbeat (re-establish a dropped session within ~1 min) and a clean
 *           socket close on Save so re-imports stop wedging single-slot accessories.
 *  v0.6.0 - Accessory metadata (manufacturer/model/serial/firmware/hardware), HomeKit Identify
 *           command, and GarageDoor ObstructionDetected.
 *  v0.5.0 - Tolerant keepalive watchdog so persistent sessions actually hold (instant event push);
 *           reconnect only after several consecutive missed keepalives.
 *  v0.4.1 - On-demand discovery now retries until it succeeds; overlapping connects serialized to
 *           protect single-connection-slot accessories.
 *  v0.4.0 - On-demand (poll) connection mode for accessories that won't hold a session, plus a
 *           one-shot connect watchdog.
 *  v0.3.0 - Universal Unpair (HAP RemovePairing — the HomeKit "exclude") and a local-only Forget.
 *  v0.2.0 - GarageDoorOpener (type 41) support.
 *  v0.1.0 - Initial: reusable HAP core library + generic accessory driver with Switch, Contact
 *           Sensor and Temperature Sensor children (pair -> discover -> map -> live events -> write-back).
 *
 * HPM Metadata:
 * {
 *   "package": "HomeKit Import",
 *   "namespace": "RamSet",
 *   "author": "RamSet",
 *   "location": "https://raw.githubusercontent.com/RamSet/hubitat/main/drivers/homekit-accessory/homekit-accessory.groovy",
 *   "description": "Imports a LAN HomeKit accessory into Hubitat: pairs, discovers services, auto-creates child devices, live updates.",
 *   "required": true,
 *   "version": "0.10.1"
 * }
 *
 * Copyright 2026 RamSet — Apache License 2.0, provided as-is, no warranty.
 */

#include RamSet.hapCore

import groovy.transform.Field

metadata {
    definition(name: "HomeKit HAP Accessory", namespace: "RamSet", author: "RamSet", importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/main/drivers/homekit-accessory/homekit-accessory.groovy") {
        capability "Refresh"
        command "pair"
        command "unpair"            // HAP RemovePairing — cleanly release this accessory (like a Z-Wave exclude), then remove its children
        command "forget"            // local-only: clear our keys + children WITHOUT notifying the accessory (use if it's offline/dead)
        command "dumpAccessories"   // debug: log the accessory's full service/characteristic map
        command "rediscover"        // re-read /accessories and rebuild children (after adding/removing services)
        command "identify"          // HomeKit Identify — make the accessory beep/blink to locate it
        attribute "hapStatus", "string"
        attribute "services", "string"   // human-readable list of mapped services
        attribute "manufacturer", "string"
        attribute "model", "string"
        attribute "firmware", "string"
        attribute "diag", "string"
    }
    preferences {
        input "ip", "string", title: "Accessory IP address", required: true
        if (!(state.paired==true || settings?.iosLtsk)) {   // settings is null at code-save time -> MUST use safe-nav
            input "setupCode", "string", title: "HomeKit setup code — 8 digits, no dashes (e.g. 12345678). Enter and Save to pair.", required: false
        }
        input "sessionMode", "enum", title: "Connection mode", options: ["Persistent (event push)","On-demand (poll)"], defaultValue: "Persistent (event push)",
              description: "Persistent = instant updates via a held session (best for well-behaved accessories). On-demand = connect only to read/write + poll (use for flaky accessories like Meross that hard-close the connection)."
        input "pollMins", "number", title: "Poll interval (minutes) — On-demand mode only", defaultValue: 5
        input "infoLog", "bool", title: "Enable info logging", defaultValue: true
        input "debugLog", "bool", title: "Enable debug logging", defaultValue: false
    }
}

// ===== service -> capability mapping (HAP type code -> child driver) =====
// type/char codes are short-form (dashes stripped, uppercased, leading zeros removed) — see hapCode().
@Field static Map SERVICE_MAP = [
    "49": [name:"Switch",             driver:"HomeKit HAP Switch"],              // Switch
    "47": [name:"Outlet",             driver:"HomeKit HAP Switch"],              // Outlet (reuse Switch)
    "43": [name:"Light",              driver:"HomeKit HAP Light"],               // Lightbulb
    "45": [name:"Lock",               driver:"HomeKit HAP Lock"],                // LockMechanism
    "80": [name:"Contact Sensor",     driver:"HomeKit HAP Contact Sensor"],      // ContactSensor
    "85": [name:"Motion Sensor",      driver:"HomeKit HAP Motion Sensor"],       // MotionSensor
    "86": [name:"Occupancy Sensor",   driver:"HomeKit HAP Occupancy Sensor"],    // OccupancySensor
    "8A": [name:"Temperature Sensor", driver:"HomeKit HAP Temperature Sensor"],  // TemperatureSensor
    "82": [name:"Humidity Sensor",    driver:"HomeKit HAP Humidity Sensor"],     // HumiditySensor
    "84": [name:"Light Sensor",       driver:"HomeKit HAP Light Sensor"],        // LightSensor
    "8C": [name:"Window Shade",       driver:"HomeKit HAP Window Shade"],        // WindowCovering
    "40": [name:"Fan",                driver:"HomeKit HAP Fan"],                 // Fan v1
    "B7": [name:"Fan",                driver:"HomeKit HAP Fan"],                 // Fan v2
    "96": [name:"Battery",            driver:"HomeKit HAP Battery"],             // BatteryService
    "4A": [name:"Thermostat",         driver:"HomeKit HAP Thermostat"],          // Thermostat (generic; ecobee has its own rich driver)
    "41": [name:"Garage Door",        driver:"HomeKit HAP Garage Door"],         // GarageDoorOpener
]
// per-service characteristic of interest: HAP char code -> logical key
@Field static Map CHAR_MAP = [
    "49": ["26":"switch"],                                                   // On
    "47": ["26":"switch"],                                                   // On
    "43": ["26":"switch","8":"level","13":"hue","2F":"saturation","CE":"colorTemp"], // On, Brightness, Hue, Saturation, ColorTemperature
    "45": ["1D":"lockCurrent","1E":"lockTarget"],                           // LockCurrentState, LockTargetState
    "80": ["6A":"contact"],                                                  // ContactSensorState (0=closed, 1=open)
    "85": ["22":"motion"],                                                   // MotionDetected
    "86": ["71":"occupancy"],                                                // OccupancyDetected
    "8A": ["11":"temperature"],                                             // CurrentTemperature (°C)
    "82": ["10":"humidity"],                                                 // CurrentRelativeHumidity
    "84": ["6B":"illuminance"],                                             // CurrentAmbientLightLevel (lux)
    "8C": ["6D":"posCurrent","7C":"posTarget","72":"posState"],            // Current/Target/PositionState
    "40": ["26":"switch","29":"fanSpeed"],                                  // On, RotationSpeed
    "B7": ["B0":"active","29":"fanSpeed"],                                  // Active, RotationSpeed
    "96": ["68":"batteryLevel","79":"lowBattery"],                         // BatteryLevel, StatusLowBattery
    "4A": ["33":"tMode","F":"tOpState","35":"tSetpoint","D":"tCoolSet","12":"tHeatSet","11":"temperature","10":"humidity"], // Target/CurrentHeatingCoolingState, Target/Cooling/HeatingThreshold temps, temp, humidity
    "41": ["E":"doorCurrent","32":"doorTarget","24":"obstruction"],        // CurrentDoorState, TargetDoorState, ObstructionDetected
]
@Field static String NAME_SVC = "3E"   // AccessoryInformation
@Field static String NAME_CHAR = "23"  // Name
// service types whose child driver declares the Battery capability — a sibling Battery service (96) folds
// into these instead of creating a standalone Battery child. Mains-powered types (switch/light/fan/garage)
// are excluded, so a battery on one of those falls back to its own child.
@Field static List BATTERY_HOSTS = ["80","85","86","8A","82","84","45","8C","4A"]

def installed(){ updated() }
def updated(){
    unschedule(); try{ interfaces.rawSocket.close() }catch(e){}   // drop any prior socket cleanly so the accessory frees its slot before we reconnect
    state.live=false; state.diag=[]; state.connTry=0; state.mdnsTries=0; state.connInFlight=null; state.vtry=0; state.wretry=0
    if(settings.debugLog) sendEvent(name:"diag", value:"")
    state.remove("services")   // force fresh /accessories discovery on Save so the child topology rebuilds
    if(settings.setupCode && !isPaired()){ logInfo "HAP: setup code entered — pairing"; runIn(1,"pair") }
    else if(isPaired()){ runIn(2,"startSession"); runEvery10Minutes("ensureUp") }   // backstop only; verifyWatch backoff is the primary retry
}
def rediscover(){ state.remove("services"); if(isPaired()) startSession() else log.warn "HAP: not paired" }
// local-only forget: the accessory is NOT notified (use when it's offline). The slot stays used on the
// accessory until you reset HomeKit on it; unpair() is the clean path when the accessory is reachable.
def forget(){
    logInfo "HAP: forgetting pairing locally (accessory NOT notified — if it's still online, reset HomeKit on it to free the slot)"
    clearLocalPairing(); onUnpaired(); sendEvent(name:"hapStatus", value:"forgotten (local)")
}
// core RemovePairing success/forget hook: delete every child this accessory created
void onUnpaired(){
    getChildDevices()?.each{ try{ deleteChildDevice(it.deviceNetworkId) }catch(e){ log.warn "HAP: child delete ${it.deviceNetworkId}: ${e}" } }
    sendEvent(name:"services", value:"")
}

def dumpAccessories(){
    if(state.live && state.sess){ state.dumpReq=true; logInfo "HAP: requesting /accessories dump…"; sendEncrypted("GET /accessories HTTP/1.1\r\nHost: ${settings.ip}\r\n\r\n") }
    else { log.warn "HAP: not connected — open the session first (device must be paired and live)" }
}

// ===== core callbacks =====
// build the mapped-service list from /accessories and create one child per mapped service
void onAccessories(j){
    if(state.dumpReq){ state.dumpReq=false; dumpAcc(j) }
    def services=[]; def infoByAid=[:]; def batteryByAid=[:]
    j.accessories.each{ acc->
        infoByAid[acc.aid]=accInfo(acc)
        String accName = nameOf(acc)
        acc.services.each{ sv->
            String stype=hapCode(sv.type)
            if(stype=="96"){   // Battery service -> fold into this accessory's sibling sensor(s), not its own child
                def bc=[:]; sv.characteristics.each{ c-> def k=CHAR_MAP["96"]?.get(hapCode(c.type)); if(k) bc[k]=c.iid }
                if(bc) batteryByAid[acc.aid]=bc
                return
            }
            def mapped=SERVICE_MAP[stype]
            String dni="hk-${device.id}-${acc.aid}-${sv.iid}"
            if(mapped){
                def chars=[:]   // logical key -> iid
                sv.characteristics.each{ c-> def key=CHAR_MAP[stype]?.get(hapCode(c.type)); if(key) chars[key]=c.iid }
                if(chars.isEmpty()) return
                String label = nameOfService(acc, sv) ?: accName ?: "${mapped.name} ${acc.aid}.${sv.iid}"
                services << [aid:acc.aid, sIid:sv.iid, type:stype, driver:mapped.driver, dni:dni, label:label, chars:chars]
            } else {
                if(stype in ["3E","A2"]) return   // AccessoryInformation / ProtocolInformation — not user-facing
                // generic raw fallback: expose every readable characteristic so unknown service types still surface
                def rawChars=[:]
                sv.characteristics.each{ c-> def p=(c.perms?:[]); if(p.contains("pr")||p.contains("ev")) rawChars["iid${c.iid}(${hapCode(c.type)})"]=c.iid }
                if(rawChars.isEmpty()) return
                // tag the name with the HAP service type so unmapped children are self-identifying and
                // distinct (an accessory often has several unmapped services that share one base name)
                String base = nameOfService(acc, sv) ?: accName ?: "HomeKit ${acc.aid}.${sv.iid}"
                String label = "${base} [HAP svc ${stype}]"
                services << [aid:acc.aid, sIid:sv.iid, type:stype, driver:"HomeKit HAP Generic", dni:dni, label:label, raw:true, chars:rawChars]
            }
        }
    }
    // fold each accessory's Battery service into its sibling sensor(s); standalone Battery child only if nothing can host it
    batteryByAid.each{ aid, bc->
        def hosts = services.findAll{ it.aid==aid && BATTERY_HOSTS.contains(it.type) }
        if(hosts){ hosts.each{ it.chars.putAll(bc) } }
        else { services << [aid:aid, sIid:0, type:"96", driver:"HomeKit HAP Battery", dni:"hk-${device.id}-${aid}-batt", label:(infoByAid[aid]?.model ? "${infoByAid[aid].model} Battery" : "Battery ${aid}"), chars:bc] }
    }
    state.services=services
    applyAccessoryInfo(infoByAid)
    if(services.isEmpty()){ logInfo "HAP: no supported services found on this accessory (run Dump Accessories to see what it exposes)"; sendEvent(name:"services", value:"none mapped"); return }
    sendEvent(name:"services", value: services.collect{ "${(SERVICE_MAP[it.type]?.name ?: 'Generic('+it.type+')')}: ${it.label}" }.join(" | "))
    services.each{ s->
        def cd=getChildDevice(s.dni)
        if(!cd){ try{ cd=addChildDevice("RamSet", s.driver, s.dni, [name:s.label, label:s.label]) ; logInfo "HAP: created child '${s.label}' (${s.driver})" }catch(e){ log.warn "HAP: child create failed for ${s.dni}: ${e}" } }
        if(cd && s.raw){
            cd.sendEvent(name:"serviceType", value: s.type)
            String cur = cd.getLabel() ?: ""   // back-fill the [HAP svc ..] tag onto pre-existing generic children (skip if user-renamed/already tagged)
            if(!cur.contains("[HAP svc ")) cd.setLabel(s.label)
        }
        def inf=infoByAid[s.aid]
        if(cd && inf){
            ["manufacturer","model","serialNumber","firmware","hardware"].each{ k-> if(inf[k]!=null) cd.updateDataValue(k, inf[k].toString()) }
            ["manufacturer","model","firmware"].each{ k-> if(inf[k]!=null) cd.sendEvent(name:k, value: inf[k].toString()) }   // also as attributes on the child
        }
    }
}
// surface accessory metadata: parent Data fields + attributes (manufacturer/model/firmware), keyed off the
// primary accessory (lowest aid); also remember the Identify characteristic for the identify() command
void applyAccessoryInfo(Map infoByAid){
    if(!infoByAid){ return }
    def primAid = infoByAid.keySet().min()
    def inf = infoByAid[primAid] ?: [:]
    state.identifyAid = primAid; state.identifyIid = inf.identifyIid
    ["manufacturer","model","serialNumber","firmware","hardware"].each{ k-> if(inf[k]!=null) device.updateDataValue(k, inf[k].toString()) }
    if(inf.manufacturer!=null) sendEvent(name:"manufacturer", value: inf.manufacturer.toString())
    if(inf.model!=null) sendEvent(name:"model", value: inf.model.toString())
    if(inf.firmware!=null) sendEvent(name:"firmware", value: inf.firmware.toString())
}
def identify(){
    if(state.identifyIid==null){ log.warn "HAP: this accessory has no Identify characteristic"; return }
    writeChar(state.identifyAid as long, state.identifyIid as int, true); logInfo "HAP: Identify sent"
}
// route a /characteristics read or event to the right child device
void onCharacteristics(j){
    def vmap=[:]; j.characteristics.each{ vmap["${it.aid}.${it.iid}"]=it.value }
    (state.services ?: []).each{ s->
        def cd=getChildDevice(s.dni); if(!cd) return
        if(s.raw){   // generic child: dump all readable chars as one JSON attribute
            def m=[:]; s.chars.each{ label,iid-> def v=vmap["${s.aid}.${iid}"]; if(v!=null) m[label]=v }
            if(m) cd.sendEvent(name:"characteristics", value: groovy.json.JsonOutput.toJson(m))
            return
        }
        s.chars.each{ key, iid->
            def v=vmap["${s.aid}.${iid}"]; if(v==null) return
            switch(key){
                case "switch":      cd.sendEvent(name:"switch",      value: (v ? "on":"off")); break
                case "active":      cd.sendEvent(name:"switch",      value: ((v as int)>0 ? "on":"off")); break   // FanV2 Active
                case "level":       cd.sendEvent(name:"level",       value: (v as int)); break                   // Brightness % == Hubitat level
                case "hue":         cd.sendEvent(name:"hue",         value: hapHueToHub(v)); break               // HAP 0-360 -> Hubitat 0-100
                case "saturation":  cd.sendEvent(name:"saturation",  value: (v as int)); break
                case "colorTemp":   cd.sendEvent(name:"colorTemperature", value: miredToK(v)); break             // HAP mired -> Kelvin
                case "lockCurrent": cd.sendEvent(name:"lock",        value: ([0:"unlocked",1:"locked",2:"unknown",3:"unknown"][v as int] ?: "unknown")); break  // 2=jammed (no Hubitat value) -> unknown
                case "lockTarget":  break
                case "contact":     cd.sendEvent(name:"contact",     value: ((v as int)==0 ? "closed":"open")); break
                case "motion":      cd.sendEvent(name:"motion",      value: (v ? "active":"inactive")); break
                case "occupancy":   cd.sendEvent(name:"presence",    value: ((v as int)>0 ? "present":"not present")); break
                case "temperature": cd.sendEvent(name:"temperature", value: cToHub(v), unit:"°${isF()?'F':'C'}"); break
                case "humidity":    cd.sendEvent(name:"humidity",    value: (v as int), unit:"%"); break
                case "illuminance": cd.sendEvent(name:"illuminance", value: Math.round(v as BigDecimal) as int, unit:"lux"); break
                case "posCurrent":  cd.sendEvent(name:"position",    value: (v as int)); cd.sendEvent(name:"windowShade", value: shadeState(v as int)); break
                case "posTarget":   break
                case "posState":    break
                case "fanSpeed":    cd.sendEvent(name:"level", value: (v as int)); cd.sendEvent(name:"speed", value: pctToSpeed(v as int)); break
                case "batteryLevel":cd.sendEvent(name:"battery",     value: (v as int), unit:"%"); break
                case "lowBattery":  break   // surfaced via battery level; HAP StatusLowBattery is a separate flag
                case "tMode":       cd.sendEvent(name:"thermostatMode", value: ([0:"off",1:"heat",2:"cool",3:"auto"][v as int] ?: "auto")); cd.sendEvent(name:"supportedThermostatModes", value:'["off","heat","cool","auto"]'); break
                case "tOpState":    cd.sendEvent(name:"thermostatOperatingState", value: ([0:"idle",1:"heating",2:"cooling"][v as int] ?: "idle")); break
                case "tSetpoint":   cd.sendEvent(name:"thermostatSetpoint", value: cToHub(v)); break
                case "tCoolSet":    cd.sendEvent(name:"coolingSetpoint", value: cToHub(v)); break
                case "tHeatSet":    cd.sendEvent(name:"heatingSetpoint", value: cToHub(v)); break
                case "doorCurrent": cd.sendEvent(name:"door",        value: ([0:"open",1:"closed",2:"opening",3:"closing",4:"unknown"][v as int] ?: "unknown")); break
                case "doorTarget":  break   // CurrentDoorState is authoritative; TargetDoorState is only used for write-back
                case "obstruction": cd.sendEvent(name:"obstruction", value: (v ? "obstructed":"clear")); break
            }
        }
    }
}
// characteristics to GET on connect / refresh / keepalive
String readIds(){
    def ids=[]
    (state.services ?: []).each{ s-> s.chars.each{ k,iid-> ids << "${s.aid}.${iid}" } }
    return ids.join(",")
}
// subscribe (ev:true) to every mapped characteristic
String subscribeBody(){
    def ev=[]
    (state.services ?: []).each{ s-> s.chars.each{ k,iid-> ev << "{\"aid\":${s.aid},\"iid\":${iid},\"ev\":true}" } }
    String b="{\"characteristics\":[${ev.join(',')}]}"
    return "PUT /characteristics HTTP/1.1\r\nHost: ${settings.ip}\r\nContent-Type: application/hap+json\r\nContent-Length: ${b.getBytes('UTF-8').length}\r\nConnection: keep-alive\r\n\r\n"+b
}

// ===== child (component) commands -> HAP write-back =====
// all writes are optimistic (update the child immediately); the real read-back/event confirms
private Map svcOf(cd){ (state.services ?: []).find{ it.dni==cd.deviceNetworkId } }
private boolean writeKey(cd, String key, val){
    def s=svcOf(cd); if(!s || s.chars[key]==null){ log.warn "HAP: ${cd?.deviceNetworkId} has no '${key}' characteristic"; return false }
    writeChar(s.aid, s.chars[key] as int, val); return true
}
def componentRefresh(cd){ refresh() }
// Switch / Outlet / Light / Fan on-off (FanV2 uses Active instead of On)
def componentOn(cd){ def s=svcOf(cd); if(s?.chars?.active!=null) writeKey(cd,"active",1) else writeKey(cd,"switch",true); cd.sendEvent(name:"switch", value:"on") }
def componentOff(cd){ def s=svcOf(cd); if(s?.chars?.active!=null) writeKey(cd,"active",0) else writeKey(cd,"switch",false); cd.sendEvent(name:"switch", value:"off") }
// Dimmer / Light brightness, or Fan speed (whichever the service exposes)
def componentSetLevel(cd, level, duration=null){
    int lv=Math.max(0,Math.min(100, level as int)); def s=svcOf(cd)
    if(s?.chars?.level!=null){ if(lv>0 && s.chars.switch!=null) writeChar(s.aid, s.chars.switch as int, true); writeKey(cd,"level",lv) }
    else if(s?.chars?.fanSpeed!=null){ writeKey(cd,"fanSpeed",lv); cd.sendEvent(name:"speed", value: pctToSpeed(lv)) }
    cd.sendEvent(name:"level", value:lv); if(lv>0) cd.sendEvent(name:"switch", value:"on")
}
// ColorControl
def componentSetColor(cd, Map c){
    if(c.hue!=null){ writeKey(cd,"hue", hubHueToHap(c.hue)); cd.sendEvent(name:"hue", value: c.hue as int) }
    if(c.saturation!=null){ writeKey(cd,"saturation", c.saturation as int); cd.sendEvent(name:"saturation", value: c.saturation as int) }
    if(c.level!=null) componentSetLevel(cd, c.level)
}
def componentSetHue(cd, h){ writeKey(cd,"hue", hubHueToHap(h)); cd.sendEvent(name:"hue", value: h as int) }
def componentSetSaturation(cd, sa){ writeKey(cd,"saturation", sa as int); cd.sendEvent(name:"saturation", value: sa as int) }
def componentSetColorTemperature(cd, k, level=null, duration=null){ writeKey(cd,"colorTemp", kToMired(k)); cd.sendEvent(name:"colorTemperature", value: k as int); if(level!=null) componentSetLevel(cd, level) }
// Lock: LockTargetState 0=unsecured, 1=secured
def componentLock(cd){ writeKey(cd,"lockTarget",1); cd.sendEvent(name:"lock", value:"locked") }
def componentUnlock(cd){ writeKey(cd,"lockTarget",0); cd.sendEvent(name:"lock", value:"unlocked") }
// WindowShade position (0=closed, 100=open)
def componentSetPosition(cd, pos){ int p=Math.max(0,Math.min(100,pos as int)); writeKey(cd,"posTarget",p); cd.sendEvent(name:"position", value:p) }
// FanControl named speed
def componentSetSpeed(cd, speed){ String sp=speed?.toString(); int p=speedToPct(sp); writeKey(cd,"fanSpeed",p); cd.sendEvent(name:"speed", value:sp); cd.sendEvent(name:"level", value:p) }
// open/close is shared: GarageDoorOpener writes TargetDoorState (0=open,1=closed); WindowShade writes TargetPosition (100=open,0=closed)
def componentOpen(cd){ def s=svcOf(cd)
    if(s?.chars?.doorTarget!=null){ writeChar(s.aid, s.chars.doorTarget as int, 0); cd.sendEvent(name:"door", value:"opening") }
    else if(s?.chars?.posTarget!=null){ writeChar(s.aid, s.chars.posTarget as int, 100); cd.sendEvent(name:"windowShade", value:"opening") }
}
def componentClose(cd){ def s=svcOf(cd)
    if(s?.chars?.doorTarget!=null){ writeChar(s.aid, s.chars.doorTarget as int, 1); cd.sendEvent(name:"door", value:"closing") }
    else if(s?.chars?.posTarget!=null){ writeChar(s.aid, s.chars.posTarget as int, 0); cd.sendEvent(name:"windowShade", value:"closing") }
}
// Thermostat: TargetHeatingCoolingState 0=off/1=heat/2=cool/3=auto. In auto the active setpoints are the
// Cooling/Heating threshold chars; in heat/cool the single TargetTemperature is used (matches HAP semantics).
// Generic raw write (advanced): write any characteristic by iid on an unmapped service's child
def componentRawWrite(cd, iid, value){
    def s=svcOf(cd); if(!s){ log.warn "HAP: ${cd?.deviceNetworkId} unknown"; return }
    String v=value?.toString()
    def parsed = v?.isInteger() ? (v as Integer) : (v?.isBigDecimal() ? (v as BigDecimal) : ((v=="true"||v=="false") ? (v=="true") : v))
    writeChar(s.aid, iid as int, parsed); logInfo "HAP: raw write ${s.aid}.${iid} = ${parsed}"
}
def componentSetThermostatMode(cd, mode){ def v=[off:0,heat:1,cool:2,auto:3][mode?.toString()?.toLowerCase()]; if(v!=null){ writeKey(cd,"tMode",v); cd.sendEvent(name:"thermostatMode", value: mode.toString().toLowerCase()) } }
def componentSetHeatingSetpoint(cd, temp){ def s=svcOf(cd); def c=hubToC(temp); if(cd.currentValue("thermostatMode")=="auto" && s?.chars?.tHeatSet!=null) writeKey(cd,"tHeatSet",c) else writeKey(cd,"tSetpoint",c); cd.sendEvent(name:"heatingSetpoint", value: temp) }
def componentSetCoolingSetpoint(cd, temp){ def s=svcOf(cd); def c=hubToC(temp); if(cd.currentValue("thermostatMode")=="auto" && s?.chars?.tCoolSet!=null) writeKey(cd,"tCoolSet",c) else writeKey(cd,"tSetpoint",c); cd.sendEvent(name:"coolingSetpoint", value: temp) }

// ===== helpers =====
String nameOf(acc){ def sv=acc.services?.find{ hapCode(it.type)==NAME_SVC }; def c=sv?.characteristics?.find{ hapCode(it.type)==NAME_CHAR }; return c?.value?.toString() }
String nameOfService(acc, sv){ def c=sv.characteristics?.find{ hapCode(it.type)==NAME_CHAR }; return c?.value?.toString() }
boolean isF(){ return (location?.temperatureScale ?: "F") == "F" }
def round1(BigDecimal v){ return (v*10).setScale(0, java.math.RoundingMode.HALF_UP)/10 }
def cToHub(v){ if(v==null) return null; def c=(v as BigDecimal); return isF()? round1(c*9/5+32) : round1(c) }
def hubToC(v){ if(v==null) return null; def t=(v as BigDecimal); return isF()? round1((t-32)*5/9) : round1(t) }
// HAP Hue is 0-360°, Hubitat hue is 0-100
int hapHueToHub(v){ return Math.round((v as BigDecimal)/3.6) as int }
int hubHueToHap(v){ return Math.round((v as BigDecimal)*3.6) as int }
// HAP ColorTemperature is mired (reciprocal megakelvin); Hubitat uses Kelvin
int miredToK(v){ def m=(v as BigDecimal); return m>0 ? Math.round(1000000/m) as int : 0 }
int kToMired(v){ def k=(v as BigDecimal); return k>0 ? Math.round(1000000/k) as int : 0 }
String shadeState(int p){ return p<=0 ? "closed" : (p>=99 ? "open" : "partially open") }
String pctToSpeed(int p){ return p<=0 ? "off" : (p<=33 ? "low" : (p<=66 ? "medium" : "high")) }
int speedToPct(String s){ return (["off":0,"low":33,"medium-low":33,"medium":66,"medium-high":85,"high":100,"on":100,"auto":50][s] ?: 50) }
