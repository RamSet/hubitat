/**
 *  Spruce Controller SST — Hubitat driver
 *  IMPORT URL: https://raw.githubusercontent.com/RamSet/hubitat/main/spruce-controller-sst.groovy
 *
 *  Copyright 2020 PlaidSystems (original)
 *  Licensed under the Apache License, Version 2.0
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Changelog:
 *   v2.72  (2026-05) RamSet cleanup:
 *                    - Hubitat-only: dropped SmartThings tiles, SmartThings
 *                      fingerprints, mnmn/vid metadata, and the SHPL portability
 *                      block. Replaced isSTHub branches with the Hubitat path.
 *                    - Bug: programOff() was logging "programEnd" — fixed.
 *                    - Bug: pumpMaster() iterated zone 1..17 (zone 17 mapped to
 *                      endpoint 18, the rain sensor) — now 1..16.
 *                    - Bug: createChildDevices() compared settings.pumpMasterZone
 *                      ("Zone 5", String) to int i, so the "PM" label never
 *                      applied. Now parses the zone number from the string.
 *                    - Bug: installed()/updated() returned response(...) — that's
 *                      a SmartThings construct. On Hubitat the cmd lists are
 *                      handled directly; switched to returning the lists.
 *                    - UX: added logging toggles (debugOutput, descTextEnable)
 *                      with 30-min auto-off, gated all log.debug behind them.
 *                    - UX: hid internal/utility commands (writeType, writeTime,
 *                      settingsMap, notify, updated) from the device command UI
 *                      so the button row only shows user-facing actions.
 *                      Implementations remain — uncomment the `command` lines
 *                      to expose them again.
 *                    - UX: added plain-language descriptions to every preference.
 *                    - Added importUrl pointing at the project's GitHub raw URL.
 *
 *   v2.71  (2020-09) Original Hubitat compatibility attempt.
 *   v2.70             Added Rain Sensor = Water Sensor Capability; Pump/Master.
 */
private def getVersion() { return "v2.72 2026-05" }

metadata {
    definition (name: "Spruce Controller SST", namespace: "plaidsystems", author: "plaidsystems",
                importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/main/spruce-controller-sst.groovy") {
        capability "Switch"
        capability "Actuator"
        capability "Water Sensor"
        capability "Sensor"
        capability "Configuration"
        capability "Refresh"
        capability "Health Check"

        command "on"
        command "off"
        command "childOn"
        command "childOff"

        command "wet"
        command "dry"

        command "zon"
        command "zoff"
        command 'programOn'
        command 'programOff'
        command 'programWait'
        command 'programEnd'

        command "config"
        command "refresh"
        command "rain"
        command "manual"
        command "manualTime"

        // Internal/utility commands — called by the Spruce Scheduler app, not
        // useful as device-page buttons. Uncomment to expose for debugging.
        // command "settingsMap"
        // command "writeTime"
        // command "writeType"
        // command "notify"
        // command "updated"

        fingerprint endpointId: "01", profileId: "0104", deviceId: "0002", deviceVersion: "00",
                    inClusters: "0000,0003,0004,0005,0006,0009,000A,000F,0B05",
                    outClusters: "0003, 0019",
                    manufacturer: "PLAID SYSTEMS", model: "PS-SPRZ16-01",
                    deviceJoinName: "Spruce Controller"
    }

    preferences {
        input title: "Version", description: getVersion(), type: "paragraph", element: "paragraph"

        section("Rain sensor") {
            input title: "Rain Sensor",
                  description: "If you have a physical rain sensor wired to the controller's RS input, enable this so the controller reports wet/dry. Leave OFF if you don't have one wired in.",
                  type: "paragraph", element: "paragraph"
            input "RainEnable", "bool", title: "<b>Rain Sensor Attached?</b>",
                  required: false, defaultValue: false, displayDuringSetup: true
        }

        section("Pump / master valve") {
            input name: "pumpMasterZone", type: "enum",
                  title: "<b>Pump or Master zone</b>",
                  description: "If one of your zones drives a pump or a master valve (rather than a sprinkler), pick it here so the controller knows to run it alongside every other zone. The Spruce Scheduler app will also configure this automatically if installed. Leave blank if you don't have one.",
                  required: false,
                  options: ["Zone 1","Zone 2","Zone 3","Zone 4","Zone 5","Zone 6","Zone 7","Zone 8",
                            "Zone 9","Zone 10","Zone 11","Zone 12","Zone 13","Zone 14","Zone 15","Zone 16"]
        }

        section("Zone devices") {
            input title: "Enable child devices for the zones you want to control manually or in automations. Spruce Scheduler works without enabling them here — these are only needed for direct on/off and Rule-Manager integration per zone.",
                  type: "paragraph", element: "paragraph"
            for (int i = 1; i <= 16; i++) {
                input name: "z${i}", type: "bool", title: "<b>Enable Zone ${i}</b>", defaultValue: false, displayDuringSetup: true
            }
        }

        section("Logging") {
            input name: "debugOutput", type: "bool",
                  title: "<b>Enable debug logging</b>",
                  description: "<br><i>Logs every Zigbee message and decision. Auto-turns off after 30 minutes so the log doesn't fill forever. Leave OFF in normal use.</i><br>",
                  defaultValue: false
            input name: "descTextEnable", type: "bool",
                  title: "<b>Enable description text logging</b>",
                  description: "<br><i>Logs human-readable status updates (e.g. \"Zone 3 on\", \"Rain sensor wet\") to the Logs page. Useful for monitoring. Default ON.</i><br>",
                  defaultValue: true
        }
    }
}

//----------------------zigbee parse-------------------------------//

def parse(String description) {
    if (debugOutput) log.debug "Parse description ${description}"
    def result = null
    def map = zigbee.parseDescriptionAsMap(description)
    if (debugOutput) log.debug "${map}"

    def endpoint = ( map.sourceEndpoint == null ? hextoint(map.endpoint) : hextoint(map.sourceEndpoint) )
    def value = ( map.sourceEndpoint == null ? hextoint(map.value) : null )
    def command = (value != null ? commandType(endpoint, map.clusterInt) : null)

    if (command != null && debugOutput) log.debug "${command} endpoint ${endpoint} value ${value} cluster ${map.clusterInt}"
    switch(command) {
      case "alarm":
        if (debugOutput) log.debug "alarm"
        break
      case "program":
        def onoff = (value == 1 ? "on" : "off")
        if (descTextEnable) log.info "${device.displayName} program ${onoff}"
        result = createEvent(name: "switch", value: onoff)
        break
      case "zone":
        def onoff = (value == 1 ? "on" : "off")
        def child = childDevices.find{it.deviceNetworkId == "${device.deviceNetworkId}:${endpoint}"}
        if (child) {
            child.sendEvent(name: "switch", value: onoff)
            if (descTextEnable) log.info "Zone ${endpoint - 1} ${onoff}"
        }
        break
      case "rainSensor":
        def wetdry = (value == 1 ? "wet" : "dry")
        if (!RainEnable) wetdry = "dry"
        if (descTextEnable) log.info "Rain sensor ${wetdry}"
        result = createEvent(name: "water", value: wetdry)
        break
      case "refresh":
        if (debugOutput) log.debug "refresh response"
        def child = childDevices.find{it.deviceNetworkId == "${device.deviceNetworkId}:19"}
        if (child) child.sendEvent(name: "switch", value: "off", isStateChange: true, displayed: true)
        break
      default:
        break
    }
    return result
}

//--------------------end zigbee parse-------------------------------//

def installed() {
    if (debugOutput) log.debug "installed"
    if (!childDevices) {
        removeChildDevices()
        createChildDevices()
    }
    return refresh() + configure()
}

def updated() {
    if (debugOutput) log.debug "updated"
    if (debugOutput) runIn(1800, logsOff)
    createChildDevices()
    return pumpMaster() + rain()
}

def logsOff() {
    log.warn "debug logging disabled"
    device.updateSetting("debugOutput", [value: "false", type: "bool"])
}

private void createChildDevices() {
    if (debugOutput) log.debug "create children"

    // Refresh button child (UI hack — tappable on/off button used to trigger refresh).
    if (!childDevices.find{it.deviceNetworkId == "${device.deviceNetworkId}:19"}) {
        if (debugOutput) log.debug "Add Refresh"
        def child = addChildDevice("Spruce zone", "${device.deviceNetworkId}:19",
                                   [completedSetup: true, label: "Refresh",
                                    isComponent: true, componentName: "Refresh",
                                    componentLabel: "Refresh"])
        if (debugOutput) log.debug "${child}"
        child.sendEvent(name: "switch", value: "off", displayed: false)
    }

    Integer pumpMasterIdx = parsePumpMasterZone()

    for (i in 1..16) {
        def dni = i + 1
        if (settings."${"z${i}"}") {
            def child = childDevices.find{it.deviceNetworkId == "${device.deviceNetworkId}:${dni}"}
            if (!child) {
                def childLabel = (state.oldLabel != null ? "${state.oldLabel} Zone${i}" : "Spruce Zone${i}")
                if (pumpMasterIdx != null && pumpMasterIdx == i) childLabel = "Spruce PM Zone${i}"
                child = addChildDevice("Spruce zone", "${device.deviceNetworkId}:${dni}",
                                       [completedSetup: true, label: "${childLabel}",
                                        isComponent: false])
                child.sendEvent(name: "switch", value: "off", displayed: false)
            }
            else if (device.label != state.oldLabel) {
                def childLabel = (state.oldLabel != null ? "${state.oldLabel} Zone${i}" : "Spruce Zone${i}")
                if (pumpMasterIdx != null && pumpMasterIdx == i) childLabel = "Spruce PM Zone${i}"
                child.setLabel("${childLabel}")
            }
        }
        else if (childDevices.find{it.deviceNetworkId == "${device.deviceNetworkId}:${dni}"}) {
            deleteChildDevice("${device.deviceNetworkId}:${dni}")
        }
    }
    state.oldLabel = device.label
}

// Parse "Zone N" string preference into integer N (1..16), or null if not set.
private Integer parsePumpMasterZone() {
    def raw = settings.pumpMasterZone
    if (!raw) return null
    try { return raw.toString().replaceFirst("Zone ", "").toInteger() } catch (e) { return null }
}

private removeChildDevices() {
    if (debugOutput) log.debug "remove all children"
    def children = getChildDevices()
    if (children != null) {
        children.each { deleteChildDevice(it.deviceNetworkId) }
    }
}


//----------------------------------commands--------------------------------------//
def notify(String val, String txt)  { if (debugOutput) log.debug "notify ${val} ${txt}" }
def programOn()                     { if (debugOutput) log.debug "programOn" }
def programWait()                   { if (debugOutput) log.debug "programWait" }
def programEnd()                    { if (debugOutput) log.debug "programEnd" }
def programOff()                    { if (debugOutput) log.debug "programOff"; off() }

def start() {
    if (debugOutput) log.debug "start"
    if (device.latestValue("pause") != "closed") endpause()
    on()
}

def pause() {
    if (debugOutput) log.debug "pause"
    def pauseCmds = []
    pauseCmds.push("st wattr 0x${device.deviceNetworkId} 1 6 0x4002 0x21 {0000}")
    return pauseCmds + "st cmd 0x${device.deviceNetworkId} 1 6 0 {}"
}

def endpause() {
    if (debugOutput) log.debug "endpause"
    sendEvent(name: "pause", value: "closed", descriptionText: "Pause end", displayed: true)
}


// on/off — redefined for Alexa to start manual schedule
def on() {
    if (debugOutput) log.debug "Alexa on"
    sendEvent(name: "switch", value: "on", descriptionText: "${device.displayName} on")
    sendEvent(name: "switch", value: "programOn", descriptionText: "Schedule on")
}
def off() {
    if (debugOutput) log.debug "Alexa off"
    sendEvent(name: "switch", value: "off", descriptionText: "Alexa turned program off")
    zoff()
}

def zon()  { "st cmd 0x${device.deviceNetworkId} 1 6 1 {}" }
def zoff() { "st cmd 0x${device.deviceNetworkId} 1 6 0 {}" }


// Commands to children
def childOn(valueMap) {
    if (debugOutput) log.debug valueMap
    def endpoint = valueMap.dni.replaceFirst("${device.deviceNetworkId}:","").toInteger()
    def duration = (valueMap.duration != null ? valueMap.duration.toInteger() : 0)
    def command = commandType(endpoint, 6)
    switch(command) {
      case "program":    zoneOn(endpoint, 0);        break
      case "zone":       zoneOn(endpoint, duration); break
      case "rainSensor": if (debugOutput) log.debug "rainSensor"; break
      case "refresh":    refresh(); break
    }
}

def childOff(valueMap) {
    def endpoint = valueMap.dni.replaceFirst("${device.deviceNetworkId}:","").toInteger()
    def command = commandType(endpoint, 6)
    switch(command) {
      case "program":    zoneOff(endpoint); break
      case "zone":       zoneOff(endpoint); break
      case "rainSensor": if (debugOutput) log.debug "rainSensor"; break
      case "refresh":
        if (debugOutput) log.debug "refresh"
        def child = childDevices.find{it.deviceNetworkId == "${device.deviceNetworkId}:19"}
        if (child) child.sendEvent(name: "switch", value: "off", isStateChange: true, displayed: true)
        break
    }
}

def commandType(endpoint, cluster) {
    if (cluster == 9) return "alarm"
    else if (endpoint == 1) return "program"
    else if (endpoint in 2..17) return "zone"
    else if (endpoint == 18) return "rainSensor"
    else if (endpoint == 19) return "refresh"
}

def zoneOn(endpoint, duration) {
    if (debugOutput) log.debug "zoneOn: ${endpoint} ${duration}"
    return zoneDuration(duration) + "st cmd 0x${device.deviceNetworkId} ${endpoint} 6 1 {}"
}

def zoneOff(endpoint) {
    if (debugOutput) log.debug "zoneOff: ${endpoint}"
    return "st cmd 0x${device.deviceNetworkId} ${endpoint} 6 0 {}"
}

def zoneDuration(int duration) {
    def hexDuration = hex(duration)
    def sendCmds = []
    sendCmds.push("st wattr 0x${device.deviceNetworkId} 1 6 0x4002 0x21 {00${hexDuration}}")
    return sendCmds
}
//------------------end commands----------------------------------//

def settingsMap(WriteTimes, attrType) {
    if (debugOutput) log.debug WriteTimes
    def i = 1
    def runTime
    def sendCmds = []
    while (i <= 17) {
        if (WriteTimes."${i}") {
            runTime = hex(Integer.parseInt(WriteTimes."${i}"))
            if (debugOutput) log.debug "${i} : $runTime"
            if (attrType == 4001) sendCmds.push("st wattr 0x${device.deviceNetworkId} ${i} 0x06 0x4001 0x21 {00${runTime}}")
            else                  sendCmds.push("st wattr 0x${device.deviceNetworkId} ${i} 0x06 0x4002 0x21 {00${runTime}}")
            sendCmds.push("delay 600")
        }
        i++
    }
    return sendCmds
}

def writeType(wEP, cycle) {
    if (debugOutput) log.debug "wt ${wEP} ${cycle}"
    "st wattr 0x${device.deviceNetworkId} ${wEP} 0x06 0x4001 0x21 {00" + hex(cycle) + "}"
}

def writeTime(wEP, runTime) {
    "st wattr 0x${device.deviceNetworkId} ${wEP} 0x06 0x4002 0x21 {00" + hex(runTime) + "}"
}

def configure() {
    sendEvent(name: "DeviceWatch-DeviceStatus", value: "online")
    sendEvent(name: "healthStatus", value: "online")
    sendEvent(name: "DeviceWatch-Enroll", value: 2 * 60 * 60, displayed: false,
              data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])
    config()
}

def config() {
    configureHealthCheck()
    if (debugOutput) log.debug "Configuring Reporting and Bindings ${device.deviceNetworkId} ${device.zigbeeId}"

    def configCmds = [
        "zdo bind 0x${device.deviceNetworkId} 1 1 6 {${device.zigbeeId}} {}", "delay 1000",
        "zdo bind 0x${device.deviceNetworkId} 1 1 0x09 {${device.zigbeeId}} {}", "delay 1000",
        "zdo bind 0x${device.deviceNetworkId} 1 1 0x0F {${device.zigbeeId}} {}", "delay 1000",
    ]
    // Bind cluster 0x0F on zones 1..16 (endpoints 2..17) plus rain sensor (ep 18).
    for (int ep = 2; ep <= 18; ep++) {
        configCmds += ["zdo bind 0x${device.deviceNetworkId} ${ep} 1 0x0F {${device.zigbeeId}} {}", "delay 1000"]
    }

    // Program switch reporting (cluster 6 attr 0).
    configCmds += ["zcl global send-me-a-report 6 0 0x10 1 0 {01}", "delay 500",
                   "send 0x${device.deviceNetworkId} 1 1", "delay 500"]

    // Zone status reporting (cluster 0x0F attr 0x55) on endpoints 1..18.
    for (int ep = 1; ep <= 18; ep++) {
        configCmds += ["zcl global send-me-a-report 0x0F 0x55 0x10 1 0 {01}", "delay 500",
                       "send 0x${device.deviceNetworkId} 1 ${ep}", "delay 500"]
    }

    // Alarm reporting (cluster 9 attr 0).
    configCmds += ["zcl global send-me-a-report 0x09 0x00 0x21 1 0 {00}", "delay 500",
                   "send 0x${device.deviceNetworkId} 1 1", "delay 500"]

    return configCmds + rain()
}

def ping() {
    if (debugOutput) log.debug "device health ping"
    return zigbee.onOffRefresh()
}

def rain() {
    if (debugOutput) log.debug "Rain sensor: ${RainEnable}"
    if (RainEnable) return "st wattr 0x${device.deviceNetworkId} 18 0x0F 0x51 0x10 {01}"
    else            return "st wattr 0x${device.deviceNetworkId} 18 0x0F 0x51 0x10 {00}"
}

def pumpMaster() {
    Integer pumpMasterIdx = parsePumpMasterZone()
    if (debugOutput) log.debug "Pump/Master zone: ${pumpMasterIdx}"

    def endpointMap = [:]
    // Zone N maps to endpoint N+1 (zone 1 = ep 2, zone 16 = ep 17). Endpoint 18
    // is the rain sensor, not a zone. Pre-v2.72 this loop ran 1..17 and wrote
    // a cycle to endpoint 18, which doesn't accept it.
    for (int zone = 1; zone <= 16; zone++) {
        def zoneCycle = (pumpMasterIdx != null && zone == pumpMasterIdx) ? 4 : 2
        endpointMap."${zone + 1}" = "${zoneCycle}"
    }
    return settingsMap(endpointMap, 4001)
}

def refresh() {
    if (debugOutput) log.debug "refresh pressed"
    def child = childDevices.find{it.deviceNetworkId == "${device.deviceNetworkId}:19"}
    if (child) child.sendEvent(name: "switch", value: "on", isStateChange: true, displayed: true)

    def refreshCmds = []
    for (int ep = 1; ep <= 17; ep++) {
        refreshCmds += ["st rattr 0x${device.deviceNetworkId} ${ep} 0x0F 0x55", "delay 500"]
    }
    refreshCmds += ["st rattr 0x${device.deviceNetworkId} 18 0x0F 0x51", "delay 500"]
    return refreshCmds
}

def healthPoll() {
    if (debugOutput) log.debug "healthPoll()"
    def cmds = refresh()
    cmds.each { sendHubCommand(hubitat.device.HubAction.newInstance(it, hubitat.device.Protocol.ZIGBEE)) }
}

def configureHealthCheck() {
    Integer hcIntervalMinutes = 12
    if (!state.hasConfiguredHealthCheck) {
        if (debugOutput) log.debug "Configuring Health Check, Reporting"
        unschedule("healthPoll")
        runEvery5Minutes("healthPoll")
        def healthEvent = [name: "checkInterval", value: hcIntervalMinutes * 60, displayed: false,
                           data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID]]
        sendEvent(healthEvent)
        childDevices.each { it.sendEvent(healthEvent) }
        state.hasConfiguredHealthCheck = true
    }
}

private hextoint(String hex) { Long.parseLong(hex, 16).toInteger() }
private hex(value) { new BigInteger(Math.round(value).toString()).toString(16) }
