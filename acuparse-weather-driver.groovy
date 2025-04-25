/*
 * Acuparse Weather Station
 *
 * Description:
 *   Polls Acuparse API JSON data and updates Hubitat attributes.
 *   Designed for use with Hubitat Package Manager (HPM).
 *
 * Author: RamSet
 * Version: 1.1.0
 * Date: 2025-04-24
 *
 * Changelog:
 *  v1.1.0 - Added system health check from /api/system/health endpoint.
 *          - Fetches system status, realtime status, and database info first.
 *          - Weather data updated after health check.
 *          - New attributes: systemStatus, realtimeStatus, databaseInfo.
 *          - Added timestamp splitting for several fields into date and time.
 *          - Fields with timestamp data include:
 *            - atlas_lastUpdated, lightning_last_strike_ts, lightning_last_update
 *            - main_high_temp_recorded, main_lastUpdated, main_low_temp_recorded
 *            - main_moon_lastFull, main_moon_lastNew, main_moon_nextFull
 *            - main_moon_nextNew, main_moonrise, main_moonset
 *            - main_sunrise, main_sunset
 *          - Improved logging and handling of attribute updates.
 * 
 * v1.0.0 - Initial release.
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
        
        // New attributes for split date and time
        attribute "atlas_lastUpdated_date", "string"
        attribute "atlas_lastUpdated_time", "string"
        attribute "lightning_last_strike_ts_date", "string"
        attribute "lightning_last_strike_ts_time", "string"
        attribute "lightning_last_update_date", "string"
        attribute "lightning_last_update_time", "string"
        attribute "main_high_temp_recorded_date", "string"
        attribute "main_high_temp_recorded_time", "string"
        attribute "main_lastUpdated_date", "string"
        attribute "main_lastUpdated_time", "string"
        attribute "main_low_temp_recorded_date", "string"
        attribute "main_low_temp_recorded_time", "string"
        attribute "main_moon_lastFull_date", "string"
        attribute "main_moon_lastFull_time", "string"
        attribute "main_moon_lastNew_date", "string"
        attribute "main_moon_lastNew_time", "string"
        attribute "main_moon_nextFull_date", "string"
        attribute "main_moon_nextFull_time", "string"
        attribute "main_moon_nextNew_date", "string"
        attribute "main_moon_nextNew_time", "string"
        attribute "main_moonrise_date", "string"
        attribute "main_moonrise_time", "string"
        attribute "main_moonset_date", "string"
        attribute "main_moonset_time", "string"
        attribute "main_sunrise_date", "string"
        attribute "main_sunrise_time", "string"
        attribute "main_sunset_date", "string"
        attribute "main_sunset_time", "string"
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

                // Example of specific fields to process
                // Make sure to process the fields correctly and split timestamps
                processTimestampField("atlas_lastUpdated", data?.atlas?.lastUpdated)
                processTimestampField("lightning_last_strike_ts", data?.lightning?.last_strike_ts)
                processTimestampField("lightning_last_update", data?.lightning?.last_update)
                processTimestampField("main_high_temp_recorded", data?.main?.high_temp_recorded)
                processTimestampField("main_lastUpdated", data?.main?.lastUpdated)
                processTimestampField("main_low_temp_recorded", data?.main?.low_temp_recorded)
                processTimestampField("main_moon_lastFull", data?.main?.moon_lastFull)
                processTimestampField("main_moon_lastNew", data?.main?.moon_lastNew)
                processTimestampField("main_moon_nextFull", data?.main?.moon_nextFull)
                processTimestampField("main_moon_nextNew", data?.main?.moon_nextNew)
                processTimestampField("main_moonrise", data?.main?.moonrise)
                processTimestampField("main_moonset", data?.main?.moonset)
                processTimestampField("main_sunrise", data?.main?.sunrise)
                processTimestampField("main_sunset", data?.main?.sunset)

                // Update simplified attributes
                updateAttr("temperatureF", data?.main?.tempF)
                updateAttr("humidity", data?.main?.relH)
                updateAttr("pressure_inHg", data?.main?.pressure_inHg)
                updateAttr("windSpeedMPH", data?.main?.windSpeedMPH)
                updateAttr("lightIntensity", data?.atlas?.lightIntensity)
                updateAttr("uvIndex", data?.atlas?.uvIndex)
                updateAttr("lightningStrikeCount", data?.lightning?.strikecount)
            } else {
                logWarn "Failed to fetch weather data - Status: ${resp?.status}"
            }
        }
    } catch (e) {
        logWarn "Weather poll error: ${e.message}"
    }

    scheduleNextPoll()
}

private processTimestampField(fieldName, timestamp) {
    if (timestamp) {
        try {
            // Parse the full timestamp string into a Date object
            def sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")
            Date date = sdf.parse(timestamp)

            // Format date and time separately
            def dateFormat = new SimpleDateFormat("yyyy-MM-dd")
            def timeFormat = new SimpleDateFormat("HH:mm:ss")

            def dateStr = dateFormat.format(date)
            def timeStr = timeFormat.format(date)

            // Send the updated date and time to the device
            sendEvent(name: "${fieldName}_date", value: dateStr)
            sendEvent(name: "${fieldName}_time", value: timeStr)
        } catch (Exception e) {
            logWarn "Failed to process timestamp for ${fieldName}: ${e.message}"
        }
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