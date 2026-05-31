/**
 *  Spruce Controller zone child — Hubitat driver
 *  IMPORT URL: https://raw.githubusercontent.com/RamSet/hubitat/main/spruce-zone.groovy
 *
 *  Copyright 2020 PlaidSystems (original; author: NC, 2020)
 *  Licensed under the Apache License, Version 2.0
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Changelog:
 *   v1.2  (2026-05) RamSet cleanup:
 *                   - Hubitat-only: dropped SmartThings mnmn/vid metadata, the
 *                     isST tile block, and the SHPL portability block. installed()
 *                     no longer calls getHubPlatform(); initialize() no longer
 *                     branches on isSTHub.
 *                   - Added importUrl.
 *                   - Added optional debug-log toggle with 30-min auto-off.
 *   v1.1  (2020-09) Original Hubitat compatibility attempt.
 */

metadata {
    definition (name: "Spruce zone", namespace: "plaidsystems", author: "Plaid Systems",
                importUrl: "https://raw.githubusercontent.com/RamSet/hubitat/main/spruce-zone.groovy") {
        capability "Actuator"
        capability "Switch"
        capability "Switch Level"
        capability "Sensor"
        capability "Health Check"

        command "on"
        command "off"
        command "setLevel"
    }
    preferences {
        input name: "debugOutput", type: "bool",
              title: "<b>Enable debug logging</b>",
              description: "<br><i>Logs every parent-child message. Auto-turns off after 30 minutes. Leave OFF in normal use.</i><br>",
              defaultValue: false
    }
}

def installed() {
    if (debugOutput) log.trace "installed"
    initialize()
}

def updated() {
    if (debugOutput) log.trace "updated"
    if (debugOutput) runIn(1800, logsOff)
    initialize()
}

def logsOff() {
    log.warn "debug logging disabled"
    device.updateSetting("debugOutput", [value: "false", type: "bool"])
}

private initialize() {
    if (debugOutput) log.trace "initialize"
    sendEvent(name: "level", value: 5, descriptionText: "initialize level", displayed: false)
    sendEvent(name: "switch", value: "off", descriptionText: "initialize off", displayed: false)
    sendEvent(name: "DeviceWatch-DeviceStatus", value: "online")
    sendEvent(name: "healthStatus", value: "online")
    sendEvent(name: "DeviceWatch-Enroll",
              value: new groovy.json.JsonOutput().toJson([protocol: "cloud", scheme: "untracked"]),
              display: false, displayed: false)
}

def parse(String onoff) {
    if (debugOutput) log.debug "Child Desc: ${onoff}"
    sendEvent(name: "switch", value: onoff)
}

def on() {
    def eventMap = createEvent(dni: device.deviceNetworkId, value: 'on', duration: device.latestValue("level").toInteger())
    parent.childOn(eventMap)
}

def off() {
    def eventMap = createEvent(dni: device.deviceNetworkId, value: 'off', duration: 0)
    parent.childOff(eventMap)
}

def setLevel(level) {
    if (debugOutput) log.debug "setLevel ${level}"
    sendEvent(name: "level", value: level)
}

def ping() {
    // intentionally blank — the parent device polls health
}
