/*
 * Acuparse Weather Station
 *
 * Description:
 *   Polls Acuparse API JSON data and updates Hubitat attributes.
 *   Designed for use with Hubitat Package Manager (HPM).
 *
 * Author: RamSet
 * Version: 1.1.1
 * Date: 2025-04-24
 *
 * Changelog:
 *  v1.1.1 - Split timestamp fields into separate date and time attributes.
 *          - Added new attributes for date and time based on timestamp fields:
 *            Atlas_lastUpdated, LastUpdated, Lightning_last_strike_ts, Lightning_last_update,
 *            Main_high_temp_recorded, Main_lastUpdated, Main_low_temp_recorded, Main_moon_lastFull,
 *            Main_moon_lastNew, Main_moon_nextFull, Main_moon_nextNew, Main_moonrise,
 *            Main_moonset, Main_sunrise, Main_sunset.
 *          - Timestamps are now split into date (yyyy-MM-dd) and time (HH:mm:ss), with timezone considered.
 *          - Each timestamp field will now create two new attributes: *_date and *_time.
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
        attribute "Atlas_lastUpdated_date", "string"
        attribute "Atlas_lastUpdated_time", "string"
        attribute "LastUpdated_date", "string"
        attribute "LastUpdated_time", "string"
        attribute "Lightning_last_strike_ts_date", "string"
        attribute "Lightning_last_strike_ts_time", "string"
        attribute "Lightning_last_update_date", "string"
        attribute "Lightning_last_update_time", "string"
        attribute "Main_high_temp_recorded_date", "string"
        attribute "Main_high_temp_recorded_time", "string"
        attribute "Main_lastUpdated_date", "string"
        attribute "Main_lastUpdated_time", "string"
        attribute "Main_low_temp_recorded_date", "string"
        attribute "Main_low_temp_recorded_time", "string"
        attribute "Main_moon_lastFull_date", "string"
        attribute "Main_moon_lastFull_time", "string"
        attribute "Main_moon_lastNew_date", "string"
        attribute "Main_moon_lastNew_time", "string"
        attribute "Main_moon_nextFull_date", "string"
        attribute "Main_moon_nextFull_time", "string"
        attribute "Main_moon_nextNew_date", "string"
        attribute "Main_moon_nextNew_time", "string"
        attribute "Main_moonrise_date", "string"
        attribute "Main_moonrise_time", "string"
        attribute "Main_moonset_date", "string"
        attribute "Main_moonset_time", "string"
        attribute "Main_sunrise_date", "string"
        attribute "Main_sunrise_time", "string"
        attribute "Main_sunset_date", "string"
        attribute "Main_sunset_time", "string"
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

                // Update health-related attributes
                updateAttr("systemStatus", healthData?.status)
                updateAttr("realtimeStatus", healthData?.realtime)
                updateAttr("databaseInfo", healthData?.database)
                
                // After checking health, now poll weather data
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

                // Process weather data
                ["main", "atlas", "lightning"].each { section ->
                    data[section]?.each { key, value ->
                        def attrName = "${section}_${key}"
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
                updateAttr("lastUpdated", data?.main?.lastUpdated)
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
    // Define timestamp fields that need to be split into date and time
    def timestampFields = [
        "Atlas_lastUpdated", "LastUpdated", 
        "Lightning_last_strike_ts", "Lightning_last_update",
        "Main_high_temp_recorded", "Main_lastUpdated",
        "Main_low_temp_recorded", "Main_moon_lastFull",
        "Main_moon_lastNew", "Main_moon_nextFull",
        "Main_moon_nextNew", "Main_moonrise", 
        "Main_moonset", "Main_sunrise", "Main_sunset"
    ]

    if (name in timestampFields) {
        // Parse the timestamp and split into date and time
        def formattedData = parseTimestamp(value)
        
        // Define new attributes for date and time
        def dateName = "${name}_date"
        def timeName = "${name}_time"

        // Update date and time attributes separately
        updateAttr(dateName, formattedData.date)
        updateAttr(timeName, formattedData.time)
    } else {
        // For non-timestamp fields, update normally
        def current = device.currentValue(name)
        if (current?.toString() != value?.toString()) {
            sendEvent(name: name, value: value)
            logInfo "Updated ${name} = ${value}"
        } else {
            logDebug "No change for ${name}"
        }
    }
}

private parseTimestamp(String timestamp) {
    // Example: 2025-04-24T23:53:00-06:00
    def dateTimeFormat = "yyyy-MM-dd'T'HH:mm:ssXXX"
    def dateFormat = new SimpleDateFormat("yyyy-MM-dd")
    def timeFormat = new SimpleDateFormat("HH:mm:ss")

    try {
        def date = new Date().parse(dateTimeFormat, timestamp)

        // Date and Time
        def formattedDate = dateFormat.format(date)
        def formattedTime = timeFormat.format(date)

        // Return as a map
        return [date: formattedDate, time: formattedTime]
    } catch (Exception e) {
        logWarn "Failed to parse timestamp: ${timestamp}. Error: ${e.message}"
        return [date: null, time: null] // Return null values in case of failure
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