/*
 * Acuparse Weather Station
 *
 * Description:
 *   Polls Acuparse API JSON data and updates Hubitat attributes.
 *   Designed for use with Hubitat Package Manager (HPM).
 *
 * Author: RamSet
 * Version: 1.2.1
 * Date: 2025-04-25
 *
 * Changelog:
 *  v1.2.1 - Removed databaseInfo and lightningStrikeCount.
 *          - Suppressed duplicate fields using preferred sources.
 *          - lastUpdated split into lastUpdatedDate and lastUpdatedTime.
 *  v1.2.0 - Added timestamp parsing for specific fields.
 *          - Optional toggle to limit attribute updates to essential fields only.
 *  v1.1.0 - Added system health check from /api/system/health.
 *  v1.0.0 - Initial release.
 */

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

metadata {
    definition(name: "Acuparse Weather Station", namespace: "custom", author: "RamSet") {
        capability "Sensor"
        capability "Polling"
        capability "Refresh"

        attribute "temperatureF", "number"
        attribute "temperatureC", "number"
        attribute "humidity", "number"
        attribute "pressure_inHg", "number"
        attribute "windSpeedMPH", "number"
        attribute "windSpeedKMH", "number"
        attribute "lightIntensity", "number"
        attribute "uvIndex", "number"
        attribute "lastUpdated", "string"
        attribute "lastUpdatedDate", "string"
        attribute "lastUpdatedTime", "string"
        attribute "systemStatus", "string"
        attribute "realtimeStatus", "string"
    }

    preferences {
        input name: "host", type: "string", title: "Device IP or Hostname", required: true
        input name: "port", type: "number", title: "Port (default 80)", required: false
        input name: "updateInterval", type: "number", title: "Polling interval (seconds)", defaultValue: 60
        input name: "logLevel", type: "enum", title: "Logging Level", options: ["Off", "Info", "Debug", "Warn"], defaultValue: "Info"
        input name: "pullAllFields", type: "bool", title: "Pull All Fields (May Generate Many Events)", defaultValue: false
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
    def healthUri = "http://${settings.host}:${targetPort}/api/system/health"
    def weatherUri = "http://${settings.host}:${targetPort}/api/v1/json/dashboard/?main"
    
    def healthParams = [ uri: healthUri, contentType: "application/json" ]
    try {
        httpGet(healthParams) { healthResp ->
            if (healthResp?.status == 200 && healthResp?.data) {
                def healthData = healthResp.data
                updateAttr("systemStatus", healthData?.status)
                updateAttr("realtimeStatus", healthData?.realtime)
                pollWeatherData(weatherUri)
            } else {
                logWarn "Failed to fetch health data - Status: ${healthResp?.status}"
            }
        }
    } catch (e) {
        logWarn "Health check error: ${e.message}"
    }
}

private pollWeatherData(weatherUri) {
    def params = [ uri: weatherUri, contentType: "application/json" ]
    try {
        httpGet(params) { resp ->
            if (resp?.status == 200 && resp?.data) {
                def data = resp.data
                def coreFields = [
                    "main_tempC", "main_tempF", "main_relH", "atlas_lightIntensity",
                    "atlas_uvIndex", "main_windSpeedKMH", "main_windSpeedMPH", "realtimeStatus"
                ]
                def timestampFields = [
                    "lastUpdated", "main_lastUpdated", "main_high_temp_recorded", "main_low_temp_recorded",
                    "main_moon_lastFull", "main_moon_lastNew", "main_moon_nextFull", "main_moon_nextNew",
                    "main_moonrise", "main_moonset", "main_sunrise", "main_sunset",
                    "main_windSpeed_peak_recorded"
                ]
                def baseAttributes = ["temperatureC", "temperatureF", "humidity", "windSpeedKMH", "windSpeedMPH", "lightIntensity", "uvIndex"]

                def alreadySet = []

                ["main", "atlas"].each { section ->
                    data[section]?.each { key, value ->
                        def attrName = "${section}_${key}".replaceAll("\\s", "")
                        if (!settings.pullAllFields && !(attrName in coreFields)) return

                        def simplifiedName = key.replaceAll("\\s", "")
                        if (baseAttributes.contains(simplifiedName) && alreadySet.contains(simplifiedName)) return

                        if (timestampFields.contains(attrName) && value) {
                            try {
                                def zdt = ZonedDateTime.parse(value.toString())
                                def formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                                value = zdt.format(formatter)
                            } catch (e) {
                                logWarn "Timestamp parse failed for ${attrName}: ${e.message}"
                            }
                        }

                        updateAttr(attrName, value)
                        alreadySet << simplifiedName
                    }
                }

                updateAttr("temperatureF", data?.main?.tempF)
                updateAttr("temperatureC", data?.main?.tempC)
                updateAttr("humidity", data?.main?.relH)
                updateAttr("pressure_inHg", data?.main?.pressure_inHg)
                updateAttr("windSpeedMPH", data?.main?.windSpeedMPH)
                updateAttr("windSpeedKMH", data?.main?.windSpeedKMH)
                updateAttr("lightIntensity", data?.atlas?.lightIntensity)
                updateAttr("uvIndex", data?.atlas?.uvIndex)

                def lastUpdated = data?.main?.lastUpdated
                if (lastUpdated) {
                    updateAttr("lastUpdated", lastUpdated)
                    try {
                        def zdt = ZonedDateTime.parse(lastUpdated.toString())
                        updateAttr("lastUpdatedDate", zdt.toLocalDate().toString())
                        updateAttr("lastUpdatedTime", zdt.toLocalTime().toString())
                    } catch (e) {
                        logWarn "Failed to parse lastUpdated: ${e.message}"
                    }
                }
            } else {
                logWarn "Failed to fetch weather data - Status: ${resp?.status}"
            }
        }
    } catch (e) {
        logWarn "Weather poll error: ${e.message}"
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