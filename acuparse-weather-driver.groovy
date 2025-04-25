/*
 * Acuparse Weather Station
 *
 * Description: Polls JSON weather data from Acuparse API and updates device attributes.
 *
 * Version History:
 * 1.0.0 - 2025-04-24
 *   - Initial version
 *   - Polls weather data from Acuparse JSON API
 *   - Supports dynamic IP/port configuration
 *   - Polling interval in seconds
 *   - Updates only changed values
 *   - Auto-disables debug logging after 5 minutes
 *   - Includes info/debug/warn/off logging levels
 *   - HPM metadata included
 *
 * Author: RamSet
 *
 * HPM Metadata:
 * {
 *   "package": "Acuparse Weather Station",
 *   "author": "RamSet",
 *   "namespace": "custom",
 *   "location": "https://raw.githubusercontent.com/RamSet/hubitat/main/acuparse-weather-driver.groovy",
 *   "description": "Weather driver for polling Acuparse JSON data",
 *   "required": true
 * }
 */

metadata {
    definition(name: "Acuparse Weather Station", namespace: "custom", author: "Your Name") {
        capability "Sensor"
        capability "Polling"
        capability "Refresh"

        attribute "temperatureF", "number"
        attribute "humidity", "number"
        attribute "pressure_inHg", "number"
        attribute "windSpeedMPH", "number"
        attribute "lightIntensity", "number"
        attribute "uvIndex", "number"
        attribute "lightningStrikeCount", "number"
        attribute "lastUpdated", "string"
    }

    preferences {
        input name: "host", type: "string", title: "Device IP or Hostname", required: true
        input name: "port", type: "number", title: "Port (default 80)", required: false
        input name: "updateInterval", type: "number", title: "Polling interval (seconds)", defaultValue: 60
        input name: "logLevel", type: "enum", title: "Logging Level", options: ["Off", "Info", "Debug", "Warn"], defaultValue: "Info"
    }
}

def installed() {
    initialize()
}

def updated() {
    unschedule()
    initialize()
}

def initialize() {
    logInfo "Initializing with interval: ${settings.updateInterval} seconds"
    if (settings.logLevel == "Debug") {
        logDebug "Debug logging will auto-disable in 5 minutes."
        runIn(300, disableDebugLogging)
    }
    scheduleNextPoll()
    poll()
}

def refresh() {
    poll()
}

def scheduleNextPoll() {
    int seconds = settings.updateInterval ?: 60
    runIn(seconds, poll)
}

def poll() {
    if (!settings.host) {
        logWarn "Host/IP not configured."
        return
    }

    def targetPort = settings.port ?: 80
    def uri = "http://${settings.host}:${targetPort}/api/v1/json/dashboard/?main"
    def params = [ uri: uri, contentType: "application/json" ]

    try {
        httpGet(params) { resp ->
            if (resp?.status == 200 && resp?.data) {
                def data = resp.data

                ["main", "atlas", "lightning"].each { section ->
                    data[section]?.each { key, value ->
                        def attrName = "${section}_${key}"
                        updateAttr(attrName, value)
                    }
                }

                // Dashboard-friendly simplified attributes
                updateAttr("temperatureF", data?.main?.tempF)
                updateAttr("humidity", data?.main?.relH)
                updateAttr("pressure_inHg", data?.main?.pressure_inHg)
                updateAttr("windSpeedMPH", data?.main?.windSpeedMPH)
                updateAttr("lightIntensity", data?.atlas?.lightIntensity)
                updateAttr("uvIndex", data?.atlas?.uvIndex)
                updateAttr("lightningStrikeCount", data?.lightning?.strikecount)
                updateAttr("lastUpdated", data?.main?.lastUpdated)
            } else {
                logWarn "Failed to fetch data from API - Status: ${resp?.status}"
            }
        }
    } catch (e) {
        logWarn "API poll error: ${e.message}"
    }

    scheduleNextPoll()
}

private updateAttr(name, value) {
    def current = device.currentValue(name)
    if (current?.toString() != value?.toString()) {
        sendEvent(name: name, value: value)
        logInfo "Updated ${name} = ${value}"
    } else {
        logDebug "No change for ${name}"
    }
}

private logDebug(msg) {
    if (settings.logLevel == "Debug") log.debug "[Acuparse] ${msg}"
}
private logInfo(msg) {
    if (settings.logLevel in ["Info", "Debug"]) log.info "[Acuparse] ${msg}"
}
private logWarn(msg) {
    if (settings.logLevel in ["Warn", "Debug"]) log.warn "[Acuparse] ${msg}"
}

def disableDebugLogging() {
    if (settings.logLevel == "Debug") {
        device.updateSetting("logLevel", [value: "Info", type: "enum"])
        logInfo "Debug logging disabled automatically after 5 minutes."
    }
}