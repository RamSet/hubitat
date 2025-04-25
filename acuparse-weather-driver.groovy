/*
 * Acuparse Weather Station
 *
 * Description:
 *   Polls Acuparse API JSON data and updates Hubitat attributes.
 *   Designed for use with Hubitat Package Manager (HPM).
 *
 * Author: RamSet
 * Version: 1.2.0
 * Date: 2025-04-24
 *
 * Changelog:
 *  v1.2.0 - Converts ISO8601 timestamp fields to readable local time format.
 *          - Applies to all known timestamp fields including wind speed peak.
 *          - Formatting happens inline during refresh; no new attributes created.
 *
 *  v1.1.0 - Added system health check from /api/system/health endpoint.
 *          - Fetches system status, realtime status, and database info first.
 *          - Weather data updated after health check.
 *          - New attributes: systemStatus, realtimeStatus, databaseInfo.
 *          - Improved logging and handling of attribute updates.
 *
 *  v1.0.0 - Initial release.
 *          - Driver that pulls values from Acuparse API, including weather data.
 *          - Attributes for temperature, humidity, wind speed, light intensity, UV index, and lightning strike count.
 *          - Fully configurable polling interval and host/port settings.
 *          - Includes logging options (Debug, Info, Warn).
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

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

metadata {
    definition(name: "Acuparse Weather Station", namespace: "custom", author: "RamSet") {
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
        attribute "systemStatus", "string"
        attribute "realtimeStatus", "string"
        attribute "databaseInfo", "string"
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
    def healthUri = "http://${settings.host}:${targetPort}/api/system/health"
    def weatherUri = "http://${settings.host}:${targetPort}/api/v1/json/dashboard/?main"

    // First, check system health status
    def healthParams = [ uri: healthUri, contentType: "application/json" ]
    try {
        httpGet(healthParams) { healthResp ->
            if (healthResp?.status == 200 && healthResp?.data) {
                def healthData = healthResp.data
                updateAttr("systemStatus", healthData?.status)
                updateAttr("realtimeStatus", healthData?.realtime)
                updateAttr("databaseInfo", healthData?.database)

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
                def timestampFields = [
                    "atlas_lastUpdated", "lastUpdated", "lightning_last_strike_ts", "lightning_last_update",
                    "main_high_temp_recorded", "main_lastUpdated", "main_low_temp_recorded",
                    "main_moon_lastFull", "main_moon_lastNew", "main_moon_nextFull", "main_moon_nextNew",
                    "main_moonrise", "main_moonset", "main_sunrise", "main_sunset", "main_windSpeed_peak_recorded"
                ]

                ["main", "atlas", "lightning"].each { section ->
                    data[section]?.each { key, value ->
                        def attrName = "${section}_${key}".replaceAll(" ", "")
                        if (timestampFields.contains(attrName) && value instanceof String) {
                            value = formatTimestamp(value)
                        }
                        updateAttr(attrName, value)
                    }
                }

                // Update simplified attributes
                updateAttr("temperatureF", data?.main?.tempF)
                updateAttr("humidity", data?.main?.relH)
                updateAttr("pressure_inHg", data?.main?.pressure_inHg)
                updateAttr("windSpeedMPH", data?.main?.windSpeedMPH)
                updateAttr("lightIntensity", data?.atlas?.lightIntensity)
                updateAttr("uvIndex", data?.atlas?.uvIndex)
                updateAttr("lightningStrikeCount", data?.lightning?.strikecount)
                updateAttr("lastUpdated", formatTimestamp(data?.main?.lastUpdated))
            } else {
                logWarn "Failed to fetch weather data - Status: ${resp?.status}"
            }
        }
    } catch (e) {
        logWarn "Weather poll error: ${e.message}"
    }

    scheduleNextPoll()
}

private String formatTimestamp(String isoTimestamp) {
    try {
        return ZonedDateTime.parse(isoTimestamp).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))
    } catch (DateTimeParseException e) {
        logWarn "Timestamp parsing failed for value: ${isoTimestamp}"
        return isoTimestamp
    }
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