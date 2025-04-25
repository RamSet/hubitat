import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// [metadata and preferences remain unchanged]

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
                def formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

                def timestampFields = [
                    "atlas_last Updated",
                    "lastUpdated",
                    "lightning_last_strike_ts",
                    "lightning_last_update",
                    "main_high_temp_recorded",
                    "main_last Updated",
                    "main_low_temp_recorded",
                    "main_moon_last Full",
                    "main_moon_last New",
                    "main_moon_next Full",
                    "main_moon_next New",
                    "main_moonrise",
                    "main_moonset",
                    "main_sunrise",
                    "main_sunset"
                ]

                ["main", "atlas", "lightning"].each { section ->
                    data[section]?.each { key, value ->
                        def attrName = "${section}_${key}"
                        def formattedValue = (attrName in timestampFields) ? formatTimestamp(value, formatter) : value
                        updateAttr(attrName, formattedValue)
                    }
                }

                updateAttr("temperatureF", data?.main?.tempF)
                updateAttr("humidity", data?.main?.relH)
                updateAttr("pressure_inHg", data?.main?.pressure_inHg)
                updateAttr("windSpeedMPH", data?.main?.windSpeedMPH)
                updateAttr("lightIntensity", data?.atlas?.lightIntensity)
                updateAttr("uvIndex", data?.atlas?.uvIndex)
                updateAttr("lightningStrikeCount", data?.lightning?.strikecount)

                def formattedLastUpdated = formatTimestamp(data?.main?.lastUpdated, formatter)
                updateAttr("lastUpdated", formattedLastUpdated)
            } else {
                logWarn "Failed to fetch weather data - Status: ${resp?.status}"
            }
        }
    } catch (e) {
        logWarn "Weather poll error: ${e.message}"
    }
    
    scheduleNextPoll()
}

private String formatTimestamp(raw, formatter) {
    try {
        return ZonedDateTime.parse(raw).format(formatter)
    } catch (Exception e) {
        logDebug "Timestamp parse failed for value: ${raw}"
        return raw
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