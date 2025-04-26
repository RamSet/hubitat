/**
 *  Virtual Air Quality Sensor - Device Driver for Hubitat Elevation
 *
 *  Licensed under the Apache License, Version 2.0
 *  See: http://www.apache.org/licenses/LICENSE-2.0
 */

metadata {
    definition (name: "Virtual Air Quality (AQI) Driver", namespace: "RamSet", author: "RamSet") {
        capability "AirQuality"
        capability "Sensor"
        capability "Temperature Measurement"
        command "airQualityIndex", ["Number"]
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable Debug Logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable Description Text Logging", defaultValue: true
    }
}

def installed() {
    log.warn "Driver installed. Initializing with default AQI value."
    airQualityIndex(0)
    initialize()
}

def updated() {
    log.info "Driver settings updated."
    log.warn "Debug logging is: ${logEnable == true}"
    log.warn "Description text logging is: ${txtEnable == true}"
    initialize()
}

def initialize() {
    if (logEnable) {
        log.debug "Scheduling debug logging to turn off automatically in 30 minutes."
        runIn(1800, logsOff)
    }
}

def logsOff() {
    log.warn "Debug logging disabled automatically."
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

def parse(String description) {
    // Not used for virtual device.
}

def airQualityIndex(Number aqi) {
    if (aqi == null || aqi < 0) {
        log.warn "Invalid AQI value (${aqi}). Defaulting to 0 (Unknown)."
        aqi = 0
    }

    def level = getAqiLevel(aqi)
    def descriptionText = "${device.displayName} AQI is ${aqi} (${level})"

    if (txtEnable) log.info "${descriptionText}"
    sendEvent(name: "airQualityIndex", value: aqi, descriptionText: descriptionText)
    sendEvent(name: "airQuality", value: level)

    logDebug("AQI value sent: ${aqi}, Level: ${level}")
}

def getAqiLevel(Number aqi) {
    if (aqi >= 0 && aqi <= 50) return "Excellent"
    if (aqi > 50 && aqi <= 100) return "Good"
    if (aqi > 100 && aqi <= 150) return "Fair"
    if (aqi > 150 && aqi <= 200) return "Inferior"
    if (aqi > 200 && aqi <= 500) return "Poor"
    return "Unknown"
}

def logDebug(msg) {
    if (logEnable) log.debug msg
}