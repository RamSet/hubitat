/*
 * Acuparse Weather Station
 *
 * Description:
 *   Polls Acuparse API JSON data and updates Hubitat attributes.
 *   Supports dynamic discovery of available fields. User can select additional fields via comma-separated list.
 *   Designed for use with Hubitat Package Manager (HPM).
 *
 * Author: RamSet
 * Version: 1.4.0
 * Date: 2025-04-25
 *
 * Changelog:
 *  v1.4.0 - NEW: Dynamic discovery of available fields from the latest API response.
 *           - Lists available fields via logs.
 *           - Allows selection of additional fields via comma-separated input.
 *           - Pull All Fields toggle still overrides and pulls everything.
 *
 *  v1.3.2 - Fixed multi-select support using static list.
 *  v1.3.1 - Multi-select fully patched.
 *  v1.3.0 - Added manual selection of extra fields.
 *  v1.2.3 - Added *_date and *_time breakdowns for timestamps.
 *  v1.2.2 - Timestamp formatting using ZonedDateTime with timezone.
 *  v1.2.1 - Filtered redundant main_* fields.
 *  v1.2.0 - Core/optional field filtering logic.
 *  v1.1.0 - System health integration.
 *  v1.0.0 - Initial release.
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
        attribute "lastUpdated_date", "string"
        attribute "lastUpdated_time", "string"
        attribute "systemStatus", "string"
        attribute "realtimeStatus", "string"
    }

    preferences {
        input name: "host", type: "string", title: "Device IP or Hostname", required: true
        input name: "port", type: "number", title: "Port (default 80)", required: false
        input name: "updateInterval", type: "number", title: "Polling interval (seconds)", defaultValue: 60
        input name: "logLevel", type: "enum", title: "Logging Level", options: ["Off", "Info", "Debug", "Warn"], defaultValue: "Info"
        input name: "pullAllFields", type: "bool", title: "Pull All Fields (Overrides Manual Selection)", defaultValue: false
        input name: "extraFieldsText", type: "string", title: "Additional Fields (comma-separated)", required: false,
              description: "Enter extra fields, separated by commas. Example: main_pressure_inHg, lightning_last_strike_ts"
    }
}

def installed() { initialize() }
def updated() { unschedule(); initialize() }

def initialize() {
    logInfo "Initializing with interval: ${settings.updateInterval} seconds"
    if (settings.logLevel == "Debug") {
        logDebug "Debug logging will auto-disable in 5 minutes."
        runIn(300, disableDebugLogging)
    }
    scheduleNextPoll()
    poll()
}

def refresh() { poll() }

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
    def coreFields = [
        "humidity", "lightIntensity", "uvIndex", "temperatureC", "temperatureF",
        "windSpeedKMH", "windSpeedMPH", "realtimeStatus"
    ]

    def timestampFields = [
        "atlas_lastUpdated", "lastUpdated", "lightning_last_strike_ts", "lightning_last_update",
        "main_moon_nextNew", "main_moonrise", "main_moonset", "main_sunrise", "main_sunset",
        "main_windSpeed_peak_recorded"
    ]

    def userExtras = (settings.extraFieldsText ?: "")
                        .split(",")
                        .collect { it.trim() }
                        .findAll { it }

    def params = [ uri: weatherUri, contentType: "application/json" ]
    try {
        httpGet(params) { resp ->
            if (resp?.status == 200 && resp?.data) {
                def data = resp.data

                def discoveredFields = []
                ["main", "atlas", "lightning"].each { section ->
                    data[section]?.each { key, _ ->
                        def attrName = "${section}_${key}".replaceAll("\\s", "")
                        discoveredFields << attrName
                    }
                }
                state.availableFields = discoveredFields.unique().sort()
                logInfo "Discovered Available Fields: ${state.availableFields.join(', ')}"

                ["main", "atlas", "lightning"].each { section ->
                    data[section]?.each { key, value ->
                        def attrName = "${section}_${key}".replaceAll("\\s", "")

                        if ((section == "main" && ["tempC", "tempF", "relH", "windSpeedKMH", "windSpeedMPH", "pressure_inHg"].contains(key)) ||
                            (section == "atlas" && ["lightIntensity", "uvIndex"].contains(key))) {
                            return
                        }

                        if (!settings.pullAllFields && !(attrName in coreFields || userExtras.contains(attrName))) return

                        if (timestampFields.contains(attrName) && value) {
                            def ts = formatTimestamp(value, attrName)
                            updateAttr(attrName, ts.formatted)
                            updateAttr("${attrName}_date", ts.date)
                            updateAttr("${attrName}_time", ts.time)
                        } else {
                            updateAttr(attrName, value)
                        }
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

                def ts = formatTimestamp(data?.main?.lastUpdated, "lastUpdated")
                updateAttr("lastUpdated", ts.formatted)
                updateAttr("lastUpdated_date", ts.date)
                updateAttr("lastUpdated_time", ts.time)
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

private formatTimestamp(value, name) {
    if (!value) return [formatted: value, date: null, time: null]
    try {
        ZonedDateTime zdt
        def defaultZone = java.time.ZoneId.of("America/Denver")

        if (value.toString() =~ /\d{4}-\d{2}-\d{2}T/) {
            zdt = ZonedDateTime.parse(value.toString()).withZoneSameInstant(defaultZone)
        } else {
            def formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")
            zdt = ZonedDateTime.parse(value.toString(), formatter).withZoneSameInstant(defaultZone)
        }

        def formatted = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))
        def datePart  = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        def timePart  = zdt.format(DateTimeFormatter.ofPattern("HH:mm:ss z"))

        return [formatted: formatted, date: datePart, time: timePart]
    } catch (e) {
        logWarn "Failed to format timestamp for ${name}: ${e.message}"
        return [formatted: value, date: null, time: null]
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