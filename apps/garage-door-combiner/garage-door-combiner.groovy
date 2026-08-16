/*
 * Garage Door Combiner
 *
 * Marries a TRUSTED door-position sensor (contact/tilt) to a SEPARATE opener into one clean GarageDoorControl
 * device — so a controller whose own sensor lies (e.g. GoControl reporting "closed" while the door is open)
 * can be driven by a sensor you trust. State comes from the sensor; open/close actuates the opener;
 * opening/closing and a "close that didn't complete" (obstruction / blocked / reversed) are derived with a
 * travel timer. The opener can be a garage-door device (open/close commands) OR a momentary relay pulse.
 *
 * Author: RamSet
 * Version: 0.1.0
 */
definition(name: "Garage Door Combiner", namespace: "RamSet", author: "RamSet",
    description: "Combine a trusted door sensor + a separate opener into one clean GarageDoorControl device",
    category: "Convenience", iconUrl: "", iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/apps/garage-door-combiner/garage-door-combiner.groovy")

preferences { page(name: "mainPage") }

def mainPage(){
    dynamicPage(name: "mainPage", title: "Garage Door Combiner", install: true, uninstall: true){
        section("Door position — the TRUSTED sensor (all state comes from here)"){
            input "sensor", "capability.contactSensor", title: "Door contact/tilt sensor", required: true
            input "invert", "bool", title: "Sensor is inverted (reports 'open' when the door is CLOSED)", defaultValue: false
        }
        section("Opener — what actually moves the door"){
            input "opener", "capability.actuator", title: "Opener device (GoControl garage-door OR a relay)", required: true
            input "openerType", "enum", title: "How to trigger it",
                options: ["door": "Garage-door commands (open / close)", "pulse": "Momentary pulse (on = one button press; toggles the door)"],
                defaultValue: "door", required: true
            input "pulseMs", "number", title: "Pulse length in ms (momentary type only)", defaultValue: 800
        }
        section("Timing"){
            input "travelSecs", "number", title: "Door travel time in seconds (a full open or close)", defaultValue: 20, required: true
        }
        section(){
            input "childLabel", "string", title: "Name for the combined device", defaultValue: "Garage Door"
            input "debugLog", "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

def installed(){ initialize() }
def updated(){ unsubscribe(); unschedule(); initialize() }

def initialize(){
    def child = getChild()
    if(!child){
        child = addChildDevice("RamSet", "Garage Door Composite", "gdc-${app.id}",
            [name: "Garage Door", label: (settings.childLabel ?: "Garage Door"), isComponent: false])
    } else if(settings.childLabel && child.label != settings.childLabel){
        child.setLabel(settings.childLabel)
    }
    subscribe(sensor, "contact", sensorHandler)
    state.target = null
    syncFromSensor()   // reflect the sensor's current position immediately
}

private getChild(){ getChildDevice("gdc-${app.id}") }
private dlog(m){ if(settings.debugLog) log.debug "GDC: ${m}" }

// trusted sensor's contact -> door position ("open" / "closed")
private String pos(){
    String c = sensor?.currentValue("contact")
    if(settings.invert) c = (c == "open") ? "closed" : "open"
    return (c == "open") ? "open" : "closed"
}

private setDoor(String s){
    def child = getChild(); if(!child) return
    child.sendEvent(name: "door", value: s)
    if(s in ["open", "closed"]) child.sendEvent(name: "contact", value: s)
    dlog("door -> ${s}")
}
private setObstruction(String s){ getChild()?.sendEvent(name: "obstruction", value: s) }

def syncFromSensor(){ setDoor(pos()); setObstruction("clear") }

// ---- trusted sensor moved (covers manual wall-button operation too) ----
def sensorHandler(evt){
    String p = pos()
    dlog("sensor ${evt.value} -> pos ${p} (target=${state.target})")
    if(p == "closed"){
        setDoor("closed"); setObstruction("clear")
        unschedule("travelTimeout"); state.target = null
    } else { // door is up / partly up
        if(state.target == "close") return   // mid-close the sensor reads 'open' until it latches 'closed'; wait for that or the timeout
        setDoor("open")
        if(state.target == "open"){ setObstruction("clear"); unschedule("travelTimeout"); state.target = null }
    }
}

// ---- commands from the combined (child) device: HomeKit / dashboards ----
def childCmd(String action){
    String p = pos()
    dlog("cmd ${action} (door is ${p})")
    int t = (settings.travelSecs ?: 20) as int
    if(action == "open"){
        if(p == "open"){ setDoor("open"); return }
        state.target = "open"; setDoor("opening"); fire("open"); runIn(t, "travelTimeout")
    } else {
        if(p == "closed"){ setDoor("closed"); return }
        state.target = "close"; setDoor("closing"); fire("close"); runIn(t, "travelTimeout")
    }
}
def childRefresh(){ syncFromSensor() }

// actuate the opener
private fire(String action){
    if(settings.openerType == "pulse"){
        opener.on()
        runInMillis((settings.pulseMs ?: 800) as long, "pulseOff")
    } else {
        if(action == "open") opener.open() else opener.close()
    }
}
def pulseOff(){ try{ opener.off() }catch(e){} }

// travel time elapsed — judge success/failure from the TRUSTED sensor
def travelTimeout(){
    String p = pos(); String t = state.target; state.target = null
    dlog("timeout: target=${t}, sensor now ${p}")
    if(t == "close"){
        if(p == "closed"){ setDoor("closed"); setObstruction("clear") }
        else { setDoor("open"); setObstruction("detected"); log.warn "GDC: close did NOT complete — obstruction/blocked/reversed (door still open after ${settings.travelSecs}s)" }
    } else if(t == "open"){
        if(p == "open"){ setDoor("open") }
        else { setDoor("closed"); log.warn "GDC: open did NOT complete (door still closed after ${settings.travelSecs}s)" }
    }
}
