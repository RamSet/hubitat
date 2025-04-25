/**
 * Acuparse Weather Station Driver - v1.2.1
 *
 * Changelog:
 * 1.2.1 - Suppresses duplicate fields from "main_" and "atlas_" when base field exists
 * 1.2.0 - Parses ISO8601 timestamps, adds essential-only toggle, and improves attribute handling
 * 1.1.0 - Adds health check, dashboard JSON polling, and HPM compatibility
 */

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

metadata {
    definition(name: "Acuparse Weather Station", namespace: "custom", author: "user") {
        capability "Sensor"
        capability "Polling"

        attribute "temperatureC", "number"
        attribute "temperatureF", "number"
        attribute "humidity", "number"
        attribute "windSpeedKMH", "number"
        attribute "windSpeedMPH", "number"
        attribute "pressure_inHg", "number"
        attribute "lightIntensity", "number"
        attribute "uvIndex", "number"
        attribute "realtimeStatus", "string"
        attribute "systemStatus", "string"
        attribute "lastUpdated", "string"
        attribute "lastUpdatedDate", "string"
        attribute "lastUpdatedTime", "string"
    }

    preferences {
        input name: "acuparseIP", type: "text", title: "Acuparse IP Address", required: true
        input name: "pollInterval", type: "number", title: "Polling Interval (minutes)", defaultValue: 5
        input name: "pullAllFields", type: "bool", title: "Update all available fields", defaultValue: false
        input name: "logLevel", type: "enum", title: "Log Level", options: ["info", "debug", "warn", "off"], defaultValue: "info"
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
    logInfo "Initializing..."
    runIn(5, refresh)
    schedule("0 */${settings.pollInterval} * * * ?", refresh)
}

def refresh() {
    pollSystemStatus("http://${settings.acuparseIP}/api/system/health")
    pollWeatherData("http://${settings.acuparseIP}/api/v1/json/dashboard/?main")
}

private pollSystemStatus(uri) {
    try {
        httpGet(uri) { resp ->
            if (resp?.status == 200 && resp?.data) {
                updateAttr("systemStatus", resp.data?.status ?: "Unknown")
            } else {
                logWarn "System health check failed with status ${resp?.status}"
            }
        }
    } catch (e) {
        logWarn "System health poll error: ${e.message}"
    }
}

private pollWeatherData(uri) {
    def params = [ uri: uri, contentType: "application/json" ]
    try {
        httpGet(params) { resp ->
            if (resp?.status == 200 && resp?.data) {
                def data = resp.data

                def preferredFields = [
                    temperatureC: data?.main?.tempC,
                    temperatureF: data?.main?.tempF,
                    humidity:     data?.main?.relH,
                    windSpeedKMH: data?.main?.windSpeedKMH,
                    windSpeedMPH: data?.main?.windSpeedMPH,
                    lightIntensity: data?.atlas?.lightIntensity,
                    uvIndex:      data?.atlas?.uvIndex,
                    pressure_inHg: data?.main?.pressure_inHg
                ]

                preferredFields.each { attr, value ->
                    updateAttr(attr, value)
                }

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

                def skipList = preferredFields.keySet().collect { field ->
                    ["main_${field}", "atlas_${field}"]
                }.flatten()

                if (settings.pullAllFields) {
                    ["main", "atlas"].each { section ->
                        data[section]?.each { k, v ->
                            def attrName = "${section}_${k}".replaceAll("\\s", "")
                            if (attrName in skipList) return
                            if (k == "lastUpdated") return
                            updateAttr(attrName, v)
                        }
                    }
                }

                updateAttr("realtimeStatus", "online")

            } else {
                logWarn "Weather data fetch failed with status ${resp?.status}"
            }
        }
    } catch (e) {
        logWarn "Weather poll error: ${e.message}"
    }
}

private updateAttr(String name, value) {
    if (value == null) return
    def current = device.currentValue(name)
    if (current?.toString() != value.toString()) {
        sendEvent(name: name, value: value)
        logDebug "Updated ${name} = ${value}"
    }
}

private logInfo(msg)  { if (settings.logLevel == "info")  log.info msg }
private logDebug(msg) { if (settings.logLevel == "debug") log.debug msg }
private logWarn(msg)  { if (settings.logLevel in ["warn", "debug", "info"]) log.warn msg }