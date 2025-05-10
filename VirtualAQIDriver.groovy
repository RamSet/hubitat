/**
 *  Virtual Air Quality Sensor - Device Driver for Hubitat Elevation
 *
 *  Driver Location:
 *  https://raw.githubusercontent.com/RamSet/hubitat/main/VirtualAQIDriver.groovy
 *
 *  HPM Manifest:
 *  https://raw.githubusercontent.com/RamSet/hubitat/refs/heads/main/manifest-aqi.json
 *
 *  Licensed under the Apache License, Version 2.0
 *  See: http://www.apache.org/licenses/LICENSE-2.0
 */

metadata {
    definition(name: "Virtual Air Quality (AQI) Driver", namespace: "RamSet", author: "RamSet") {
        capability "Sensor"
        capability "AirQuality"

        attribute "airQualityIndex", "number"
        attribute "airQuality", "string"

        command "airQualityIndex", [[name: "AQI*", type: "NUMBER", description: "Set AQI (0–500)"]]
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable description text logging", defaultValue: true
    }
}

def installed() {
    log.info "Virtual AQI Driver installed"
    initialize()
}

def updated() {
    log.info "Preferences updated"
    if (logEnable) runIn(1800, logsOff)
}

def initialize() {
    sendEvent(name: "airQualityIndex", value: 0)
    sendEvent(name: "airQuality", value: "Good")
}

def airQualityIndex(aqiInput) {
    def aqi = safeNumber(aqiInput)
    if (aqi == null) {
        log.error "Invalid AQI input: '${aqiInput}' — must be numeric."
        return
    }

    def level = getAqiLevel(aqi)

    sendEvent(name: "airQualityIndex", value: aqi)
    sendEvent(name: "airQuality", value: level)

    if (txtEnable) log.info "Air Quality Index set to ${aqi} (${level})"
    if (logEnable) log.debug "Set AQI: ${aqi} | Level: ${level}"
}

private BigDecimal safeNumber(val) {
    try {
        return val.toBigDecimal()
    } catch (e) {
        return null
    }
}

private String getAqiLevel(BigDecimal aqi) {
    if (aqi <= 50) return "Good"
    if (aqi <= 100) return "Moderate"
    if (aqi <= 150) return "Unhealthy for Sensitive Groups"
    if (aqi <= 200) return "Unhealthy"
    if (aqi <= 300) return "Very Unhealthy"
    return "Hazardous"
}

def logsOff() {
    log.warn "Debug logging disabled."
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}
